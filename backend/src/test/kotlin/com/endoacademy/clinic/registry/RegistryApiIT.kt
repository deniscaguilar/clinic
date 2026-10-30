package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.registry.api.PatientContact
import com.endoacademy.clinic.registry.api.RegistryApi
import com.endoacademy.clinic.support.ClinicApiIT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** A `registry.api` que o `notifications` usa, contra o banco real e sob a RLS (T5.6). */
class RegistryApiIT : ClinicApiIT() {

    @Autowired
    lateinit var registry: RegistryApi

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    private fun newType(clinic: Clinic, name: String, messaging: Boolean, required: Boolean = true): UUID = idOf(
        mockMvc.perform(
            call(post("/api/v1/consent-types").contentType(MediaType.APPLICATION_JSON).content(body("name" to name, "text" to "Autorizo.", "requiredOnSignup" to required, "controlsMessaging" to messaging)), clinic),
        ).andExpect(status().isCreated),
    )

    private fun registerPatient(clinic: Clinic, consents: List<UUID>, cpf: String = "529.982.247-25"): UUID = idOf(
        mockMvc.perform(
            call(
                post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(
                    json.writeValueAsString(
                        mapOf(
                            "cpf" to cpf, "fullName" to "Maria da Silva", "birthDate" to "1985-03-12", "whatsapp" to "(11) 98765-4321", "email" to "maria@example.com",
                            "address" to mapOf("zipCode" to "01310-100", "street" to "Avenida Paulista", "number" to "1578", "district" to "Bela Vista", "city" to "São Paulo", "state" to "SP"),
                            "consents" to consents.map { mapOf("typeId" to it) },
                        ),
                    ),
                ),
                clinic,
            ),
        ).andExpect(status().isCreated),
    )

    private fun <T> inClinic(clinic: Clinic, block: () -> T): T = TenantScope.runAs(clinic.id, block)

    @Test
    fun `messaging consent follows the acceptance and the revocation`() {
        val clinic = newClinic()
        val messaging = newType(clinic, "Mensagens", messaging = true)
        val patient = registerPatient(clinic, listOf(messaging))
        assertThat(inClinic(clinic) { registry.hasActiveMessagingConsent(patient) }).isTrue()

        val image = newType(clinic, "Imagem", messaging = false, required = false)
        mockMvc.perform(call(post("/api/v1/patients/$patient/consents").contentType(MediaType.APPLICATION_JSON).content(body("typeId" to image)), clinic)).andExpect(status().isCreated)
        // o aceite do tipo que não controla mensagens não muda nada; revogar o de mensagens encerra
        assertThat(inClinic(clinic) { registry.hasActiveMessagingConsent(patient) }).isTrue()
        val messagingGrant = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForObject("select id from registry.consent_grant where patient_id = ? and type_id = ?", UUID::class.java, patient, messaging) }!!
        }
        mockMvc.perform(call(post("/api/v1/patients/$patient/consents/$messagingGrant/revoke"), clinic)).andExpect(status().isOk)
        assertThat(inClinic(clinic) { registry.hasActiveMessagingConsent(patient) }).isFalse()

        mockMvc.perform(call(post("/api/v1/patients/$patient/consents").contentType(MediaType.APPLICATION_JSON).content(body("typeId" to messaging)), clinic)).andExpect(status().isCreated)
        assertThat(inClinic(clinic) { registry.hasActiveMessagingConsent(patient) }).isTrue()
    }

    @Test
    fun `a deactivated messaging type ends the consent, and a clinic without such a type has none`() {
        val clinic = newClinic()
        val withoutMessaging = newType(clinic, "Imagem", messaging = false)
        val patient = registerPatient(clinic, listOf(withoutMessaging))
        assertThat(inClinic(clinic) { registry.hasActiveMessagingConsent(patient) }).isFalse()

        val other = newClinic()
        val messaging = newType(other, "Mensagens", messaging = true)
        val patientOfOther = registerPatient(other, listOf(messaging))
        assertThat(inClinic(other) { registry.hasActiveMessagingConsent(patientOfOther) }).isTrue()
        mockMvc.perform(
            call(put("/api/v1/consent-types/$messaging").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Mensagens", "requiredOnSignup" to true, "controlsMessaging" to true, "active" to false)), other),
        ).andExpect(status().isOk)
        assertThat(inClinic(other) { registry.hasActiveMessagingConsent(patientOfOther) }).isFalse()
    }

    @Test
    fun `another clinic, or no clinic at all, sees no consent and no contact (RLS fails closed)`() {
        val a = newClinic()
        val b = newClinic()
        val patientOfA = registerPatient(a, listOf(newType(a, "Mensagens", messaging = true)))
        assertThat(inClinic(a) { registry.hasActiveMessagingConsent(patientOfA) }).isTrue()
        assertThat(inClinic(a) { registry.getPatientContact(patientOfA) }).isNotNull()

        assertThat(inClinic(b) { registry.hasActiveMessagingConsent(patientOfA) }).isFalse()
        assertThat(inClinic(b) { registry.getPatientContact(patientOfA) }).isNull()
        assertThat(registry.hasActiveMessagingConsent(patientOfA)).describedAs("sem contexto de clínica").isFalse()
        assertThat(registry.getPatientContact(patientOfA)).describedAs("sem contexto de clínica").isNull()
        assertThat(TenantScope.runAsPlatform { registry.getPatientContact(patientOfA) }).describedAs("o escopo platform não abre paciente").isNull()
    }

    @Test
    fun `the contact has the name and the channels and nothing else`() {
        val clinic = newClinic()
        val patient = registerPatient(clinic, emptyList())
        assertThat(inClinic(clinic) { registry.getPatientContact(patient) }).isEqualTo(PatientContact("Maria da Silva", "11987654321", "maria@example.com"))
        assertThat(inClinic(clinic) { registry.getPatientContact(UUID.randomUUID()) }).isNull()
    }
}
