package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.application.BrandingPresenter
import com.endoacademy.clinic.platform.application.BrandingStore
import com.endoacademy.clinic.platform.application.ClinicSummary
import com.endoacademy.clinic.platform.application.GetBranding
import com.endoacademy.clinic.platform.application.GetMyProfile
import com.endoacademy.clinic.platform.application.ListMyClinics
import com.endoacademy.clinic.platform.application.ResolveSession
import com.endoacademy.clinic.platform.application.UserDirectory
import com.endoacademy.clinic.platform.application.UserProfile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class SessionUseCasesTest {

    private val userId = UUID.randomUUID()
    private val clinicA = ClinicSummary(UUID.randomUUID(), "Clínica A")
    private val clinicB = ClinicSummary(UUID.randomUUID(), "Clínica B")
    private val profile = UserProfile(userId, "ana@clinica.test", "Ana")

    private class FakeDirectory(
        val profiles: Map<String, UserProfile>,
        val clinicsBySub: Map<String, List<ClinicSummary>>,
        val clinics: Map<UUID, ClinicSummary>,
        val roles: Map<Pair<UUID, UUID>, List<String>>,
    ) : UserDirectory {
        override fun findProfile(sub: String) = profiles[sub]
        override fun listActiveClinics(sub: String) = clinicsBySub[sub].orEmpty()
        override fun findClinic(clinicId: UUID) = clinics[clinicId]
        override fun findRoleNames(clinicId: UUID, userId: UUID) = roles[clinicId to userId].orEmpty()
    }

    private class RecordingAuditTrail : AuditTrail {
        val events = mutableListOf<Pair<CurrentUser?, AuditEvent>>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += user to event
        }
    }

    private val directory = FakeDirectory(
        profiles = mapOf("sub-ana" to profile),
        clinicsBySub = mapOf("sub-ana" to listOf(clinicA, clinicB)),
        clinics = mapOf(clinicA.id to clinicA, clinicB.id to clinicB),
        roles = mapOf((clinicA.id to userId) to listOf("DOCTOR"), (clinicB.id to userId) to listOf("MANAGER", "STAFF")),
    )
    private val audit = RecordingAuditTrail()
    private val getBranding = GetBranding(
        object : BrandingStore {
            override fun find() = null
            override fun save(clinicId: UUID, branding: com.endoacademy.clinic.platform.domain.Branding) = Unit
        },
        directory,
        BrandingPresenter(com.endoacademy.clinic.platform.adapter.out.storage.InMemoryFileStorage()),
        object : com.endoacademy.clinic.platform.application.Transactions {
            override fun <T> inTransaction(block: () -> T): T = block()
        },
    )

    @Test
    fun `ListMyClinics returns the clinics of the user and nothing for an unknown sub`() {
        val useCase = ListMyClinics(directory)
        assertThat(useCase.list("sub-ana")).containsExactly(clinicA, clinicB)
        assertThat(useCase.list("sub-unknown")).isEmpty()
    }

    @Test
    fun `GetMyProfile returns the profile or fails with USER_NOT_REGISTERED`() {
        val useCase = GetMyProfile(directory)
        assertThat(useCase.get("sub-ana")).isEqualTo(profile)
        assertThatThrownBy { useCase.get("sub-unknown") }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(ErrorKind.FORBIDDEN)
            assertThat(it.code).isEqualTo("USER_NOT_REGISTERED")
        }
    }

    @Test
    fun `ResolveSession returns only the roles and permissions of the chosen clinic and audits LOGIN`() {
        val user = CurrentUser(userId, "sub-ana", clinicB.id, setOf("VIEW_PATIENTS", "MANAGE_USERS"))

        val session = ResolveSession(directory, audit, getBranding).resolve(user)

        assertThat(session.user).isEqualTo(profile)
        assertThat(session.clinic).isEqualTo(clinicB)
        assertThat(session.roles).containsExactly("MANAGER", "STAFF")
        assertThat(session.permissions).containsExactly("MANAGE_USERS", "VIEW_PATIENTS")
        assertThat(session.branding.displayName).isEqualTo("Clínica B") // sem marca gravada, a padrão com o nome da clínica
        val (who, event) = audit.events.single()
        assertThat(who).isEqualTo(user)
        assertThat(event.action).isEqualTo("LOGIN")
        assertThat(event.entityType).isEqualTo("SESSION")
        assertThat(event.outcome).isEqualTo(AuditOutcome.SUCCESS)
        assertThat(event.details).isEmpty()
    }

    @Test
    fun `ResolveSession for an unregistered user or a missing clinic fails and audits nothing`() {
        val useCase = ResolveSession(directory, audit, getBranding)

        assertThatThrownBy { useCase.resolve(CurrentUser(userId, "sub-unknown", clinicA.id, emptySet())) }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("USER_NOT_REGISTERED") }
        assertThatThrownBy { useCase.resolve(CurrentUser(userId, "sub-ana", UUID.randomUUID(), emptySet())) }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("CLINIC_ACCESS_DENIED") }
        assertThat(audit.events).isEmpty()
    }
}
