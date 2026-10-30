package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.identity.InMemoryIdentityProvider
import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.api.PermissionDefinition
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.platform.application.DeactivateMembership
import com.endoacademy.clinic.platform.application.IdentityProviderException
import com.endoacademy.clinic.platform.application.InviteUser
import com.endoacademy.clinic.platform.application.ListUsers
import com.endoacademy.clinic.platform.application.MembershipStatus
import com.endoacademy.clinic.platform.application.PermissionAuthorizer
import com.endoacademy.clinic.platform.application.PermissionCatalog
import com.endoacademy.clinic.platform.application.ResendInvitation
import com.endoacademy.clinic.platform.application.Transactions
import com.endoacademy.clinic.platform.application.UpdateUserRoles
import com.endoacademy.clinic.platform.application.UserIds
import com.endoacademy.clinic.support.FakeMemberStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class UserManagementTest {

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<AuditEvent>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += event
        }
    }

    private val clinic = UUID.randomUUID()
    private val manager = CurrentUser(UUID.randomUUID(), "sub-manager", clinic, setOf("MANAGE_USERS"))
    private val catalog = PermissionCatalog(
        listOf(object : PermissionContributor {
            override fun permissions() = listOf(PermissionDefinition("MANAGE_USERS", "usuários"))
        }),
    )
    private val store = FakeMemberStore()
    private val audit = RecordingAudit()
    private val identity = InMemoryIdentityProvider()
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private val authorizer = PermissionAuthorizer(catalog, audit)
    private val invite = InviteUser(authorizer, store, identity, audit, transactions)
    private val updateRoles = UpdateUserRoles(authorizer, store, audit, transactions)
    private val deactivate = DeactivateMembership(authorizer, store, audit, transactions)
    private val resend = ResendInvitation(authorizer, store, identity, audit, transactions)
    private val list = ListUsers(authorizer, store, transactions)

    private val adminRole = store.addRole("CLINIC_ADMIN")
    private val doctorRole = store.addRole("DOCTOR")
    private val staffRole = store.addRole("STAFF")
    private val adminMembership = store.addMember("admin@clinica.test", "Admin", MembershipStatus.ACTIVE, "sub-admin", adminRole)

    private fun api(block: () -> Unit) = assertThatThrownBy(block).isInstanceOf(ApiException::class.java).extracting { it as ApiException }

    @Test
    fun `invite creates the user, links the roles, creates the login and activates the membership`() {
        val result = invite.invite(manager, " Ana@Clinica.test ", "Ana Souza", setOf(doctorRole))

        assertThat(result.status).isEqualTo(MembershipStatus.ACTIVE)
        val member = store.find(result.membershipId)!!
        assertThat(member.email).isEqualTo("ana@clinica.test")
        assertThat(member.userId).isEqualTo(UserIds.forEmail("ana@clinica.test"))
        assertThat(member.roles.map { it.name }).containsExactly("DOCTOR")
        assertThat(member.sub).isEqualTo(identity.createUser("ana@clinica.test", "x"))
        assertThat(audit.events.map { it.action }).containsExactly("INVITE_USER", "ACTIVATE_MEMBERSHIP")
        assertThat(audit.events.first().details).containsExactlyInAnyOrder("email", "name", "roles")
    }

    @Test
    fun `an e-mail that already has a login elsewhere reuses it, skips the provider and answers like a new one`() {
        val elsewhere = UserIds.forEmail("admin@outra.test")
        store.knownUsers[elsewhere] = FakeMemberStore.User("admin@outra.test", "Admin", "sub-dca-owner")

        val reused = invite.invite(manager, "admin@outra.test", "Qualquer nome", setOf(doctorRole))
        val fresh = invite.invite(manager, "nova@pessoa.test", "Nova", setOf(doctorRole))

        assertThat(reused.status).isEqualTo(fresh.status).isEqualTo(MembershipStatus.ACTIVE)
        assertThat(store.find(reused.membershipId)!!.sub).isEqualTo("sub-dca-owner")
        assertThat(store.knownUsers.getValue(elsewhere).name).isEqualTo("Admin") // o nome do login existente não é sobrescrito
        assertThat(identity.users()).containsExactly("nova@pessoa.test") // o provedor só foi chamado para o e-mail novo
    }

    @Test
    fun `when the provider fails the membership stays pending and the failure is audited`() {
        identity.failNext(IdentityProviderException("indisponível"))

        val result = invite.invite(manager, "ana@clinica.test", "Ana", setOf(doctorRole))

        assertThat(result.status).isEqualTo(MembershipStatus.PENDING)
        assertThat(store.find(result.membershipId)!!.sub).isNull()
        assertThat(audit.events.map { it.action to it.outcome }).containsExactly("INVITE_USER" to AuditOutcome.SUCCESS, "CREATE_IDENTITY" to AuditOutcome.FAILURE)
    }

    @Test
    fun `inviting someone who is already active or pending in the clinic is a conflict, an inactive one is reactivated`() {
        invite.invite(manager, "ana@clinica.test", "Ana", setOf(doctorRole))
        api { invite.invite(manager, "ANA@clinica.test", "Ana", setOf(staffRole)) }.satisfies({
            assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
            assertThat(it.code).isEqualTo("USER_ALREADY_MEMBER")
        })

        val inactive = store.addMember("velho@clinica.test", "Velho", MembershipStatus.INACTIVE, "sub-velho", doctorRole)
        val again = invite.invite(manager, "velho@clinica.test", "Velho", setOf(staffRole))

        assertThat(again.membershipId).isEqualTo(inactive)
        assertThat(again.status).isEqualTo(MembershipStatus.ACTIVE)
        assertThat(store.find(inactive)!!.roles.map { it.name }).containsExactly("STAFF")
    }

    @Test
    fun `invalid data and unknown roles are rejected before anything is stored`() {
        val before = store.members.size
        listOf(
            { invite.invite(manager, "sem-arroba", "Ana", setOf(doctorRole)) },
            { invite.invite(manager, "ana@clinica.test", " ", setOf(doctorRole)) },
            { invite.invite(manager, "ana@clinica.test", "Ana", emptySet()) },
            { invite.invite(manager, "ana@clinica.test", "Ana", setOf(UUID.randomUUID())) },
        ).forEach { call -> api { call() }.satisfies({ assertThat(it.kind).isEqualTo(ErrorKind.BAD_REQUEST) }) }
        assertThat(store.members).hasSize(before)
        assertThat(identity.users()).isEmpty()
    }

    @Test
    fun `update replaces the roles but never removes the last active CLINIC_ADMIN`() {
        val ana = invite.invite(manager, "ana@clinica.test", "Ana", setOf(doctorRole)).membershipId

        updateRoles.update(manager, ana, setOf(staffRole, doctorRole))
        assertThat(store.find(ana)!!.roles.map { it.name }).containsExactly("DOCTOR", "STAFF")
        assertThat(audit.events.last().action).isEqualTo("UPDATE_USER_ROLES")

        api { updateRoles.update(manager, adminMembership, setOf(doctorRole)) }.satisfies({
            assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
            assertThat(it.code).isEqualTo("LAST_CLINIC_ADMIN")
        })
        assertThat(store.find(adminMembership)!!.roles.map { it.name }).containsExactly("CLINIC_ADMIN")

        updateRoles.update(manager, ana, setOf(adminRole)) // agora há dois administradores
        updateRoles.update(manager, adminMembership, setOf(doctorRole))
        assertThat(store.find(adminMembership)!!.roles.map { it.name }).containsExactly("DOCTOR")
    }

    @Test
    fun `keeping the CLINIC_ADMIN role while changing the others is allowed for the only admin`() {
        updateRoles.update(manager, adminMembership, setOf(adminRole, doctorRole))
        assertThat(store.find(adminMembership)!!.roles.map { it.name }).containsExactly("CLINIC_ADMIN", "DOCTOR")
    }

    @Test
    fun `deactivate blocks the membership, is idempotent and protects the last active CLINIC_ADMIN`() {
        val ana = invite.invite(manager, "ana@clinica.test", "Ana", setOf(doctorRole)).membershipId

        deactivate.deactivate(manager, ana)
        deactivate.deactivate(manager, ana)
        assertThat(store.find(ana)!!.status).isEqualTo(MembershipStatus.INACTIVE)
        assertThat(audit.events.count { it.action == "DEACTIVATE_MEMBERSHIP" }).isEqualTo(1)

        api { deactivate.deactivate(manager, adminMembership) }.satisfies({ assertThat(it.code).isEqualTo("LAST_CLINIC_ADMIN") })
        assertThat(store.find(adminMembership)!!.status).isEqualTo(MembershipStatus.ACTIVE)

        val second = store.addMember("admin2@clinica.test", "Admin 2", MembershipStatus.ACTIVE, "sub-admin2", adminRole)
        deactivate.deactivate(manager, adminMembership)
        assertThat(store.find(second)!!.status).isEqualTo(MembershipStatus.ACTIVE)
    }

    @Test
    fun `resend works only for pending memberships`() {
        identity.failNext(IdentityProviderException("indisponível"))
        val pending = invite.invite(manager, "ana@clinica.test", "Ana", setOf(doctorRole)).membershipId

        assertThat(resend.resend(manager, pending)).isEqualTo(MembershipStatus.ACTIVE)
        assertThat(store.find(pending)!!.sub).isNotNull()

        api { resend.resend(manager, pending) }.satisfies({
            assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
            assertThat(it.code).isEqualTo("INVITATION_NOT_PENDING")
        })
        api { resend.resend(manager, adminMembership) }.satisfies({ assertThat(it.code).isEqualTo("INVITATION_NOT_PENDING") })
        api { resend.resend(manager, UUID.randomUUID()) }.satisfies({ assertThat(it.kind).isEqualTo(ErrorKind.NOT_FOUND) })
    }

    @Test
    fun `resend with the provider down answers 502 and keeps the membership pending`() {
        identity.failNext(IdentityProviderException("indisponível"))
        val pending = invite.invite(manager, "ana@clinica.test", "Ana", setOf(doctorRole)).membershipId
        identity.failNext(IdentityProviderException("indisponível"))

        api { resend.resend(manager, pending) }.satisfies({
            assertThat(it.kind).isEqualTo(ErrorKind.BAD_GATEWAY)
            assertThat(it.code).isEqualTo("IDENTITY_PROVIDER_UNAVAILABLE")
        })
        assertThat(store.find(pending)!!.status).isEqualTo(MembershipStatus.PENDING)
    }

    @Test
    fun `unknown memberships answer 404`() {
        val unknown = UUID.randomUUID()
        api { updateRoles.update(manager, unknown, setOf(doctorRole)) }.satisfies({ assertThat(it.code).isEqualTo("MEMBERSHIP_NOT_FOUND") })
        api { deactivate.deactivate(manager, unknown) }.satisfies({ assertThat(it.code).isEqualTo("MEMBERSHIP_NOT_FOUND") })
    }

    @Test
    fun `list returns the members with their roles`() {
        invite.invite(manager, "ana@clinica.test", "Ana", setOf(doctorRole))
        assertThat(list.list(manager).map { it.name }).containsExactly("Admin", "Ana")
    }

    @Test
    fun `without MANAGE_USERS every operation is refused and audited as DENIED`() {
        val nobody = manager.copy(permissions = setOf("VIEW_PATIENTS"))
        val before = store.members.size
        listOf<() -> Unit>(
            { invite.invite(nobody, "ana@clinica.test", "Ana", setOf(doctorRole)) },
            { updateRoles.update(nobody, adminMembership, setOf(doctorRole)) },
            { deactivate.deactivate(nobody, adminMembership) },
            { resend.resend(nobody, adminMembership) },
            { list.list(nobody) },
        ).forEach { assertThatThrownBy(it).isInstanceOf(PermissionDeniedException::class.java) }

        assertThat(store.members).hasSize(before)
        assertThat(audit.events).hasSize(5).allSatisfy { assertThat(it.outcome).isEqualTo(AuditOutcome.DENIED) }
    }
}
