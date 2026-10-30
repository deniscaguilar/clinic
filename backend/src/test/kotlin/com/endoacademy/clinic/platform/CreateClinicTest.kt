package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.identity.InMemoryIdentityProvider
import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.ClinicCreated
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.api.PermissionDefinition
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.platform.application.CreateClinic
import com.endoacademy.clinic.platform.application.CreateClinicCommand
import com.endoacademy.clinic.platform.application.EventPublisher
import com.endoacademy.clinic.platform.application.IdentityProviderException
import com.endoacademy.clinic.platform.application.MembershipStatus
import com.endoacademy.clinic.platform.application.PermissionCatalog
import com.endoacademy.clinic.platform.application.PlatformActor
import com.endoacademy.clinic.platform.application.PlatformAdmins
import com.endoacademy.clinic.platform.application.PlatformGuard
import com.endoacademy.clinic.support.FakeClinicStore
import com.endoacademy.clinic.platform.application.UnitOfWork
import com.endoacademy.clinic.platform.application.UserIds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class CreateClinicTest {

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<Triple<UUID?, UUID?, AuditEvent>>() // (actor, tenant, event)
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += Triple(user?.userId, user?.clinicId, event)
        }

        override fun recordPlatform(actorUserId: UUID?, tenantId: UUID?, event: AuditEvent) {
            events += Triple(actorUserId, tenantId, event)
        }
    }

    private class Events : EventPublisher {
        val published = mutableListOf<Any>()
        override fun publish(event: Any) {
            published += event
        }
    }

    private val adminId = UUID.randomUUID()
    private val admin = PlatformActor(adminId, "sub-admin")
    private val store = FakeClinicStore()
    private val audit = RecordingAudit()
    private val events = Events()
    private val identity = InMemoryIdentityProvider()
    private val catalog = PermissionCatalog(
        listOf(
            object : PermissionContributor {
                override fun permissions() = listOf(
                    PermissionDefinition("MANAGE_USERS", "usuários"),
                    PermissionDefinition("VIEW_PATIENTS", "ver pacientes"),
                    PermissionDefinition("MANAGE_CLINICS", "clínicas", platformOnly = true),
                )

                override fun defaultGrants() = mapOf("DOCTOR" to setOf("VIEW_PATIENTS"), "STAFF" to setOf("VIEW_PATIENTS"))
            },
        ),
    )
    private val inScope = mutableListOf<UUID?>()
    private val unitOfWork = object : UnitOfWork {
        override fun <T> inPlatformScope(tenantId: UUID?, block: () -> T): T {
            inScope += tenantId
            return block()
        }
    }

    private fun useCase(admins: PlatformAdmins = PlatformAdmins { if (it == "sub-admin") admin else null }) =
        CreateClinic(PlatformGuard(admins, unitOfWork, audit), store, unitOfWork, identity, catalog, audit, events)

    private val command = CreateClinicCommand("Clínica da DCA", "Admin@DCA.test", "Admin DCA")

    @Test
    fun `someone who is not a platform admin is refused, audited as DENIED and nothing is created`() {
        assertThatThrownBy { useCase().create("sub-doctor", command) }
            .isInstanceOfSatisfying(PermissionDeniedException::class.java) { assertThat(it.permission).isEqualTo("MANAGE_CLINICS") }

        assertThat(store.clinics).isEmpty()
        assertThat(store.users).isEmpty()
        assertThat(identity.users()).isEmpty()
        assertThat(events.published).isEmpty()
        assertThat(audit.events.single().third.outcome).isEqualTo(AuditOutcome.DENIED)
    }

    @Test
    fun `creates the clinic with the template roles, links the first admin to CLINIC_ADMIN and activates the invitation`() {
        val result = useCase().create("sub-admin", command)

        assertThat(store.clinics).containsEntry(result.clinicId, "Clínica da DCA")
        val roles = store.roles.values.associate { it.second to it.third }
        assertThat(roles.keys).containsExactlyInAnyOrder("CLINIC_ADMIN", "DOCTOR", "NURSE", "MANAGER", "STAFF")
        assertThat(roles.getValue("CLINIC_ADMIN")).containsExactlyInAnyOrder("MANAGE_USERS", "VIEW_PATIENTS") // sem a permissão exclusiva da plataforma
        assertThat(roles.getValue("DOCTOR")).containsExactly("VIEW_PATIENTS")
        assertThat(roles.getValue("NURSE")).isEmpty()
        assertThat(store.roles.values.map { it.first }.toSet()).containsExactly(result.clinicId)

        val (clinicOfMembership, userId, status) = store.memberships.getValue(result.adminMembershipId)
        assertThat(clinicOfMembership).isEqualTo(result.clinicId)
        assertThat(status).isEqualTo(MembershipStatus.ACTIVE)
        assertThat(result.adminStatus).isEqualTo(MembershipStatus.ACTIVE)
        val adminRoleId = store.roles.entries.single { it.value.second == "CLINIC_ADMIN" }.key
        assertThat(store.assignments).containsExactly(result.adminMembershipId to adminRoleId)

        assertThat(store.users.getValue(userId).first).isEqualTo("admin@dca.test")
        assertThat(userId).isEqualTo(UserIds.forEmail("admin@dca.test"))
        assertThat(store.membershipSubs.getValue(result.adminMembershipId)).isEqualTo(store.users.getValue(userId).third)
        assertThat(store.users.getValue(userId).third).isEqualTo(identity.createUser("admin@dca.test", "x"))
        assertThat(events.published).containsExactly(ClinicCreated(result.clinicId))
    }

    @Test
    fun `audits the creation and the activation with the actor and the clinic, never with values`() {
        val result = useCase().create("sub-admin", command)

        val actions = audit.events.map { it.third.action }
        assertThat(actions).containsExactly("CREATE_CLINIC", "ACTIVATE_MEMBERSHIP")
        assertThat(audit.events).allSatisfy {
            assertThat(it.first).isEqualTo(adminId)
            assertThat(it.second).isEqualTo(result.clinicId)
            assertThat(it.third.outcome).isEqualTo(AuditOutcome.SUCCESS)
        }
        assertThat(audit.events.first().third.details).containsExactlyInAnyOrder("name", "adminEmail", "adminName")
    }

    @Test
    fun `when the identity provider fails the clinic stays and the first admin stays PENDING, audited as FAILURE`() {
        identity.failNext(IdentityProviderException("indisponível"))

        val result = useCase().create("sub-admin", command)

        assertThat(store.clinics).containsKey(result.clinicId)
        assertThat(result.adminStatus).isEqualTo(MembershipStatus.PENDING)
        assertThat(store.memberships.getValue(result.adminMembershipId).third).isEqualTo(MembershipStatus.PENDING)
        assertThat(store.users.values.single().third).isNull()
        assertThat(events.published).containsExactly(ClinicCreated(result.clinicId))
        assertThat(audit.events.map { it.third.action to it.third.outcome })
            .containsExactly("CREATE_CLINIC" to AuditOutcome.SUCCESS, "CREATE_IDENTITY" to AuditOutcome.FAILURE)
    }

    @Test
    fun `an e-mail that already has a login reuses it and activates without a new invitation`() {
        val existing = UUID.randomUUID()
        store.users[existing] = Triple("admin@dca.test", "Admin", "sub-dca-owner")

        val result = useCase().create("sub-admin", command)

        assertThat(store.memberships.getValue(result.adminMembershipId).second).isEqualTo(existing)
        assertThat(result.adminStatus).isEqualTo(MembershipStatus.ACTIVE)
        assertThat(identity.users()).isEmpty()
        assertThat(store.users).hasSize(1)
    }

    @Test
    fun `an e-mail registered elsewhere but without a login gets the invitation`() {
        val existing = UUID.randomUUID()
        store.users[existing] = Triple("admin@dca.test", "Admin", null)

        val result = useCase().create("sub-admin", command)

        assertThat(result.adminStatus).isEqualTo(MembershipStatus.ACTIVE)
        assertThat(store.users.getValue(existing).third).isNotNull()
        assertThat(identity.users()).containsExactly("admin@dca.test")
    }

    @Test
    fun `blank name, e-mail or admin name is rejected before anything is stored`() {
        listOf(
            command.copy(name = "  "),
            command.copy(adminEmail = " "),
            command.copy(adminName = ""),
            command.copy(adminEmail = "sem-arroba"),
            command.copy(name = "x".repeat(121)),
        ).forEach { invalid ->
            assertThatThrownBy { useCase().create("sub-admin", invalid) }
                .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("VALIDATION_FAILED") }
        }
        assertThat(store.clinics).isEmpty()
    }

    @Test
    fun `every database step runs in the platform scope of the new clinic`() {
        val result = useCase().create("sub-admin", command)
        assertThat(inScope.filterNotNull().toSet()).containsExactly(result.clinicId)
    }
}
