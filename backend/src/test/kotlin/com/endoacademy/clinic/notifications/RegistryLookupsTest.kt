package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.notifications.adapter.out.registry.RegistryConsentInfoLookup
import com.endoacademy.clinic.notifications.adapter.out.registry.RegistryMessagingConsentLookup
import com.endoacademy.clinic.notifications.adapter.out.registry.RegistryPatientContactLookup
import com.endoacademy.clinic.notifications.application.ConsentInfo
import com.endoacademy.clinic.notifications.application.ContactDetails
import com.endoacademy.clinic.registry.api.ConsentSummary
import com.endoacademy.clinic.registry.api.PatientContact
import com.endoacademy.clinic.registry.api.RegistryApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Os adaptadores do `notifications` sobre a `registry.api` (T5.8): só traduzem; a regra fica no `registry`. */
class RegistryLookupsTest {

    private val withConsent = UUID.randomUUID()
    private val withContact = UUID.randomUUID()
    private val aGrant = UUID.randomUUID()
    private val calls = mutableListOf<String>()

    private val registry = object : RegistryApi {
        override fun hasActiveMessagingConsent(patientId: UUID): Boolean {
            calls += "consent:$patientId"
            return patientId == withConsent
        }

        override fun getConsentSummary(patientId: UUID, grantId: UUID): ConsentSummary? {
            calls += "summary:$patientId:$grantId"
            return if (patientId == withContact && grantId == aGrant) ConsentSummary("Mensagens", Instant.parse("2026-10-30T12:00:00Z")) else null
        }

        override fun getPatientContact(patientId: UUID): PatientContact? {
            calls += "contact:$patientId"
            return if (patientId == withContact) PatientContact("Maria da Silva", "11987654321", null) else null
        }
    }

    @Test
    fun `the contact lookup maps the registry contact and keeps a missing channel null`() {
        val lookup = RegistryPatientContactLookup(registry)
        assertThat(lookup.find(withContact)).isEqualTo(ContactDetails("Maria da Silva", "11987654321", null))
        assertThat(lookup.find(UUID.randomUUID())).isNull()
        assertThat(calls).hasSize(2)
    }

    @Test
    fun `the contact details do not expose the data through toString`() {
        assertThat(ContactDetails("Maria da Silva", "11987654321", "maria@example.com").toString()).doesNotContain("Maria").doesNotContain("98765").doesNotContain("example.com")
    }

    @Test
    fun `the consent info lookup maps the summary and asks for the very patient and acceptance`() {
        val lookup = RegistryConsentInfoLookup(registry)
        assertThat(lookup.find(withContact, aGrant)).isEqualTo(ConsentInfo("Mensagens", Instant.parse("2026-10-30T12:00:00Z")))
        assertThat(lookup.find(withContact, UUID.randomUUID())).isNull()
        assertThat(lookup.find(UUID.randomUUID(), aGrant)).isNull()
        assertThat(calls.first()).isEqualTo("summary:$withContact:$aGrant")
    }

    @Test
    fun `the consent lookup asks the registry for the very patient`() {
        val lookup = RegistryMessagingConsentLookup(registry)
        assertThat(lookup.hasActive(withConsent)).isTrue()
        assertThat(lookup.hasActive(UUID.randomUUID())).isFalse()
        assertThat(calls.first()).isEqualTo("consent:$withConsent")
    }
}
