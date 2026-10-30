package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.DatabaseIT
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Perfis e permissões de ponta a ponta (T2.7; CA12). */
@AutoConfigureMockMvc
class RoleIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var json: JsonMapper

    private val tx get() = TransactionTemplate(transactionManager)

    companion object {
        private val oidc = MockOAuth2Server().also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun oidc(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { oidc.issuerUrl("clinic").toString() }
        }

        @JvmStatic
        @AfterAll
        fun stop() = oidc.shutdown()

        private fun bearer(subject: String) = "Bearer " + oidc.issueToken(
            "clinic", "client", DefaultOAuth2TokenCallback("clinic", subject, "JWT", listOf("clinic-api"), emptyMap(), 3600),
        ).serialize()
    }

    /** Clínica criada pelo fluxo real; `adminSub` é o do 1º administrador (`CLINIC_ADMIN`). */
    private class Clinic(val id: UUID, val adminSub: String)

    private val platformSub: String by lazy {
        val id = UUID.randomUUID()
        val sub = "sub-platform-$id"
        TenantScope.runAsPlatform {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Admin')", id, sub, "$id@example.test")
                jdbc.update("insert into platform.platform_admin (user_id) values (?)", id)
            }
        }
        sub
    }

    private fun newClinic(): Clinic {
        val email = "admin-${UUID.randomUUID()}@clinica.test"
        val body = mockMvc.perform(
            post("/api/v1/admin/clinics").header(HttpHeaders.AUTHORIZATION, bearer(platformSub)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("name" to "Clínica", "adminEmail" to email, "adminName" to "Admin"))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return Clinic(UUID.fromString(json.readTree(body).get("id").asString()), UUID.nameUUIDFromBytes(email.toByteArray()).toString())
    }

    private fun call(builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder, clinic: Clinic, sub: String = clinic.adminSub) =
        builder.header(HttpHeaders.AUTHORIZATION, bearer(sub)).header("X-Clinic-Id", clinic.id.toString())

    private fun body(vararg pairs: Pair<String, Any>) = json.writeValueAsString(mapOf(*pairs))

    private fun createRole(clinic: Clinic, name: String, permissions: List<String>, sub: String = clinic.adminSub) =
        mockMvc.perform(
            call(builder = post("/api/v1/roles").contentType(MediaType.APPLICATION_JSON).content(body("name" to name, "permissions" to permissions)), clinic = clinic, sub = sub),
        )

    private fun roleId(result: org.springframework.test.web.servlet.ResultActions) =
        UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    /** Cria um usuário ativo na clínica com o perfil dado (o convite por API é da T2.6); devolve o `sub`. */
    private fun memberWithRole(clinic: Clinic, roleId: UUID): String {
        val userId = UUID.randomUUID()
        val sub = "sub-member-$userId"
        val membershipId = UUID.randomUUID()
        TenantScope.runAsPlatform {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Membro')", userId, sub, "$userId@example.test")
                jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, 'ACTIVE', ?)", membershipId, clinic.id, userId, sub)
            }
        }
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult { jdbc.update("insert into platform.membership_role (tenant_id, membership_id, role_id) values (?, ?, ?)", clinic.id, membershipId, roleId) }
        }
        return sub
    }

    @Test
    fun `the clinic admin creates, lists, updates and deletes a role`() {
        val clinic = newClinic()
        val id = roleId(createRole(clinic, "Recepção", listOf("MANAGE_BRANDING", "VIEW_AUDIT_TRAIL")).andExpect(status().isCreated).andExpect(jsonPath("$.members").value(0)))

        mockMvc.perform(call(builder = get("/api/v1/roles"), clinic = clinic))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.name == 'Recepção')].permissions[0]").value("MANAGE_BRANDING"))
        mockMvc.perform(
            call(builder = put("/api/v1/roles/$id").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Recepção 2", "permissions" to listOf("MANAGE_USERS"))), clinic = clinic),
        ).andExpect(status().isOk).andExpect(jsonPath("$.name").value("Recepção 2")).andExpect(jsonPath("$.permissions[0]").value("MANAGE_USERS"))
        mockMvc.perform(call(builder = delete("/api/v1/roles/$id"), clinic = clinic)).andExpect(status().isNoContent)
        mockMvc.perform(call(builder = get("/api/v1/roles"), clinic = clinic)).andExpect(jsonPath("$[?(@.name == 'Recepção 2')]").doesNotExist())
    }

    @Test
    fun `only catalog permissions are accepted and platform only ones are not assignable`() {
        val clinic = newClinic()
        createRole(clinic, "R1", listOf("INEXISTENTE")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PERMISSION_NOT_ASSIGNABLE"))
        createRole(clinic, "R2", listOf("MANAGE_CLINICS")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PERMISSION_NOT_ASSIGNABLE"))
        createRole(clinic, " ", emptyList()).andExpect(status().isBadRequest)
        createRole(clinic, "Duplicado", emptyList()).andExpect(status().isCreated)
        createRole(clinic, "duplicado", emptyList()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"))

        mockMvc.perform(call(builder = get("/api/v1/permissions"), clinic = clinic))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.code == 'MANAGE_ROLES')]").exists())
            .andExpect(jsonPath("$[?(@.code == 'MANAGE_CLINICS')]").doesNotExist())
    }

    @Test
    fun `a role in use is not deleted and the CLINIC_ADMIN role is protected`() {
        val clinic = newClinic()
        val id = roleId(createRole(clinic, "Em uso", listOf("VIEW_AUDIT_TRAIL")).andExpect(status().isCreated))
        memberWithRole(clinic, id)

        mockMvc.perform(call(builder = delete("/api/v1/roles/$id"), clinic = clinic))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ROLE_IN_USE"))

        val adminRole = mockMvc.perform(call(builder = get("/api/v1/roles"), clinic = clinic)).andReturn().response.contentAsString
            .let { json.readTree(it).first { role -> role.get("name").asString() == "CLINIC_ADMIN" }.get("id").asString() }
        mockMvc.perform(call(builder = delete("/api/v1/roles/$adminRole"), clinic = clinic)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ROLE_PROTECTED"))
        mockMvc.perform(
            call(builder = put("/api/v1/roles/$adminRole").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Outro", "permissions" to emptyList<String>())), clinic = clinic),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ROLE_PROTECTED"))
    }

    @Test
    fun `a user with a restricted role gets 403 outside its permissions, audited as DENIED (CA12)`() {
        val clinic = newClinic()
        val auditor = roleId(createRole(clinic, "Auditor", listOf("VIEW_AUDIT_TRAIL")).andExpect(status().isCreated))
        val auditorSub = memberWithRole(clinic, auditor)

        createRole(clinic, "Tentativa", emptyList(), sub = auditorSub).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        mockMvc.perform(call(builder = get("/api/v1/roles"), clinic = clinic, sub = auditorSub)).andExpect(status().isForbidden)
        mockMvc.perform(call(builder = get("/api/v1/permissions"), clinic = clinic, sub = auditorSub)).andExpect(status().isForbidden)
        mockMvc.perform(call(builder = delete("/api/v1/roles/$auditor"), clinic = clinic, sub = auditorSub)).andExpect(status().isForbidden)

        val denied = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForObject("select count(*) from platform.audit_event where outcome = 'DENIED' and entity_id = 'MANAGE_ROLES'", Int::class.java) }!!
        }
        assertThat(denied).isEqualTo(4)
    }

    @Test
    fun `MANAGE_USERS alone can read roles and permissions but not change them`() {
        val clinic = newClinic()
        val usersRole = roleId(createRole(clinic, "RH", listOf("MANAGE_USERS")).andExpect(status().isCreated))
        val sub = memberWithRole(clinic, usersRole)

        mockMvc.perform(call(builder = get("/api/v1/roles"), clinic = clinic, sub = sub)).andExpect(status().isOk)
        mockMvc.perform(call(builder = get("/api/v1/permissions"), clinic = clinic, sub = sub)).andExpect(status().isOk)
        createRole(clinic, "Nova", emptyList(), sub = sub).andExpect(status().isForbidden)
    }

    @Test
    fun `a role of another clinic answers 404 and is untouched`() {
        val a = newClinic()
        val b = newClinic()
        val roleOfB = roleId(createRole(b, "Só da B", listOf("VIEW_AUDIT_TRAIL")).andExpect(status().isCreated))

        mockMvc.perform(
            call(builder = put("/api/v1/roles/$roleOfB").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Invadido", "permissions" to emptyList<String>())), clinic = a),
        ).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"))
        mockMvc.perform(call(builder = delete("/api/v1/roles/$roleOfB"), clinic = a)).andExpect(status().isNotFound)
        mockMvc.perform(call(builder = get("/api/v1/roles"), clinic = b)).andExpect(jsonPath("$[?(@.name == 'Só da B')]").exists())
        mockMvc.perform(call(builder = get("/api/v1/roles"), clinic = a)).andExpect(jsonPath("$[?(@.name == 'Só da B')]").doesNotExist())
    }
}
