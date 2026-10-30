package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.registry.api.ConsentAccepted
import com.endoacademy.clinic.support.ClinicApiIT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Cadastro de paciente com o aceite dos consentimentos obrigatórios, de ponta a ponta (T5.5; RF5a, CA13, CA15). */
@Import(RegisterPatientConsentIT.FailureSwitch::class)
class RegisterPatientConsentIT : ClinicApiIT() {

    /** Quando armado, derruba a transação no instante do commit, depois de tudo ter sido gravado. */
    class BreakBeforeCommit {
        val armed = AtomicBoolean(false)

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        fun on(@Suppress("UNUSED_PARAMETER") event: ConsentAccepted) {
            check(!armed.get()) { "falha simulada antes do commit" }
        }
    }

    @TestConfiguration
    class FailureSwitch {
        @Bean
        fun breakBeforeCommit() = BreakBeforeCommit()
    }

    @Autowired
    lateinit var switch: BreakBeforeCommit

    @AfterEach
    fun disarm() = switch.armed.set(false)

    private val cpfA = "529.982.247-25"
    private val cpfB = "111.444.777-35"

    private fun newType(clinic: Clinic, name: String, required: Boolean, text: String = "Autorizo."): UUID = UUID.fromString(
        json.readTree(
            mockMvc.perform(
                call(post("/api/v1/consent-types").contentType(MediaType.APPLICATION_JSON).content(body("name" to name, "text" to text, "requiredOnSignup" to required, "controlsMessaging" to false)), clinic),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get("id").asString(),
    )

    private fun register(clinic: Clinic, cpf: String = cpfA, consents: List<UUID>? = emptyList(), sub: String = clinic.adminSub): ResultActions =
        mockMvc.perform(
            call(
                post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(
                    json.writeValueAsString(
                        mapOf(
                            "cpf" to cpf, "fullName" to "Maria da Silva", "birthDate" to "1985-03-12", "whatsapp" to "(11) 98765-4321", "email" to "maria@example.com",
                            "address" to mapOf("zipCode" to "01310-100", "street" to "Avenida Paulista", "number" to "1578", "district" to "Bela Vista", "city" to "São Paulo", "state" to "SP"),
                            "consents" to consents?.map { mapOf("typeId" to it) },
                        ),
                    ),
                ),
                clinic,
                sub,
            ),
        )

    private fun count(clinic: Clinic, table: String): Int =
        TenantScope.runAs(clinic.id) { tx.execute { jdbc.queryForObject("select count(*) from $table", Int::class.java) }!! }

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    @Test
    fun `without a required consent nothing is saved, with it the patient and the acceptance are saved together (RF5a, CA13)`() {
        val clinic = newClinic()
        val required = newType(clinic, "Cadastro e mensagens", required = true)
        val optional = newType(clinic, "Uso de imagem", required = false)

        register(clinic, consents = emptyList()).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
        register(clinic, consents = null).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
        register(clinic, consents = listOf(optional)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
        assertThat(count(clinic, "registry.patient")).isZero()
        assertThat(count(clinic, "registry.consent_grant")).isZero()

        val patient = idOf(
            register(clinic, consents = listOf(required, optional)).andExpect(status().isCreated)
                .andExpect(jsonPath("$.cpf").value("52998224725"))
                .andExpect(jsonPath("$.consents.length()").value(2))
                .andExpect(jsonPath("$.consents[?(@.typeId == '$required')].versionNumber").value(1)),
        )
        assertThat(count(clinic, "registry.patient")).isEqualTo(1)
        assertThat(count(clinic, "registry.consent_grant")).isEqualTo(2)
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic)).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].active").value(true))
    }

    @Test
    fun `with two required consents, accepting only one is refused`() {
        val clinic = newClinic()
        val first = newType(clinic, "Cadastro e mensagens", required = true)
        val second = newType(clinic, "Tratamento de dados", required = true)
        register(clinic, consents = listOf(first)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
        register(clinic, consents = listOf(second)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
        assertThat(count(clinic, "registry.patient")).isZero()
        register(clinic, consents = listOf(first, second)).andExpect(status().isCreated).andExpect(jsonPath("$.consents.length()").value(2))
    }

    @Test
    fun `an optional consent may be skipped and a clinic with no required consent registers without any`() {
        val clinic = newClinic()
        register(clinic, consents = emptyList()).andExpect(status().isCreated).andExpect(jsonPath("$.consents.length()").value(0))
        newType(clinic, "Uso de imagem", required = false)
        register(clinic, cpfB, consents = emptyList()).andExpect(status().isCreated).andExpect(jsonPath("$.consents.length()").value(0))
        val required = newType(clinic, "Obrigatório", required = true)
        register(clinic, "000.000.006-04", consents = listOf(required)).andExpect(status().isCreated)
    }

    @Test
    fun `unknown and inactive types, and an invalid patient, write nothing`() {
        val clinic = newClinic()
        val required = newType(clinic, "Obrigatório", required = true)
        val inactive = newType(clinic, "Antigo", required = false)
        mockMvc.perform(
            call(put("/api/v1/consent-types/$inactive").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Antigo", "requiredOnSignup" to false, "controlsMessaging" to false, "active" to false)), clinic),
        ).andExpect(status().isOk)

        register(clinic, consents = listOf(required, UUID.randomUUID())).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CONSENT_TYPE_NOT_FOUND"))
        register(clinic, consents = listOf(required, inactive)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_TYPE_INACTIVE"))
        register(clinic, cpf = "529.982.247-24", consents = listOf(required)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CPF_INVALID"))
        mockMvc.perform(call(post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content("""{"cpf":"$cpfA","consents":[{}]}"""), clinic)).andExpect(status().isBadRequest)
        assertThat(count(clinic, "registry.patient")).isZero()
        assertThat(count(clinic, "registry.consent_grant")).isZero()
    }

    @Test
    fun `a duplicate CPF is refused and writes no acceptance`() {
        val clinic = newClinic()
        val required = newType(clinic, "Obrigatório", required = true)
        register(clinic, consents = listOf(required)).andExpect(status().isCreated)
        register(clinic, consents = listOf(required)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CPF_ALREADY_REGISTERED"))
        assertThat(count(clinic, "registry.patient")).isEqualTo(1)
        assertThat(count(clinic, "registry.consent_grant")).isEqualTo(1)
    }

    @Test
    fun `a failure at the very end rolls back the patient and every acceptance together`() {
        val clinic = newClinic()
        val required = newType(clinic, "Obrigatório", required = true)
        val optional = newType(clinic, "Uso de imagem", required = false)

        switch.armed.set(true)
        register(clinic, consents = listOf(required, optional)).andExpect(status().isInternalServerError)
        assertThat(count(clinic, "registry.patient")).describedAs("paciente").isZero()
        assertThat(count(clinic, "registry.consent_grant")).describedAs("aceites").isZero()
        assertThat(auditCount(clinic, "REGISTER_PATIENT")).describedAs("auditoria da gravação desfeita").isZero()

        switch.armed.set(false)
        register(clinic, consents = listOf(required, optional)).andExpect(status().isCreated)
        assertThat(count(clinic, "registry.patient")).isEqualTo(1)
        assertThat(count(clinic, "registry.consent_grant")).isEqualTo(2)
    }

    @Test
    fun `registering with an acceptance needs RECORD_CONSENT as well as MANAGE_PATIENTS`() {
        val clinic = newClinic()
        val required = newType(clinic, "Obrigatório", required = true)
        val managerOnly = memberWithPermissions(clinic, "MANAGE_PATIENTS")
        val staff = memberWithPermissions(clinic, "MANAGE_PATIENTS", "RECORD_CONSENT")
        val viewer = memberWithPermissions(clinic, "VIEW_PATIENTS", "RECORD_CONSENT")

        register(clinic, consents = listOf(required), sub = managerOnly).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        register(clinic, consents = emptyList(), sub = managerOnly).andExpect(status().isForbidden)
        register(clinic, consents = listOf(required), sub = viewer).andExpect(status().isForbidden)
        assertThat(count(clinic, "registry.patient")).isZero()
        register(clinic, consents = listOf(required), sub = staff).andExpect(status().isCreated)

        // sem tipo obrigatório e sem aceite, MANAGE_PATIENTS basta
        val relaxed = newClinic()
        val relaxedManager = memberWithPermissions(relaxed, "MANAGE_PATIENTS")
        register(relaxed, consents = emptyList(), sub = relaxedManager).andExpect(status().isCreated)
    }

    @Test
    fun `the acceptance saved at registration stays on its version when the text is revised later (CA15)`() {
        val clinic = newClinic()
        val required = newType(clinic, "Obrigatório", required = true, text = "Texto original")
        val patient = idOf(register(clinic, consents = listOf(required)).andExpect(status().isCreated))
        mockMvc.perform(
            call(
                put("/api/v1/consent-types/$required").contentType(MediaType.APPLICATION_JSON)
                    .content(body("name" to "Obrigatório", "text" to "Texto novo", "requiredOnSignup" to true, "controlsMessaging" to false, "active" to true)),
                clinic,
            ),
        ).andExpect(status().isOk).andExpect(jsonPath("$.currentVersion.number").value(2))
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic)).andExpect(jsonPath("$[0].versionNumber").value(1))
        register(clinic, cpfB, consents = listOf(required)).andExpect(status().isCreated).andExpect(jsonPath("$.consents[0].versionNumber").value(2))
    }

    @Test
    fun `the registration is audited with the acceptances and no personal data`() {
        val clinic = newClinic()
        val required = newType(clinic, "Obrigatório", required = true)
        val optional = newType(clinic, "Uso de imagem", required = false)
        register(clinic, consents = listOf(required, optional)).andExpect(status().isCreated)
        assertThat(auditCount(clinic, "REGISTER_PATIENT")).isEqualTo(1)
        assertThat(auditCount(clinic, "ACCEPT_CONSENT")).isEqualTo(2)
        val stored = TenantScope.runAs(clinic.id) {
            tx.execute {
                jdbc.queryForList("select action || ' ' || coalesce(entity_id, '') || ' ' || details::text from platform.audit_event where action in ('REGISTER_PATIENT', 'ACCEPT_CONSENT')", String::class.java)
            }!!.filterNotNull()
        }
        stored.forEach { assertThat(it).doesNotContain("Maria").doesNotContain("52998224725").doesNotContain("example.com") }
    }
}
