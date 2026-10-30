package com.endoacademy.clinic.registry

import com.endoacademy.clinic.registry.api.ConsentSummary
import com.endoacademy.clinic.registry.api.PatientContact
import com.endoacademy.clinic.registry.application.ConsentGrantStore
import com.endoacademy.clinic.registry.application.ConsentTypeStore
import com.endoacademy.clinic.registry.application.PatientStore
import com.endoacademy.clinic.registry.application.RegistryFacade
import com.endoacademy.clinic.registry.application.Transactions
import com.endoacademy.clinic.registry.domain.Address
import com.endoacademy.clinic.registry.domain.ConsentGrant
import com.endoacademy.clinic.registry.domain.ConsentType
import com.endoacademy.clinic.registry.domain.ConsentVersion
import com.endoacademy.clinic.registry.domain.Cpf
import com.endoacademy.clinic.registry.domain.Email
import com.endoacademy.clinic.registry.domain.Patient
import com.endoacademy.clinic.registry.domain.Phone
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** O que o `notifications` pergunta ao `registry` (T5.6): consentimento de mensagens vigente e o contato do paciente. */
class RegistryApiTest {

    private class FakePatientStore : PatientStore {
        val rows = linkedMapOf<UUID, Patient>()
        override fun find(id: UUID) = rows[id]
        override fun findByCpf(cpf: Cpf) = error("não usado")
        override fun cpfTaken(cpf: Cpf, exceptId: UUID?) = error("não usado")
        override fun insert(clinicId: UUID, patient: Patient) = error("não usado")
        override fun update(patient: Patient) = error("não usado")
        override fun list(nameContains: String?, offset: Int, limit: Int) = error("não usado")
        override fun count(nameContains: String?) = error("não usado")
    }

    private class FakeTypeStore : ConsentTypeStore {
        val rows = linkedMapOf<UUID, ConsentType>()
        override fun list(active: Boolean?) = rows.values.filter { active == null || it.active == active }
        override fun find(id: UUID) = rows[id]
        override fun insert(clinicId: UUID, type: ConsentType) = error("não usado")
        override fun update(type: ConsentType) = error("não usado")
        override fun addVersion(typeId: UUID, version: ConsentVersion) = error("não usado")
        override fun nameTaken(name: String, exceptId: UUID?) = error("não usado")
    }

    private class FakeGrantStore : ConsentGrantStore {
        val rows = linkedMapOf<UUID, ConsentGrant>()
        override fun find(patientId: UUID, id: UUID) = rows[id]?.takeIf { it.patientId == patientId }
        override fun listByPatient(patientId: UUID) = rows.values.filter { it.patientId == patientId }
        override fun hasActive(patientId: UUID, typeId: UUID) = rows.values.any { it.patientId == patientId && it.typeId == typeId && it.active }
        override fun insert(clinicId: UUID, grant: ConsentGrant) {
            rows[grant.id] = grant
        }

        override fun revoke(grant: ConsentGrant) {
            rows[grant.id] = grant
        }
    }

    private val patients = FakePatientStore()
    private val types = FakeTypeStore()
    private val grants = FakeGrantStore()
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private val api = RegistryFacade(patients, types, grants, transactions)

    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val user = UUID.randomUUID()
    private val address = Address.of("01310-100", "Avenida Paulista", "1578", null, "Bela Vista", "São Paulo", "SP")
    private fun patient(whatsapp: String? = "(11) 98765-4321", email: String? = "maria@example.com") = Patient.register(
        UUID.randomUUID(), "Maria da Silva", Cpf.parse("529.982.247-25"), LocalDate.of(1985, 3, 12), whatsapp?.let { Phone.parse(it) }, email?.let { Email.parse(it) }, null,
        address, Clock.fixed(now, ZoneOffset.UTC),
    ).also { patients.rows[it.id] = it }

    private fun type(name: String, messaging: Boolean, active: Boolean = true) =
        ConsentType.create(UUID.randomUUID(), name, "Autorizo.", true, messaging, now).update(name, true, messaging, active).also { types.rows[it.id] = it }

    private fun accept(patient: Patient, type: ConsentType) = ConsentGrant.accept(UUID.randomUUID(), patient.id, type.copy(active = true), user, now).also { grants.rows[it.id] = it }

    private fun ConsentType.copy(active: Boolean) = update(name, requiredOnSignup, controlsMessaging, active)

    // --- consentimento de mensagens ---

    @Test
    fun `messaging consent is active only with a live acceptance of the active type that controls messaging`() {
        val messaging = type("Mensagens", messaging = true)
        val image = type("Imagem", messaging = false)
        val p = patient()
        assertThat(api.hasActiveMessagingConsent(p.id)).describedAs("sem aceite").isFalse()
        accept(p, image)
        assertThat(api.hasActiveMessagingConsent(p.id)).describedAs("aceite de tipo que não controla mensagens").isFalse()
        val grant = accept(p, messaging)
        assertThat(api.hasActiveMessagingConsent(p.id)).isTrue()
        grants.revoke(grant.revoke(user, now.plusSeconds(60)))
        assertThat(api.hasActiveMessagingConsent(p.id)).describedAs("revogado").isFalse()
    }

    @Test
    fun `a new acceptance after a revocation makes the consent active again`() {
        val messaging = type("Mensagens", messaging = true)
        val p = patient()
        val first = accept(p, messaging)
        grants.revoke(first.revoke(user, now.plusSeconds(60)))
        assertThat(api.hasActiveMessagingConsent(p.id)).isFalse()
        accept(p, messaging)
        assertThat(api.hasActiveMessagingConsent(p.id)).isTrue()
    }

    @Test
    fun `when the controlling type is inactive or the clinic has none, there is no messaging consent`() {
        val p = patient()
        assertThat(api.hasActiveMessagingConsent(p.id)).describedAs("clínica sem tipo de mensagens").isFalse()
        val messaging = type("Mensagens", messaging = true)
        accept(p, messaging)
        assertThat(api.hasActiveMessagingConsent(p.id)).isTrue()
        types.rows[messaging.id] = messaging.update("Mensagens", true, true, active = false)
        assertThat(api.hasActiveMessagingConsent(p.id)).describedAs("tipo desativado").isFalse()
    }

    @Test
    fun `the consent belongs to the patient who gave it`() {
        val messaging = type("Mensagens", messaging = true)
        val maria = patient()
        val joao = patient()
        accept(maria, messaging)
        assertThat(api.hasActiveMessagingConsent(maria.id)).isTrue()
        assertThat(api.hasActiveMessagingConsent(joao.id)).isFalse()
        assertThat(api.hasActiveMessagingConsent(UUID.randomUUID())).isFalse()
    }

    @Test
    fun `an acceptance of an old version still counts, only a revocation ends it`() {
        val messaging = type("Mensagens", messaging = true)
        val p = patient()
        accept(p, messaging)
        types.rows[messaging.id] = types.rows.getValue(messaging.id).revise("Texto novo.", now.plusSeconds(3600))
        assertThat(api.hasActiveMessagingConsent(p.id)).isTrue()
    }

    // --- resumo do aceite ---

    @Test
    fun `the consent summary has the type name and the time of the acceptance, nothing else`() {
        val messaging = type("Mensagens e cadastro", messaging = true)
        val p = patient()
        val grant = accept(p, messaging)
        val summary = api.getConsentSummary(p.id, grant.id)
        assertThat(summary).isEqualTo(ConsentSummary("Mensagens e cadastro", now))
        assertThat(ConsentSummary::class.java.declaredFields.map { it.name }).containsExactlyInAnyOrder("typeName", "acceptedAt")
    }

    @Test
    fun `the summary belongs to the patient of the acceptance and an unknown one has none`() {
        val messaging = type("Mensagens", messaging = true)
        val maria = patient()
        val joao = patient()
        val grant = accept(maria, messaging)
        assertThat(api.getConsentSummary(joao.id, grant.id)).describedAs("aceite de outro paciente").isNull()
        assertThat(api.getConsentSummary(maria.id, UUID.randomUUID())).isNull()
        assertThat(api.getConsentSummary(UUID.randomUUID(), grant.id)).isNull()
    }

    @Test
    fun `the summary keeps the type name even after the acceptance was revoked or the type deactivated`() {
        val messaging = type("Mensagens", messaging = true)
        val p = patient()
        val grant = accept(p, messaging)
        grants.revoke(grant.revoke(user, now.plusSeconds(60)))
        types.rows[messaging.id] = messaging.update("Mensagens", true, true, active = false)
        assertThat(api.getConsentSummary(p.id, grant.id)).isEqualTo(ConsentSummary("Mensagens", now))
    }

    // --- contato ---

    @Test
    fun `the contact has only the name, the WhatsApp and the e-mail`() {
        val p = patient()
        val contact = api.getPatientContact(p.id)
        assertThat(contact).isEqualTo(PatientContact("Maria da Silva", "11987654321", "maria@example.com"))
        assertThat(PatientContact::class.java.declaredFields.map { it.name }).containsExactlyInAnyOrder("name", "whatsapp", "email")
        assertThat(contact.toString()).doesNotContain("Maria").doesNotContain("98765").doesNotContain("example.com")
    }

    @Test
    fun `a missing channel is null and an unknown patient has no contact`() {
        assertThat(api.getPatientContact(patient(whatsapp = null).id)?.whatsapp).isNull()
        assertThat(api.getPatientContact(patient(email = null).id)?.email).isNull()
        assertThat(api.getPatientContact(UUID.randomUUID())).isNull()
    }
}
