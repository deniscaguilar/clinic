package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.registry.application.ConsentGrantStore
import com.endoacademy.clinic.registry.domain.ConsentGrant
import com.endoacademy.clinic.registry.domain.ConsentType
import com.endoacademy.clinic.support.ClinicApiIT
import com.endoacademy.clinic.support.TenantFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Aceite e revogação de consentimento de ponta a ponta (T5.4; CA15, CA16 na parte do registro). */
class PatientConsentIT : ClinicApiIT() {

    @Autowired
    lateinit var grants: ConsentGrantStore

    private fun newPatient(clinic: Clinic): UUID = TenantScope.runAs(clinic.id) { tx.execute { TenantFixtures.newPatient(jdbc, clinic.id) }!! }

    private fun newType(clinic: Clinic, name: String = "Mensagens ${UUID.randomUUID()}", text: String = "Autorizo.", messaging: Boolean = false): UUID = UUID.fromString(
        json.readTree(
            mockMvc.perform(
                call(post("/api/v1/consent-types").contentType(MediaType.APPLICATION_JSON).content(body("name" to name, "text" to text, "requiredOnSignup" to true, "controlsMessaging" to messaging)), clinic),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get("id").asString(),
    )

    private fun accept(clinic: Clinic, patient: UUID, type: UUID, sub: String = clinic.adminSub): ResultActions =
        mockMvc.perform(call(post("/api/v1/patients/$patient/consents").contentType(MediaType.APPLICATION_JSON).content(body("typeId" to type)), clinic, sub))

    private fun revoke(clinic: Clinic, patient: UUID, grant: UUID, sub: String = clinic.adminSub): ResultActions =
        mockMvc.perform(call(post("/api/v1/patients/$patient/consents/$grant/revoke"), clinic, sub))

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    @Test
    fun `an acceptance records the type, the version in force, the time, the channel and the user, then it is revoked`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val type = newType(clinic, name = "Cadastro e mensagens")
        val grant = idOf(
            accept(clinic, patient, type).andExpect(status().isCreated)
                .andExpect(jsonPath("$.typeId").value(type.toString()))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.channel").value("IN_CLINIC"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.acceptedAt").exists())
                .andExpect(jsonPath("$.acceptedBy").exists())
                .andExpect(jsonPath("$.revokedAt").doesNotExist()),
        )
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic)).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].typeName").value("Cadastro e mensagens")).andExpect(jsonPath("$[0].active").value(true))

        revoke(clinic, patient, grant).andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.revokedAt").exists()).andExpect(jsonPath("$.revokedBy").exists())
            .andExpect(jsonPath("$.acceptedAt").exists())
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].active").value(false))
    }

    @Test
    fun `after a revocation the patient can accept again, as a new row, and old acceptances keep their version (CA15)`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val type = newType(clinic, text = "Texto original")
        val first = idOf(accept(clinic, patient, type).andExpect(status().isCreated))
        revoke(clinic, patient, first).andExpect(status().isOk)
        mockMvc.perform(
            call(
                put("/api/v1/consent-types/$type").contentType(MediaType.APPLICATION_JSON)
                    .content(body("name" to "Mensagens revisado", "text" to "Texto novo", "requiredOnSignup" to true, "controlsMessaging" to false, "active" to true)),
                clinic,
            ),
        ).andExpect(status().isOk)

        val second = idOf(accept(clinic, patient, type).andExpect(status().isCreated).andExpect(jsonPath("$.versionNumber").value(2)))
        assertThat(second).isNotEqualTo(first)
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.id == '$first')].versionNumber").value(1))
            .andExpect(jsonPath("$[?(@.id == '$second')].versionNumber").value(2))
    }

    @Test
    fun `an acceptance not revoked blocks a second one, an acceptance cannot be revoked twice`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val type = newType(clinic)
        val grant = idOf(accept(clinic, patient, type).andExpect(status().isCreated))
        accept(clinic, patient, type).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONSENT_ALREADY_ACCEPTED"))
        revoke(clinic, patient, grant).andExpect(status().isOk)
        revoke(clinic, patient, grant).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONSENT_ALREADY_REVOKED"))
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic)).andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `unknown or inactive things are refused`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val type = newType(clinic)
        accept(clinic, UUID.randomUUID(), type).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"))
        accept(clinic, patient, UUID.randomUUID()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CONSENT_TYPE_NOT_FOUND"))
        revoke(clinic, patient, UUID.randomUUID()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CONSENT_NOT_FOUND"))
        mockMvc.perform(call(get("/api/v1/patients/${UUID.randomUUID()}/consents"), clinic)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"))
        mockMvc.perform(call(post("/api/v1/patients/$patient/consents").contentType(MediaType.APPLICATION_JSON).content("{}"), clinic)).andExpect(status().isBadRequest)

        mockMvc.perform(
            call(put("/api/v1/consent-types/$type").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Inativo", "requiredOnSignup" to true, "controlsMessaging" to false, "active" to false)), clinic),
        ).andExpect(status().isOk)
        accept(clinic, patient, type).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_TYPE_INACTIVE"))
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic)).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `an acceptance is revoked only through its own patient, and the list shows the newest first`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val other = newPatient(clinic)
        val first = idOf(accept(clinic, patient, newType(clinic, name = "Primeiro ${UUID.randomUUID()}")).andExpect(status().isCreated))
        Thread.sleep(20)
        val second = idOf(accept(clinic, patient, newType(clinic, name = "Segundo ${UUID.randomUUID()}")).andExpect(status().isCreated))

        revoke(clinic, other, first).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CONSENT_NOT_FOUND"))
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic))
            .andExpect(jsonPath("$[0].id").value(second.toString())).andExpect(jsonPath("$[1].id").value(first.toString()))
            .andExpect(jsonPath("$[1].active").value(true))
        mockMvc.perform(call(get("/api/v1/patients/$other/consents"), clinic)).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `a different user can revoke, and the revocation records that user`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val grant = idOf(accept(clinic, patient, newType(clinic)).andExpect(status().isCreated))
        val otherRecorder = memberWithPermissions(clinic, "RECORD_CONSENT")
        val acceptedBy = json.readTree(mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic)).andReturn().response.contentAsString)[0].get("acceptedBy").asString()
        val revokedBy = json.readTree(revoke(clinic, patient, grant, sub = otherRecorder).andExpect(status().isOk).andReturn().response.contentAsString).get("revokedBy").asString()
        assertThat(revokedBy).isNotEqualTo(acceptedBy)
    }

    @Test
    fun `another clinic cannot accept, revoke or list the consents of this patient`() {
        val a = newClinic()
        val b = newClinic()
        val patientOfA = newPatient(a)
        val typeOfA = newType(a)
        val typeOfB = newType(b)
        val grant = idOf(accept(a, patientOfA, typeOfA).andExpect(status().isCreated))

        accept(b, patientOfA, typeOfB).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"))
        revoke(b, patientOfA, grant).andExpect(status().isNotFound)
        mockMvc.perform(call(get("/api/v1/patients/$patientOfA/consents"), b)).andExpect(status().isNotFound)
        accept(a, patientOfA, typeOfB).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CONSENT_TYPE_NOT_FOUND"))
        mockMvc.perform(call(get("/api/v1/patients/$patientOfA/consents"), a)).andExpect(jsonPath("$[0].active").value(true))
    }

    @Test
    fun `recording needs RECORD_CONSENT, reading also opens to VIEW_PATIENTS, and denials are audited`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val type = newType(clinic)
        val grant = idOf(accept(clinic, patient, type).andExpect(status().isCreated))
        val viewer = memberWithPermissions(clinic, "VIEW_PATIENTS")
        val recorder = memberWithPermissions(clinic, "RECORD_CONSENT")
        val outsider = memberWithPermissions(clinic, "MANAGE_PROCEDURES")

        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic, viewer)).andExpect(status().isOk)
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic, recorder)).andExpect(status().isOk)
        accept(clinic, patient, type, sub = viewer).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        revoke(clinic, patient, grant, sub = viewer).andExpect(status().isForbidden)
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic, outsider)).andExpect(status().isForbidden)
        accept(clinic, patient, type, sub = outsider).andExpect(status().isForbidden)
        revoke(clinic, patient, grant, sub = outsider).andExpect(status().isForbidden)
        val denied = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForObject("select count(*) from platform.audit_event where outcome = 'DENIED' and entity_id in ('RECORD_CONSENT', 'VIEW_PATIENTS')", Int::class.java) }!!
        }
        assertThat(denied).isEqualTo(5)
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic)).andExpect(jsonPath("$[0].active").value(true))

        accept(clinic, patient, newType(clinic), sub = recorder).andExpect(status().isCreated)
        mockMvc.perform(get("/api/v1/patients/$patient/consents")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `accepting, revoking and listing are audited with no personal data`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val grant = idOf(accept(clinic, patient, newType(clinic, name = "Tipo sigiloso", text = "Texto sigiloso")).andExpect(status().isCreated))
        mockMvc.perform(call(get("/api/v1/patients/$patient/consents"), clinic)).andExpect(status().isOk)
        revoke(clinic, patient, grant).andExpect(status().isOk)

        assertThat(auditCount(clinic, "ACCEPT_CONSENT")).isEqualTo(1)
        assertThat(auditCount(clinic, "REVOKE_CONSENT")).isEqualTo(1)
        assertThat(auditCount(clinic, "LIST_PATIENT_CONSENTS")).isEqualTo(1)
        val stored = TenantScope.runAs(clinic.id) {
            tx.execute {
                jdbc.queryForList("select action || ' ' || coalesce(entity_id, '') || ' ' || details::text from platform.audit_event where action in ('ACCEPT_CONSENT', 'REVOKE_CONSENT', 'LIST_PATIENT_CONSENTS')", String::class.java)
            }!!.filterNotNull()
        }
        assertThat(stored).hasSize(3)
        stored.forEach { assertThat(it).doesNotContain("sigiloso").doesNotContain("Paciente de teste") }
        assertThat(stored.first { it.startsWith("LIST_PATIENT_CONSENTS") }).contains("count.1")
    }
    @Test
    fun `the database decides races on a second active acceptance and on a second revocation, both as conflicts`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val type = ConsentType.create(UUID.randomUUID(), "Corrida ${UUID.randomUUID()}", "Texto", true, false, java.time.Instant.parse("2026-10-30T10:00:00Z"))
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult {
                jdbc.update("insert into registry.consent_type (id, tenant_id, name, required_on_signup, controls_messaging) values (?, ?, ?, true, false)", type.id, clinic.id, type.name)
                jdbc.update("insert into registry.consent_type_version (tenant_id, type_id, number, text) values (?, ?, 1, 'Texto')", clinic.id, type.id)
            }
        }
        val user = UUID.randomUUID()
        val now = java.time.Instant.parse("2026-10-30T10:00:00Z")
        val first = ConsentGrant.accept(UUID.randomUUID(), patient, type, user, now)
        TenantScope.runAs(clinic.id) { tx.executeWithoutResult { grants.insert(clinic.id, first) } }
        assertThatThrownBy {
            TenantScope.runAs(clinic.id) { tx.executeWithoutResult { grants.insert(clinic.id, ConsentGrant.accept(UUID.randomUUID(), patient, type, user, now)) } }
        }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
            assertThat(it.code).isEqualTo("CONSENT_ALREADY_ACCEPTED")
        }

        val revoked = first.revoke(user, now.plusSeconds(60))
        TenantScope.runAs(clinic.id) { tx.executeWithoutResult { grants.revoke(revoked) } }
        // outra requisição que também leu o aceite vigente e tenta revogar depois
        assertThatThrownBy { TenantScope.runAs(clinic.id) { tx.executeWithoutResult { grants.revoke(first.revoke(user, now.plusSeconds(120))) } } }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("CONSENT_ALREADY_REVOKED") }
        val stored = TenantScope.runAs(clinic.id) { tx.execute { grants.find(patient, first.id) } }!!
        assertThat(stored.revokedAt).isEqualTo(now.plusSeconds(60))
        assertThat(TenantScope.runAs(clinic.id) { tx.execute { grants.hasActive(patient, type.id) } }).isFalse()
    }
}
