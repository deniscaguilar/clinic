package com.endoacademy.clinic.platform.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.ClinicCreated
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.platform.api.PlatformAdminAccess
import java.util.UUID

enum class MembershipStatus { PENDING, ACTIVE, INACTIVE }

/** Quem age como `PLATFORM_ADMIN` (não pertence a nenhuma clínica). */
/**
 * Id do `app_user` derivado do e-mail. Dentro de uma clínica o banco não deixa ler usuários de outras clínicas, então o
 * convite grava com `ON CONFLICT DO NOTHING` num id que se calcula sem consultar; assim o login é reaproveitado sem
 * ler nem revelar que o e-mail já existia.
 */
object UserIds {
    fun forEmail(normalizedEmail: String): UUID = UUID.nameUUIDFromBytes("clinic-user:$normalizedEmail".toByteArray())
}

data class PlatformActor(val userId: UUID, val sub: String)

fun interface PlatformAdmins {
    fun find(sub: String): PlatformActor?
}

/** Exige `PLATFORM_ADMIN` (permissão `MANAGE_CLINICS`); quem não é recebe 403 e `ACCESS_DENIED` auditado. */
class PlatformGuard(
    private val admins: PlatformAdmins,
    private val unitOfWork: UnitOfWork,
    private val auditTrail: AuditTrail,
) {
    fun require(sub: String): PlatformActor = admins.find(sub) ?: run {
        unitOfWork.inPlatformScope(null) {
            auditTrail.recordPlatform(null, null, AuditEvent("ACCESS_DENIED", "PERMISSION", PERMISSION, AuditOutcome.DENIED))
        }
        throw PermissionDeniedException(PERMISSION)
    }

    private companion object {
        const val PERMISSION = "MANAGE_CLINICS"
    }
}

/** Entrega o [PlatformGuard] a outros módulos pela `platform.api`, sem expor o ator inteiro. */
class GuardedPlatformAdminAccess(private val guard: PlatformGuard) : PlatformAdminAccess {
    override fun require(actorSub: String): UUID = guard.require(actorSub).userId
}

/** Fronteira de transação no escopo `platform` (ADR-0010), opcionalmente já dentro do tenant da clínica afetada. */
interface UnitOfWork {
    fun <T> inPlatformScope(tenantId: UUID?, block: () -> T): T
}

fun interface EventPublisher {
    fun publish(event: Any)
}

data class StoredUser(val id: UUID, val sub: String?)

/** Gravações de clínica, usuário, vínculo e perfil. Rodam na transação aberta pelo [UnitOfWork]. */
interface ClinicStore {
    fun insertClinic(id: UUID, name: String)

    /** `email` já normalizado (minúsculas). */
    fun findUserByEmail(email: String): StoredUser?

    fun insertUser(id: UUID, email: String, name: String)

    /** `userSub` só vai preenchido quando o vínculo já nasce ativo (usuário que já tem login). */
    fun insertMembership(id: UUID, clinicId: UUID, userId: UUID, status: MembershipStatus, userSub: String?)

    fun insertRole(id: UUID, clinicId: UUID, name: String, permissions: Set<String>)

    fun assignRole(clinicId: UUID, membershipId: UUID, roleId: UUID)

    fun setUserSub(userId: UUID, sub: String)

    /** Passa o vínculo a `ACTIVE` e grava o `sub` no vínculo (`membership.user_sub`, usado pela leitura do próprio usuário). */
    fun activateMembership(clinicId: UUID, membershipId: UUID, sub: String)

    /** `false` quando a clínica não existe. */
    fun updateClinicName(id: UUID, name: String): Boolean

    /** Devolve a situação anterior, ou `null` quando a clínica não existe. */
    fun setClinicStatus(id: UUID, status: ClinicStatus): ClinicStatus?

    fun findMembership(clinicId: UUID, membershipId: UUID): MembershipInfo?

    /** Todas as clínicas, por nome. Só no escopo `platform` (a tabela `clinic` é a única que o `PLATFORM_ADMIN` lê inteira). */
    fun listClinics(): List<ClinicOverview>

    fun findClinic(id: UUID): ClinicOverview?

    /** Vínculos com o perfil `CLINIC_ADMIN` da clínica, por e-mail. */
    fun listClinicAdmins(clinicId: UUID): List<ClinicAdmin>
}

data class ClinicOverview(val id: UUID, val name: String, val status: ClinicStatus)

data class ClinicAdmin(val membershipId: UUID, val email: String, val name: String, val status: MembershipStatus)

enum class ClinicStatus { ACTIVE, INACTIVE }

data class MembershipInfo(
    val membershipId: UUID,
    val userId: UUID,
    val email: String,
    val name: String,
    val sub: String?,
    val status: MembershipStatus,
)

data class CreateClinicCommand(val name: String, val adminEmail: String, val adminName: String)

data class CreateClinicResult(val clinicId: UUID, val adminMembershipId: UUID, val adminStatus: MembershipStatus)

/** Perfis-modelo criados em toda clínica nova (spec, seção de perfis). As concessões vêm do catálogo. */
val ROLE_TEMPLATES = listOf(PermissionCatalog.CLINIC_ADMIN, "DOCTOR", "NURSE", "MANAGER", "STAFF")

/**
 * `CreateClinic` (`PLATFORM_ADMIN`), com o fluxo de convite em duas transações (plano, seção 2): a chamada ao provedor
 * de identidade fica **fora** de qualquer transação.
 * 1. Cria clínica, perfis-modelo, usuário do 1º admin (ou reaproveita o login existente) e o vínculo `PENDING`.
 * 2. Sem login ainda, chama o provedor; falha deixa o vínculo `PENDING`, visível para o reenvio.
 * 3. Grava o `sub` e passa o vínculo a `ACTIVE`.
 * O branding padrão nasce do evento [ClinicCreated] quando a tabela existir (S3).
 */
class CreateClinic(
    private val guard: PlatformGuard,
    private val store: ClinicStore,
    private val unitOfWork: UnitOfWork,
    private val identityProvider: IdentityProvider,
    private val catalog: PermissionCatalog,
    private val auditTrail: AuditTrail,
    private val events: EventPublisher,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun create(actorSub: String, command: CreateClinicCommand): CreateClinicResult {
        val actor = guard.require(actorSub)
        val valid = validated(command)

        val clinicId = newId()
        val membershipId = newId()
        val provisioned = unitOfWork.inPlatformScope(clinicId) {
            store.insertClinic(clinicId, valid.name)
            val roleIds = ROLE_TEMPLATES.associateWith { role ->
                newId().also { store.insertRole(it, clinicId, role, catalog.defaultGrants[role].orEmpty()) }
            }
            val existing = store.findUserByEmail(valid.adminEmail)
            val userId = existing?.id ?: UserIds.forEmail(valid.adminEmail).also { store.insertUser(it, valid.adminEmail, valid.adminName) }
            val alreadyHasLogin = existing?.sub != null
            store.insertMembership(membershipId, clinicId, userId, if (alreadyHasLogin) MembershipStatus.ACTIVE else MembershipStatus.PENDING, existing?.sub)
            store.assignRole(clinicId, membershipId, roleIds.getValue(PermissionCatalog.CLINIC_ADMIN))
            auditTrail.recordPlatform(
                actor.userId,
                clinicId,
                AuditEvent("CREATE_CLINIC", "CLINIC", clinicId.toString(), details = setOf("name", "adminEmail", "adminName")),
            )
            Provisioned(userId, alreadyHasLogin)
        }
        events.publish(ClinicCreated(clinicId))
        if (provisioned.active) return CreateClinicResult(clinicId, membershipId, MembershipStatus.ACTIVE)

        val sub = try {
            identityProvider.createUser(valid.adminEmail, valid.adminName)
        } catch (_: IdentityProviderException) {
            unitOfWork.inPlatformScope(clinicId) {
                auditTrail.recordPlatform(
                    actor.userId,
                    clinicId,
                    AuditEvent("CREATE_IDENTITY", "MEMBERSHIP", membershipId.toString(), AuditOutcome.FAILURE),
                )
            }
            return CreateClinicResult(clinicId, membershipId, MembershipStatus.PENDING)
        }
        unitOfWork.inPlatformScope(clinicId) {
            store.setUserSub(provisioned.userId, sub)
            store.activateMembership(clinicId, membershipId, sub)
            auditTrail.recordPlatform(actor.userId, clinicId, AuditEvent("ACTIVATE_MEMBERSHIP", "MEMBERSHIP", membershipId.toString()))
        }
        return CreateClinicResult(clinicId, membershipId, MembershipStatus.ACTIVE)
    }

    private data class Provisioned(val userId: UUID, val active: Boolean)

    private fun validated(command: CreateClinicCommand): CreateClinicCommand {
        val name = command.name.trim()
        val email = command.adminEmail.trim().lowercase()
        val adminName = command.adminName.trim()
        val valid = name.isNotEmpty() && name.length <= MAX_NAME && adminName.isNotEmpty() && adminName.length <= MAX_NAME &&
            email.length <= MAX_EMAIL && EMAIL.matches(email)
        if (!valid) throw ApiException(ErrorKind.BAD_REQUEST, "VALIDATION_FAILED")
        return CreateClinicCommand(name, email, adminName)
    }

    private companion object {
        const val MAX_NAME = 120
        const val MAX_EMAIL = 254
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}
