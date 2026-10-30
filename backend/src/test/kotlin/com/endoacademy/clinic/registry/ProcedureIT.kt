package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.registry.application.ProcedureStore
import com.endoacademy.clinic.registry.domain.Procedure
import com.endoacademy.clinic.support.ClinicApiIT
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

/** Procedimentos de ponta a ponta (T4.3; CA1 e CA2 aplicados ao procedimento). */
class ProcedureIT : ClinicApiIT() {

    @Autowired
    lateinit var store: ProcedureStore

    private fun create(clinic: Clinic, name: String, description: String? = null, sub: String = clinic.adminSub): ResultActions =
        mockMvc.perform(call(post("/api/v1/procedures").contentType(MediaType.APPLICATION_JSON).content(body("name" to name, "description" to description)), clinic, sub))

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    @Test
    fun `the clinic admin creates, reads, updates, inactivates and filters procedures`() {
        val clinic = newClinic()
        val consulta = idOf(create(clinic, "  Consulta  ", "Primeira consulta").andExpect(status().isCreated).andExpect(jsonPath("$.name").value("Consulta")).andExpect(jsonPath("$.active").value(true)))
        val retorno = idOf(create(clinic, "Retorno").andExpect(status().isCreated).andExpect(jsonPath("$.description").doesNotExist()))

        mockMvc.perform(call(get("/api/v1/procedures/$consulta"), clinic)).andExpect(status().isOk).andExpect(jsonPath("$.description").value("Primeira consulta"))
        mockMvc.perform(
            call(put("/api/v1/procedures/$retorno").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Retorno em 30 dias", "description" to "Revisão", "active" to false)), clinic),
        ).andExpect(status().isOk).andExpect(jsonPath("$.name").value("Retorno em 30 dias")).andExpect(jsonPath("$.active").value(false))

        mockMvc.perform(call(get("/api/v1/procedures"), clinic)).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].name").value("Consulta")).andExpect(jsonPath("$[1].name").value("Retorno em 30 dias"))
        mockMvc.perform(call(get("/api/v1/procedures?active=true"), clinic)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Consulta"))
        mockMvc.perform(call(get("/api/v1/procedures?active=false"), clinic)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Retorno em 30 dias"))
    }

    @Test
    fun `the name is unique per clinic ignoring case, but another clinic may reuse it`() {
        val a = newClinic()
        val b = newClinic()
        create(a, "Consulta").andExpect(status().isCreated)
        create(a, "  CONSULTA ").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PROCEDURE_NAME_TAKEN"))
        create(b, "Consulta").andExpect(status().isCreated)

        val other = idOf(create(a, "Retorno").andExpect(status().isCreated))
        mockMvc.perform(
            call(put("/api/v1/procedures/$other").contentType(MediaType.APPLICATION_JSON).content(body("name" to "consulta", "active" to true)), a),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PROCEDURE_NAME_TAKEN"))
        mockMvc.perform(
            call(put("/api/v1/procedures/$other").contentType(MediaType.APPLICATION_JSON).content(body("name" to "RETORNO", "active" to true)), a),
        ).andExpect(status().isOk).andExpect(jsonPath("$.name").value("RETORNO"))
    }

    @Test
    fun `the database unique index turns a race on the name into a conflict, not a server error`() {
        val clinic = newClinic()
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult { store.insert(clinic.id, Procedure.create(UUID.randomUUID(), "Corrida", null)) }
        }
        assertThatThrownBy {
            TenantScope.runAs(clinic.id) { tx.executeWithoutResult { store.insert(clinic.id, Procedure.create(UUID.randomUUID(), "CORRIDA", null)) } }
        }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
            assertThat(it.code).isEqualTo("PROCEDURE_NAME_TAKEN")
        }
    }

    @Test
    fun `invalid data is rejected without echoing the value and nothing is stored`() {
        val clinic = newClinic()
        create(clinic, "   ").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PROCEDURE_NAME_REQUIRED"))
        create(clinic, "x".repeat(121)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PROCEDURE_NAME_TOO_LONG"))
            .andExpect(jsonPath("$.detail").doesNotExist())
        create(clinic, "Consulta", "y".repeat(501)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PROCEDURE_DESCRIPTION_TOO_LONG"))
        mockMvc.perform(call(post("/api/v1/procedures").contentType(MediaType.APPLICATION_JSON).content("{"), clinic)).andExpect(status().isBadRequest)
        mockMvc.perform(call(get("/api/v1/procedures"), clinic)).andExpect(jsonPath("$.length()").value(0))

        val id = idOf(create(clinic, "Consulta").andExpect(status().isCreated))
        mockMvc.perform(call(put("/api/v1/procedures/$id").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Consulta")), clinic))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        mockMvc.perform(call(put("/api/v1/procedures/$id").contentType(MediaType.APPLICATION_JSON).content(body("name" to " ", "active" to true)), clinic))
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PROCEDURE_NAME_REQUIRED"))
        mockMvc.perform(call(get("/api/v1/procedures/$id"), clinic)).andExpect(jsonPath("$.name").value("Consulta"))
    }

    @Test
    fun `a procedure of another clinic answers 404 and is untouched (CA2)`() {
        val a = newClinic()
        val b = newClinic()
        val ofB = idOf(create(b, "Só da B").andExpect(status().isCreated))

        mockMvc.perform(call(get("/api/v1/procedures/$ofB"), a)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PROCEDURE_NOT_FOUND"))
        mockMvc.perform(
            call(put("/api/v1/procedures/$ofB").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Invadido", "active" to false)), a),
        ).andExpect(status().isNotFound)
        mockMvc.perform(call(get("/api/v1/procedures/$ofB"), b)).andExpect(jsonPath("$.name").value("Só da B")).andExpect(jsonPath("$.active").value(true))
        mockMvc.perform(call(get("/api/v1/procedures"), a)).andExpect(jsonPath("$.length()").value(0))
        mockMvc.perform(call(get("/api/v1/procedures"), b)).andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `only MANAGE_PROCEDURES opens the endpoints and the denials are audited`() {
        val clinic = newClinic()
        val id = idOf(create(clinic, "Consulta").andExpect(status().isCreated))
        val outsider = memberWithPermissions(clinic, "VIEW_AUDIT_TRAIL")
        val manager = memberWithPermissions(clinic, "MANAGE_PROCEDURES")

        create(clinic, "Outra", sub = outsider).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        mockMvc.perform(call(get("/api/v1/procedures"), clinic, outsider)).andExpect(status().isForbidden)
        mockMvc.perform(call(get("/api/v1/procedures/$id"), clinic, outsider)).andExpect(status().isForbidden)
        mockMvc.perform(call(put("/api/v1/procedures/$id").contentType(MediaType.APPLICATION_JSON).content(body("name" to "X", "active" to true)), clinic, outsider))
            .andExpect(status().isForbidden)
        assertThat(deniedCount(clinic)).isEqualTo(4)

        create(clinic, "Do gerente", sub = manager).andExpect(status().isCreated)
        mockMvc.perform(call(get("/api/v1/procedures"), clinic, manager)).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `without a token the answer is 401 and without a clinic header it is a client error`() {
        mockMvc.perform(get("/api/v1/procedures")).andExpect(status().isUnauthorized)
        val clinic = newClinic()
        mockMvc.perform(get("/api/v1/procedures").header("Authorization", bearer(clinic.adminSub))).andExpect(status().is4xxClientError)
    }

    @Test
    fun `creating and updating are audited with field names only`() {
        val clinic = newClinic()
        val id = idOf(create(clinic, "Dado sigiloso do procedimento", "descrição qualquer").andExpect(status().isCreated))
        mockMvc.perform(call(put("/api/v1/procedures/$id").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Outro nome sigiloso", "active" to false)), clinic)).andExpect(status().isOk)

        assertThat(auditCount(clinic, "CREATE_PROCEDURE")).isEqualTo(1)
        assertThat(auditCount(clinic, "UPDATE_PROCEDURE")).isEqualTo(1)
        val stored = TenantScope.runAs(clinic.id) {
            tx.execute {
                jdbc.queryForList("select details::text || coalesce(entity_id, '') as text from platform.audit_event where entity_type = 'PROCEDURE'", String::class.java)
            }!!
        }
        assertThat(stored).hasSize(2)
        stored.forEach { assertThat(it).doesNotContain("sigiloso").doesNotContain("descrição qualquer") }
    }

    private fun deniedCount(clinic: Clinic): Int = TenantScope.runAs(clinic.id) {
        tx.execute { jdbc.queryForObject("select count(*) from platform.audit_event where outcome = 'DENIED' and entity_id = 'MANAGE_PROCEDURES'", Int::class.java) }!!
    }
}
