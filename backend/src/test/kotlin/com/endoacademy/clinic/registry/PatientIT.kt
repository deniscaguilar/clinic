package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.ClinicApiIT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Pacientes de ponta a ponta (T4.6; CA1, CA2, CA8, CA13, CA14): cadastro, leitura auditada, permissões e isolamento. */
class PatientIT : ClinicApiIT() {

    private val cpfA = "529.982.247-25"
    private val cpfB = "111.444.777-35"
    private val cpfC = "000.000.006-04"

    private fun address(overrides: Map<String, Any?> = emptyMap()) =
        mapOf("zipCode" to "01310-100", "street" to "Avenida Paulista", "number" to "1578", "complement" to "Sala 12", "district" to "Bela Vista", "city" to "São Paulo", "state" to "SP") + overrides

    private fun patientBody(vararg overrides: Pair<String, Any?>): String {
        val base = mutableMapOf<String, Any?>(
            "cpf" to cpfA, "fullName" to "Maria da Silva", "birthDate" to "1985-03-12", "whatsapp" to "(11) 98765-4321",
            "email" to "maria@example.com", "landline" to "(11) 3456-7890", "address" to address(),
        )
        overrides.forEach { base[it.first] = it.second }
        return json.writeValueAsString(base)
    }

    private fun register(clinic: Clinic, vararg overrides: Pair<String, Any?>, sub: String = clinic.adminSub): ResultActions =
        mockMvc.perform(call(post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(patientBody(*overrides)), clinic, sub))

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    private fun audit(clinic: Clinic, action: String, outcome: String = "SUCCESS") = auditCount(clinic, action, outcome)

    @Test
    fun `a clinic registers, reads, updates, lists and searches a patient`() {
        val clinic = newClinic()
        val id = idOf(
            register(clinic).andExpect(status().isCreated)
                .andExpect(jsonPath("$.fullName").value("Maria da Silva"))
                .andExpect(jsonPath("$.cpf").value("52998224725"))
                .andExpect(jsonPath("$.birthDate").value("1985-03-12"))
                .andExpect(jsonPath("$.whatsapp").value("11987654321"))
                .andExpect(jsonPath("$.landline").value("1134567890"))
                .andExpect(jsonPath("$.address.zipCode").value("01310100"))
                .andExpect(jsonPath("$.address.state").value("SP")),
        )
        register(clinic, "cpf" to cpfB, "fullName" to "João Pereira", "whatsapp" to null, "landline" to null).andExpect(status().isCreated)

        mockMvc.perform(call(get("/api/v1/patients/$id"), clinic)).andExpect(status().isOk).andExpect(jsonPath("$.email").value("maria@example.com"))
        mockMvc.perform(
            call(
                put("/api/v1/patients/$id").contentType(MediaType.APPLICATION_JSON)
                    .content(patientBody("cpf" to null, "fullName" to "Maria da Silva Souza", "address" to address(mapOf("city" to "Campinas")))),
                clinic,
            ),
        ).andExpect(status().isOk).andExpect(jsonPath("$.fullName").value("Maria da Silva Souza")).andExpect(jsonPath("$.address.city").value("Campinas")).andExpect(jsonPath("$.cpf").value("52998224725"))

        mockMvc.perform(call(get("/api/v1/patients"), clinic)).andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].fullName").value("João Pereira")).andExpect(jsonPath("$.items[1].cpf").value("52998224725"))
            .andExpect(jsonPath("$.items[0].email").doesNotExist())
        mockMvc.perform(call(get("/api/v1/patients?name=souza&page=0&size=1"), clinic)).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items.length()").value(1))
        mockMvc.perform(call(post("/api/v1/patients/search").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("cpf" to "52998224725"))), clinic))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(id.toString()))
        mockMvc.perform(call(post("/api/v1/patients/search").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("cpf" to cpfC))), clinic))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(0))
    }

    @Test
    fun `each missing or invalid required field is a validation error and nothing is stored (CA13, CA14)`() {
        val clinic = newClinic()
        register(clinic, "fullName" to "Maria").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PATIENT_FULL_NAME_REQUIRED"))
        register(clinic, "fullName" to null).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PATIENT_FULL_NAME_REQUIRED"))
        register(clinic, "birthDate" to null).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PATIENT_BIRTH_DATE_INVALID"))
        register(clinic, "birthDate" to "2999-01-01").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PATIENT_BIRTH_DATE_INVALID"))
        register(clinic, "address" to null).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PATIENT_ADDRESS_REQUIRED"))
        register(clinic, "address" to address(mapOf("zipCode" to "123"))).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CEP_INVALID"))
        register(clinic, "whatsapp" to null, "email" to null).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PATIENT_CONTACT_REQUIRED"))
        register(clinic, "cpf" to "529.982.247-24").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CPF_INVALID"))
        register(clinic, "cpf" to null).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CPF_INVALID"))
        register(clinic, "cpf" to "111.111.111-11").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CPF_INVALID"))
        mockMvc.perform(call(get("/api/v1/patients"), clinic)).andExpect(jsonPath("$.total").value(0))

        register(clinic).andExpect(status().isCreated)
        register(clinic, "fullName" to "Outra Pessoa").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CPF_ALREADY_REGISTERED"))
        register(clinic, "cpf" to "52998224725", "fullName" to "Outra Pessoa").andExpect(status().isConflict)
        mockMvc.perform(call(get("/api/v1/patients"), clinic)).andExpect(jsonPath("$.total").value(1))
    }

    @Test
    fun `errors never echo what was sent`() {
        val clinic = newClinic()
        val bodies = listOf(
            register(clinic, "cpf" to "529.982.247-24", "fullName" to "Nome Sigiloso").andReturn().response.contentAsString,
            register(clinic, "fullName" to "Nome Sigiloso 123").andReturn().response.contentAsString,
            register(clinic, "email" to "sigiloso-sem-arroba").andReturn().response.contentAsString,
            mockMvc.perform(call(post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(patientBody("birthDate" to "12/03/1985sigiloso")), clinic)).andExpect(status().isBadRequest)
                .andReturn().response.contentAsString,
        )
        bodies.forEach { assertThat(it).doesNotContain("Sigiloso").doesNotContain("sigiloso").doesNotContain("529.982").doesNotContain("52998224724") }
        register(clinic).andExpect(status().isCreated)
        val duplicate = register(clinic, "fullName" to "Outra Pessoa").andExpect(status().isConflict).andReturn().response.contentAsString
        assertThat(duplicate).doesNotContain("52998224725").doesNotContain("529.982")
    }

    @Test
    fun `a patient of another clinic is invisible and untouchable, even by CPF (CA1, CA2)`() {
        val a = newClinic()
        val b = newClinic()
        val ofB = idOf(register(b, "fullName" to "Só da Clínica B").andExpect(status().isCreated))
        register(a, "cpf" to cpfB, "fullName" to "Só da Clínica A").andExpect(status().isCreated)

        mockMvc.perform(call(get("/api/v1/patients/$ofB"), a)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"))
        mockMvc.perform(call(put("/api/v1/patients/$ofB").contentType(MediaType.APPLICATION_JSON).content(patientBody("cpf" to null, "fullName" to "Nome Invadido")), a)).andExpect(status().isNotFound)
        mockMvc.perform(call(post("/api/v1/patients/search").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("cpf" to cpfA))), a))
            .andExpect(jsonPath("$.items.length()").value(0))
        mockMvc.perform(call(get("/api/v1/patients"), a)).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].fullName").value("Só da Clínica A"))
        mockMvc.perform(call(get("/api/v1/patients/$ofB"), b)).andExpect(jsonPath("$.fullName").value("Só da Clínica B"))

        // o mesmo CPF pode existir em duas clínicas
        register(a, "cpf" to cpfA, "fullName" to "Mesma Pessoa em A").andExpect(status().isCreated)
    }

    @Test
    fun `the page size is limited and bad paging is a 400`() {
        val clinic = newClinic()
        listOf("size=101", "size=0", "page=-1", "name=" + "x".repeat(151)).forEach {
            mockMvc.perform(call(get("/api/v1/patients?$it"), clinic)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        }
        mockMvc.perform(call(get("/api/v1/patients?size=100"), clinic)).andExpect(status().isOk).andExpect(jsonPath("$.size").value(100))
    }

    @Test
    fun `permissions are separate for viewing, managing and changing the CPF, and denials are audited`() {
        val clinic = newClinic()
        val id = idOf(register(clinic).andExpect(status().isCreated))
        val viewer = memberWithPermissions(clinic, "VIEW_PATIENTS")
        val manager = memberWithPermissions(clinic, "VIEW_PATIENTS", "MANAGE_PATIENTS")
        val outsider = memberWithPermissions(clinic, "MANAGE_PROCEDURES")
        val cpfChanger = memberWithPermissions(clinic, "MANAGE_PATIENTS", "CHANGE_PATIENT_CPF")

        mockMvc.perform(call(get("/api/v1/patients/$id"), clinic, viewer)).andExpect(status().isOk)
        register(clinic, "cpf" to cpfB, sub = viewer).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        mockMvc.perform(call(put("/api/v1/patients/$id").contentType(MediaType.APPLICATION_JSON).content(patientBody("cpf" to null)), clinic, viewer)).andExpect(status().isForbidden)

        mockMvc.perform(call(get("/api/v1/patients"), clinic, outsider)).andExpect(status().isForbidden)
        mockMvc.perform(call(get("/api/v1/patients/$id"), clinic, outsider)).andExpect(status().isForbidden)
        mockMvc.perform(call(post("/api/v1/patients/search").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("cpf" to cpfA))), clinic, outsider))
            .andExpect(status().isForbidden)
        register(clinic, "cpf" to cpfB, sub = outsider).andExpect(status().isForbidden)
        assertThat(deniedBy(clinic, "VIEW_PATIENTS")).isEqualTo(3)
        assertThat(deniedBy(clinic, "MANAGE_PATIENTS")).isEqualTo(3)

        // MANAGE_PATIENTS edita, mas não troca o CPF
        mockMvc.perform(call(put("/api/v1/patients/$id").contentType(MediaType.APPLICATION_JSON).content(patientBody("cpf" to null, "fullName" to "Maria da Silva Lima")), clinic, manager)).andExpect(status().isOk)
        mockMvc.perform(call(put("/api/v1/patients/$id").contentType(MediaType.APPLICATION_JSON).content(patientBody("cpf" to cpfB)), clinic, manager))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        assertThat(deniedBy(clinic, "CHANGE_PATIENT_CPF")).isEqualTo(1)
        mockMvc.perform(call(get("/api/v1/patients/$id"), clinic, manager)).andExpect(jsonPath("$.cpf").value("52998224725"))

        // com a permissão específica o CPF muda, e a mudança é auditada à parte
        mockMvc.perform(call(put("/api/v1/patients/$id").contentType(MediaType.APPLICATION_JSON).content(patientBody("cpf" to cpfB)), clinic, cpfChanger))
            .andExpect(status().isOk).andExpect(jsonPath("$.cpf").value("11144477735"))
        assertThat(audit(clinic, "CHANGE_PATIENT_CPF")).isEqualTo(1)
        mockMvc.perform(call(put("/api/v1/patients/$id").contentType(MediaType.APPLICATION_JSON).content(patientBody("cpf" to cpfB)), clinic, cpfChanger)).andExpect(status().isOk)
        assertThat(audit(clinic, "CHANGE_PATIENT_CPF")).isEqualTo(1)
        register(clinic, "cpf" to cpfC, "fullName" to "Terceira Pessoa").andExpect(status().isCreated)
        mockMvc.perform(call(put("/api/v1/patients/$id").contentType(MediaType.APPLICATION_JSON).content(patientBody("cpf" to cpfC)), clinic, cpfChanger))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CPF_ALREADY_REGISTERED"))
        mockMvc.perform(get("/api/v1/patients")).andExpect(status().isUnauthorized)
    }

    private fun deniedBy(clinic: Clinic, permission: String): Int = TenantScope.runAs(clinic.id) {
        tx.execute { jdbc.queryForObject("select count(*) from platform.audit_event where outcome = 'DENIED' and entity_id = ?", Int::class.java, permission) }!!
    }

    @Test
    fun `every read is audited, one event per patient and one per listing, with no personal data (CA8)`() {
        val clinic = newClinic()
        val id = idOf(register(clinic, "fullName" to "Nome Muito Sigiloso").andExpect(status().isCreated))
        register(clinic, "cpf" to cpfB, "fullName" to "Outro Nome Sigiloso", "email" to "sigiloso@example.com").andExpect(status().isCreated)
        assertThat(audit(clinic, "REGISTER_PATIENT")).isEqualTo(2)

        mockMvc.perform(call(get("/api/v1/patients/$id"), clinic)).andExpect(status().isOk)
        mockMvc.perform(call(get("/api/v1/patients/$id"), clinic)).andExpect(status().isOk)
        assertThat(audit(clinic, "READ_PATIENT")).isEqualTo(2)

        mockMvc.perform(call(get("/api/v1/patients"), clinic)).andExpect(status().isOk)
        assertThat(audit(clinic, "LIST_PATIENTS")).isEqualTo(1)
        assertThat(audit(clinic, "READ_PATIENT")).isEqualTo(2) // listar não gera um evento por paciente

        mockMvc.perform(call(post("/api/v1/patients/search").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("cpf" to cpfA))), clinic)).andExpect(status().isOk)
        assertThat(audit(clinic, "SEARCH_PATIENT_BY_CPF")).isEqualTo(1)
        assertThat(audit(clinic, "READ_PATIENT")).isEqualTo(3)

        mockMvc.perform(call(put("/api/v1/patients/$id").contentType(MediaType.APPLICATION_JSON).content(patientBody("cpf" to null, "fullName" to "Nome Muito Sigiloso Jr")), clinic)).andExpect(status().isOk)
        assertThat(audit(clinic, "UPDATE_PATIENT")).isEqualTo(1)

        val stored = TenantScope.runAs(clinic.id) {
            tx.execute {
                jdbc.queryForList("select action || ' ' || coalesce(entity_id, '') || ' ' || details::text from platform.audit_event where entity_type = 'PATIENT'", String::class.java)
            }!!.filterNotNull()
        }
        assertThat(stored).isNotEmpty()
        stored.forEach {
            assertThat(it).doesNotContain("Sigiloso").doesNotContain("52998224725").doesNotContain("11144477735").doesNotContain("example.com").doesNotContain("98765")
        }
        assertThat(stored.first { it.startsWith("LIST_PATIENTS") }).contains("count.2")
        assertThat(stored.first { it.startsWith("UPDATE_PATIENT") }).contains("fullName").doesNotContain("whatsapp")
    }

    @Test
    fun `the standard role templates get the patient permissions from the plan`() {
        val clinic = newClinic()
        val granted = TenantScope.runAs(clinic.id) {
            tx.execute {
                jdbc.query("select r.name, rp.permission from platform.role r join platform.role_permission rp on rp.role_id = r.id order by 1, 2", { rs, _ -> rs.getString(1)!! to rs.getString(2)!! })
                    .groupBy({ it.first }, { it.second })
            }!!
        }
        assertThat(granted.getValue("CLINIC_ADMIN")).contains("VIEW_PATIENTS", "MANAGE_PATIENTS", "CHANGE_PATIENT_CPF", "MANAGE_PROCEDURES", "MANAGE_PRACTITIONERS", "MANAGE_CONSENT_TYPES", "RECORD_CONSENT")
        assertThat(granted.getValue("STAFF")).containsExactlyInAnyOrder("VIEW_PATIENTS", "MANAGE_PATIENTS", "RECORD_CONSENT")
        listOf("DOCTOR", "NURSE", "MANAGER").forEach { assertThat(granted.getValue(it)).containsExactly("VIEW_PATIENTS") }
        assertThat(granted.values.flatten()).doesNotContain("MANAGE_CLINICS")
        listOf("DOCTOR", "NURSE", "MANAGER", "STAFF").forEach { assertThat(granted.getValue(it)).doesNotContain("CHANGE_PATIENT_CPF", "MANAGE_CONSENT_TYPES") }
        listOf("DOCTOR", "NURSE", "MANAGER").forEach { assertThat(granted.getValue(it)).doesNotContain("RECORD_CONSENT") }
    }
}
