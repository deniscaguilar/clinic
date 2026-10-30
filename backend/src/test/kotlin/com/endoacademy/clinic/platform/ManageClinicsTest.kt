package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.identity.InMemoryIdentityProvider
import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.platform.application.ClinicAdmin
import com.endoacademy.clinic.platform.application.ClinicStatus
import com.endoacademy.clinic.platform.application.ClinicOverview
import com.endoacademy.clinic.platform.application.GetClinic
import com.endoacademy.clinic.platform.application.ListClinics
import com.endoacademy.clinic.platform.application.PermissionCatalog
import com.endoacademy.clinic.platform.application.IdentityProviderException
import com.endoacademy.clinic.platform.application.MembershipStatus
import com.endoacademy.clinic.platform.application.PlatformActor
import com.endoacademy.clinic.platform.application.PlatformAdmins
import com.endoacademy.clinic.platform.application.PlatformGuard
import com.endoacademy.clinic.platform.application.ResendClinicAdminInvitation
import com.endoacademy.clinic.platform.application.SetClinicStatus
import com.endoacademy.clinic.platform.application.UnitOfWork
import com.endoacademy.clinic.platform.application.UpdateClinic
import com.endoacademy.clinic.support.FakeClinicStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ManageClinicsTest {

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<Triple<UUID?, UUID?, AuditEvent>>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += Triple(user?.userId, user?.clinicId, event)
        }

        override fun recordPlatform(actorUserId: UUID?, tenantId: UUID?, event: AuditEvent) {
            events += Triple(actorUserId, tenantId, event)
        }
    }

    private val adminId = UUID.randomUUID()
    private val store = FakeClinicStore()
    private val audit = RecordingAudit()
    private val identity = InMemoryIdentityProvider()
    private val unitOfWork = object : UnitOfWork {
        override fun <T> inPlatformScope(tenantId: UUID?, block: () -> T): T = block()
    }
    private val guard = PlatformGuard(PlatformAdmins { if (it == "sub-admin") PlatformActor(adminId, it) else null }, unitOfWork, audit)
    private val update = UpdateClinic(guard, store, unitOfWork, audit)
    private val status = SetClinicStatus(guard, store, unitOfWork, audit)
    private val resend = ResendClinicAdminInvitation(guard, store, unitOfWork, identity, audit)
    private val list = ListClinics(guard, store, unitOfWork)
    private val get = GetClinic(guard, store, unitOfWork)

    private val clinic = UUID.randomUUID().also { store.insertClinic(it, "Clínica Alfa") }

    private fun pendingAdmin(sub: String? = null): UUID {
        val user = UUID.randomUUID()
        store.users[user] = Triple("admin@dca.test", "Admin DCA", sub)
        return UUID.randomUUID().also { store.insertMembership(it, clinic, user, MembershipStatus.PENDING, null) }
    }

    @Test
    fun `non admins are refused by every operation`() {
        val membership = pendingAdmin()
        listOf<() -> Unit>(
            { update.update("sub-doctor", clinic, "Novo") },
            { status.deactivate("sub-doctor", clinic) },
            { status.activate("sub-doctor", clinic) },
            { resend.resend("sub-doctor", clinic, membership) },
        ).forEach { operation ->
            assertThatThrownBy(operation).isInstanceOf(PermissionDeniedException::class.java)
        }
        assertThat(store.clinics.getValue(clinic)).isEqualTo("Clínica Alfa")
        assertThat(store.clinicStatus.getValue(clinic)).isEqualTo(ClinicStatus.ACTIVE)
        assertThat(audit.events).hasSize(4).allSatisfy { assertThat(it.third.outcome).isEqualTo(AuditOutcome.DENIED) }
    }

    @Test
    fun `update changes the name, audits the field name only and rejects blank or missing`() {
        update.update("sub-admin", clinic, "  Clínica Beta ")

        assertThat(store.clinics.getValue(clinic)).isEqualTo("Clínica Beta")
        val (actor, tenant, event) = audit.events.single()
        assertThat(actor).isEqualTo(adminId)
        assertThat(tenant).isEqualTo(clinic)
        assertThat(event.action).isEqualTo("UPDATE_CLINIC")
        assertThat(event.details).containsExactly("name")

        assertThatThrownBy { update.update("sub-admin", clinic, " ") }.isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("VALIDATION_FAILED") }
        assertThatThrownBy { update.update("sub-admin", clinic, "x".repeat(121)) }.isInstanceOf(ApiException::class.java)
        assertThatThrownBy { update.update("sub-admin", UUID.randomUUID(), "Nome") }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.kind).isEqualTo(ErrorKind.NOT_FOUND) }
    }

    @Test
    fun `deactivate and activate flip the status and keep the data, auditing only real changes`() {
        val membership = pendingAdmin()

        status.deactivate("sub-admin", clinic)
        status.deactivate("sub-admin", clinic)
        assertThat(store.clinicStatus.getValue(clinic)).isEqualTo(ClinicStatus.INACTIVE)
        assertThat(store.clinics).containsKey(clinic)
        assertThat(store.memberships).containsKey(membership)

        status.activate("sub-admin", clinic)
        status.activate("sub-admin", clinic)
        assertThat(store.clinicStatus.getValue(clinic)).isEqualTo(ClinicStatus.ACTIVE)
        assertThat(audit.events.map { it.third.action }).containsExactly("DEACTIVATE_CLINIC", "ACTIVATE_CLINIC")
        assertThatThrownBy { status.deactivate("sub-admin", UUID.randomUUID()) }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("CLINIC_NOT_FOUND") }
    }

    @Test
    fun `resend creates the missing login and activates the pending membership`() {
        val membership = pendingAdmin()

        assertThat(resend.resend("sub-admin", clinic, membership)).isEqualTo(MembershipStatus.ACTIVE)

        assertThat(store.memberships.getValue(membership).third).isEqualTo(MembershipStatus.ACTIVE)
        assertThat(store.users.values.single().third).isNotNull()
        assertThat(identity.users()).containsExactly("admin@dca.test")
        assertThat(store.membershipSubs.getValue(membership)).isEqualTo(store.users.values.single().third)
        assertThat(audit.events.single().third.action).isEqualTo("RESEND_INVITATION")
    }

    @Test
    fun `resend for a user that already has a login asks the provider to resend`() {
        val membership = pendingAdmin(sub = "sub-dca-owner")

        resend.resend("sub-admin", clinic, membership)

        assertThat(identity.resent()).containsExactly("admin@dca.test")
        assertThat(identity.users()).isEmpty()
        assertThat(store.users.values.single().third).isEqualTo("sub-dca-owner")
    }

    @Test
    fun `resend is refused for memberships that are not pending`() {
        val membership = pendingAdmin()
        store.memberships[membership] = store.memberships.getValue(membership).copy(third = MembershipStatus.ACTIVE)
        assertThatThrownBy { resend.resend("sub-admin", clinic, membership) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
                assertThat(it.code).isEqualTo("INVITATION_NOT_PENDING")
            }
        store.memberships[membership] = store.memberships.getValue(membership).copy(third = MembershipStatus.INACTIVE)
        assertThatThrownBy { resend.resend("sub-admin", clinic, membership) }.isInstanceOf(ApiException::class.java)
        assertThat(identity.users()).isEmpty()
        assertThat(identity.resent()).isEmpty()
    }

    @Test
    fun `resend does not find a membership of another clinic`() {
        val other = UUID.randomUUID().also { store.insertClinic(it, "Outra") }
        val membership = pendingAdmin()
        assertThatThrownBy { resend.resend("sub-admin", other, membership) }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("MEMBERSHIP_NOT_FOUND") }
    }

    @Test
    fun `when the provider fails the answer is 502, the membership stays pending and the failure is audited`() {
        val membership = pendingAdmin()
        identity.failNext(IdentityProviderException("indisponível"))

        assertThatThrownBy { resend.resend("sub-admin", clinic, membership) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.kind).isEqualTo(ErrorKind.BAD_GATEWAY)
                assertThat(it.code).isEqualTo("IDENTITY_PROVIDER_UNAVAILABLE")
            }

        assertThat(store.memberships.getValue(membership).third).isEqualTo(MembershipStatus.PENDING)
        assertThat(audit.events.single().third.outcome).isEqualTo(AuditOutcome.FAILURE)
    }

    @Test
    fun `reading clinics is refused to non admins, with the denial audited`() {
        assertThatThrownBy { list.list("sub-doctor") }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { get.get("sub-doctor", clinic) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThat(audit.events).hasSize(2).allSatisfy { assertThat(it.third.outcome).isEqualTo(AuditOutcome.DENIED) }
    }

    @Test
    fun `list returns every clinic with its status, ordered by name`() {
        val beta = UUID.randomUUID().also { store.insertClinic(it, "Beta") }
        store.setClinicStatus(beta, ClinicStatus.INACTIVE)

        assertThat(list.list("sub-admin")).containsExactly(
            ClinicOverview(beta, "Beta", ClinicStatus.INACTIVE),
            ClinicOverview(clinic, "Clínica Alfa", ClinicStatus.ACTIVE),
        )
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `get returns the clinic with its administrators and ignores other roles and other clinics`() {
        val adminMembership = pendingAdmin()
        val adminRole = UUID.randomUUID().also { store.insertRole(it, clinic, PermissionCatalog.CLINIC_ADMIN, emptySet()) }
        store.assignRole(clinic, adminMembership, adminRole)

        val doctorMembership = pendingAdmin()
        val doctorRole = UUID.randomUUID().also { store.insertRole(it, clinic, "DOCTOR", emptySet()) }
        store.assignRole(clinic, doctorMembership, doctorRole)

        val other = UUID.randomUUID().also { store.insertClinic(it, "Outra") }
        val otherUser = UUID.randomUUID().also { store.users[it] = Triple("outro@x.test", "Outro", null) }
        val otherMembership = UUID.randomUUID().also { store.insertMembership(it, other, otherUser, MembershipStatus.ACTIVE, null) }
        store.assignRole(other, otherMembership, UUID.randomUUID().also { store.insertRole(it, other, PermissionCatalog.CLINIC_ADMIN, emptySet()) })

        val detail = get.get("sub-admin", clinic)

        assertThat(detail.clinic).isEqualTo(ClinicOverview(clinic, "Clínica Alfa", ClinicStatus.ACTIVE))
        assertThat(detail.admins).containsExactly(ClinicAdmin(adminMembership, "admin@dca.test", "Admin DCA", MembershipStatus.PENDING))
    }

    @Test
    fun `get answers 404 CLINIC_NOT_FOUND for an unknown clinic`() {
        assertThatThrownBy { get.get("sub-admin", UUID.randomUUID()) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.kind).isEqualTo(ErrorKind.NOT_FOUND)
                assertThat(it.code).isEqualTo("CLINIC_NOT_FOUND")
            }
    }
}
