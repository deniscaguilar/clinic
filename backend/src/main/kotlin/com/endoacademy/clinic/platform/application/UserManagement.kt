package com.endoacademy.clinic.platform.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import java.util.UUID

data class RoleRef(val id: UUID, val name: String)

/** Usuário da clínica ativa, com vínculo e perfis. `sub` é nulo enquanto o provedor não criou o login. */
data class MemberView(
    val membershipId: UUID,
    val userId: UUID,
    val email: String,
    val name: String,
    val sub: String?,
    val status: MembershipStatus,
    val roles: List<RoleRef>,
)

data class InviteResult(val membershipId: UUID, val status: MembershipStatus)

/**
 * Usuários e vínculos da clínica ativa. Roda na transação do [Transactions]; a RLS limita tudo ao tenant (ADR-0010,
 * emenda da T2.6): a clínica só lê os usuários que têm vínculo com ela.
 */
interface MemberStore {
    /** Cria o usuário sem `sub`, a menos que já exista (`ON CONFLICT DO NOTHING`); nada é lido nem sobrescrito. */
    fun ensureUser(id: UUID, email: String, name: String)

    /** Vínculo deste usuário com a clínica ativa, em qualquer situação. */
    fun findByUser(userId: UUID): MemberView?

    fun find(membershipId: UUID): MemberView?

    fun list(): List<MemberView>

    fun insertMembership(id: UUID, clinicId: UUID, userId: UUID, status: MembershipStatus, userSub: String?)

    /** Só devolve perfis da clínica ativa. */
    fun rolesById(ids: Collection<UUID>): List<RoleRef>

    fun replaceRoles(clinicId: UUID, membershipId: UUID, roleIds: Set<UUID>)

    /** Grava o `sub` apenas se o usuário ainda não tem um. */
    fun setUserSub(userId: UUID, sub: String)

    fun activate(clinicId: UUID, membershipId: UUID, sub: String)

    fun setStatus(membershipId: UUID, status: MembershipStatus)

    /** Ids dos vínculos `ACTIVE` que têm o perfil `CLINIC_ADMIN`. */
    fun activeClinicAdmins(): Set<UUID>
}

private const val MANAGE_USERS = "MANAGE_USERS"
private const val MAX_NAME = 120
private const val MAX_EMAIL = 254
private const val MAX_ROLES = 20
private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

private fun membershipNotFound() = ApiException(ErrorKind.NOT_FOUND, "MEMBERSHIP_NOT_FOUND")

private fun lastAdmin() = ApiException(ErrorKind.CONFLICT, "LAST_CLINIC_ADMIN")

private fun validRoles(store: MemberStore, roleIds: Set<UUID>): List<RoleRef> {
    if (roleIds.isEmpty() || roleIds.size > MAX_ROLES) throw ApiException(ErrorKind.BAD_REQUEST, "VALIDATION_FAILED")
    val roles = store.rolesById(roleIds)
    if (roles.size != roleIds.size) throw ApiException(ErrorKind.BAD_REQUEST, "ROLE_NOT_FOUND")
    return roles
}

/** Impede que a clínica fique sem nenhum `CLINIC_ADMIN` ativo: `membershipId` deixa de ser administrador ativo. */
private fun requireAnotherAdmin(store: MemberStore, member: MemberView) {
    val isActiveAdmin = member.status == MembershipStatus.ACTIVE && member.roles.any { it.name == PermissionCatalog.CLINIC_ADMIN }
    if (isActiveAdmin && store.activeClinicAdmins().none { it != member.membershipId }) throw lastAdmin()
}

class ListUsers(private val authorizer: Authorizer, private val store: MemberStore, private val transactions: Transactions) {
    fun list(user: CurrentUser): List<MemberView> {
        authorizer.require(user, MANAGE_USERS)
        return transactions.inTransaction { store.list() }
    }
}

/**
 * `InviteUser` (`MANAGE_USERS`), com o fluxo de convite em duas transações (plano, seção 2). O e-mail identifica o login
 * na plataforma toda: se já existe em outra clínica, só se cria o vínculo (**mesmo login**, sem novo convite) e a
 * resposta é a mesma de um e-mail novo. O caso de uso não lê o usuário antes de criá-lo: o id vem do e-mail
 * ([UserIds]) e a gravação é `ON CONFLICT DO NOTHING`, então nada revela se o e-mail já existia.
 */
class InviteUser(
    private val authorizer: Authorizer,
    private val store: MemberStore,
    private val identityProvider: IdentityProvider,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    private data class Pending(val membershipId: UUID, val userId: UUID, val email: String, val name: String)

    fun invite(user: CurrentUser, email: String, name: String, roleIds: Set<UUID>): InviteResult {
        authorizer.require(user, MANAGE_USERS)
        val normalized = email.trim().lowercase()
        val trimmedName = name.trim()
        if (trimmedName.isEmpty() || trimmedName.length > MAX_NAME || normalized.length > MAX_EMAIL || !EMAIL.matches(normalized)) {
            throw ApiException(ErrorKind.BAD_REQUEST, "VALIDATION_FAILED")
        }

        var pending: Pending? = null
        var membershipId = newId()
        var status = MembershipStatus.ACTIVE
        transactions.inTransaction {
            validRoles(store, roleIds)
            val userId = UserIds.forEmail(normalized)
            store.ensureUser(userId, normalized, trimmedName)
            val existing = store.findByUser(userId)
            if (existing != null && existing.status != MembershipStatus.INACTIVE) throw ApiException(ErrorKind.CONFLICT, "USER_ALREADY_MEMBER")
            if (existing == null) store.insertMembership(membershipId, user.clinicId, userId, MembershipStatus.PENDING, null) else membershipId = existing.membershipId
            val sub = store.find(membershipId)?.sub // com o vínculo criado, o usuário passa a ser legível pela clínica
            if (sub != null) {
                store.activate(user.clinicId, membershipId, sub)
            } else {
                status = MembershipStatus.PENDING
                if (existing != null) store.setStatus(membershipId, MembershipStatus.PENDING)
            }
            store.replaceRoles(user.clinicId, membershipId, roleIds)
            auditTrail.record(user, AuditEvent("INVITE_USER", "MEMBERSHIP", membershipId.toString(), details = setOf("email", "name", "roles")))
            if (sub == null) pending = Pending(membershipId, userId, normalized, trimmedName)
        }
        val toInvite = pending ?: return InviteResult(membershipId, status)

        val newSub = try {
            identityProvider.createUser(toInvite.email, toInvite.name)
        } catch (_: IdentityProviderException) {
            auditTrail.record(user, AuditEvent("CREATE_IDENTITY", "MEMBERSHIP", toInvite.membershipId.toString(), AuditOutcome.FAILURE))
            return InviteResult(membershipId, MembershipStatus.PENDING)
        }
        transactions.inTransaction {
            store.setUserSub(toInvite.userId, newSub)
            store.activate(user.clinicId, toInvite.membershipId, newSub)
            auditTrail.record(user, AuditEvent("ACTIVATE_MEMBERSHIP", "MEMBERSHIP", toInvite.membershipId.toString()))
        }
        return InviteResult(membershipId, MembershipStatus.ACTIVE)
    }
}

class UpdateUserRoles(
    private val authorizer: Authorizer,
    private val store: MemberStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun update(user: CurrentUser, membershipId: UUID, roleIds: Set<UUID>) {
        authorizer.require(user, MANAGE_USERS)
        transactions.inTransaction {
            val roles = validRoles(store, roleIds)
            val member = store.find(membershipId) ?: throw membershipNotFound()
            if (roles.none { it.name == PermissionCatalog.CLINIC_ADMIN }) requireAnotherAdmin(store, member)
            store.replaceRoles(user.clinicId, membershipId, roleIds)
            auditTrail.record(user, AuditEvent("UPDATE_USER_ROLES", "MEMBERSHIP", membershipId.toString(), details = setOf("roles")))
        }
    }
}

/** Desativa o vínculo (o login do provedor segue ativo: o usuário pode ter outras clínicas). Repetir não faz nada. */
class DeactivateMembership(
    private val authorizer: Authorizer,
    private val store: MemberStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun deactivate(user: CurrentUser, membershipId: UUID) {
        authorizer.require(user, MANAGE_USERS)
        transactions.inTransaction {
            val member = store.find(membershipId) ?: throw membershipNotFound()
            if (member.status == MembershipStatus.INACTIVE) return@inTransaction
            requireAnotherAdmin(store, member)
            store.setStatus(membershipId, MembershipStatus.INACTIVE)
            auditTrail.record(user, AuditEvent("DEACTIVATE_MEMBERSHIP", "MEMBERSHIP", membershipId.toString()))
        }
    }
}

/** `ResendInvitation` (`MANAGE_USERS`): só para vínculo `PENDING`. Chamada ao provedor fora de transação. */
class ResendInvitation(
    private val authorizer: Authorizer,
    private val store: MemberStore,
    private val identityProvider: IdentityProvider,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun resend(user: CurrentUser, membershipId: UUID): MembershipStatus {
        authorizer.require(user, MANAGE_USERS)
        val member = transactions.inTransaction { store.find(membershipId) } ?: throw membershipNotFound()
        if (member.status != MembershipStatus.PENDING) throw ApiException(ErrorKind.CONFLICT, "INVITATION_NOT_PENDING")

        val sub = try {
            member.sub?.also { identityProvider.resendInvitation(member.email) } ?: identityProvider.createUser(member.email, member.name)
        } catch (_: IdentityProviderException) {
            auditTrail.record(user, AuditEvent("RESEND_INVITATION", "MEMBERSHIP", membershipId.toString(), AuditOutcome.FAILURE))
            throw ApiException(ErrorKind.BAD_GATEWAY, "IDENTITY_PROVIDER_UNAVAILABLE")
        }
        transactions.inTransaction {
            store.setUserSub(member.userId, sub)
            store.activate(user.clinicId, membershipId, sub)
            auditTrail.record(user, AuditEvent("RESEND_INVITATION", "MEMBERSHIP", membershipId.toString()))
        }
        return MembershipStatus.ACTIVE
    }
}
