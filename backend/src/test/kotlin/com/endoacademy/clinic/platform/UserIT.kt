package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.identity.InMemoryIdentityProvider
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.platform.application.IdentityProviderException
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Convidar, trocar perfis, desativar e reenviar convite de ponta a ponta (T2.6). */
@AutoConfigureMockMvc
class UserIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var identity: InMemoryIdentityProvider

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

    private fun JsonNode.elements(): List<JsonNode> = (0 until size()).map { get(it) }

    private class Clinic(val id: UUID, val adminEmail: String) {
        val adminSub: String = UUID.nameUUIDFromBytes(adminEmail.toByteArray()).toString()
    }

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

    private fun newClinic(adminEmail: String = "admin-${UUID.randomUUID()}@clinica.test"): Clinic {
        val body = mockMvc.perform(
            post("/api/v1/admin/clinics").header(HttpHeaders.AUTHORIZATION, bearer(platformSub)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("name" to "Clínica", "adminEmail" to adminEmail, "adminName" to "Admin"))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return Clinic(UUID.fromString(json.readTree(body).get("id").asString()), adminEmail)
    }

    private fun call(builder: MockHttpServletRequestBuilder, clinic: Clinic, sub: String = clinic.adminSub) =
        builder.header(HttpHeaders.AUTHORIZATION, bearer(sub)).header("X-Clinic-Id", clinic.id.toString())

    private fun roleId(clinic: Clinic, name: String): UUID = UUID.fromString(
        json.readTree(mockMvc.perform(call(get("/api/v1/roles"), clinic)).andReturn().response.contentAsString).elements()
            .first { it.get("name").asString() == name }.get("id").asString(),
    )

    private fun invite(clinic: Clinic, email: String, vararg roles: UUID, name: String = "Ana Souza", sub: String = clinic.adminSub) =
        mockMvc.perform(
            call(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("email" to email, "name" to name, "roleIds" to roles.toList()))), clinic, sub),
        )

    private fun membershipOf(result: org.springframework.test.web.servlet.ResultActions) =
        UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("membershipId").asString())

    private fun uniqueEmail() = "ana-${UUID.randomUUID()}@exemplo.test"

    private fun subOf(email: String) = identity.createUser(email, "x")

    private fun users(clinic: Clinic): List<JsonNode> =
        json.readTree(mockMvc.perform(call(get("/api/v1/users"), clinic)).andExpect(status().isOk).andReturn().response.contentAsString).elements()

    @Test
    fun `the clinic admin invites a user who then enters with the chosen role`() {
        val clinic = newClinic()
        val email = uniqueEmail()

        val membership = membershipOf(invite(clinic, email, roleId(clinic, "DOCTOR")).andExpect(status().isCreated).andExpect(jsonPath("$.status").value("ACTIVE")))

        val sub = subOf(email)
        mockMvc.perform(get("/api/v1/me/clinics").header(HttpHeaders.AUTHORIZATION, bearer(sub))).andExpect(status().isOk).andExpect(jsonPath("$[0].id").value(clinic.id.toString()))
        mockMvc.perform(call(get("/api/v1/session"), clinic, sub)).andExpect(status().isOk).andExpect(jsonPath("$.roles[0]").value("DOCTOR"))
        assertThat(users(clinic).map { it.get("membershipId").asString() }).contains(membership.toString())
    }

    @Test
    fun `an e-mail that exists in another clinic reuses the login and the answer does not reveal it`() {
        val a = newClinic()
        val b = newClinic()
        val fresh = invite(b, uniqueEmail(), roleId(b, "STAFF")).andExpect(status().isCreated).andReturn().response.contentAsString
        val knownAlreadyInA = a.adminEmail

        val reused = invite(b, knownAlreadyInA, roleId(b, "STAFF"), name = "Outro nome").andExpect(status().isCreated).andReturn().response.contentAsString

        assertThat(json.readTree(reused).propertyNames().toList()).isEqualTo(json.readTree(fresh).propertyNames().toList())
        assertThat(json.readTree(reused).get("status").asString()).isEqualTo(json.readTree(fresh).get("status").asString()).isEqualTo("ACTIVE")
        // mesmo login nas duas clínicas
        mockMvc.perform(get("/api/v1/me/clinics").header(HttpHeaders.AUTHORIZATION, bearer(a.adminSub)))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(2))
        mockMvc.perform(call(get("/api/v1/session"), b, a.adminSub)).andExpect(status().isOk).andExpect(jsonPath("$.roles[0]").value("STAFF"))
        // o nome do login existente não foi sobrescrito
        assertThat(TenantScope.runAsPlatform { tx.execute { jdbc.queryForObject("select name from platform.app_user where lower(email) = ?", String::class.java, knownAlreadyInA) } }).isEqualTo("Admin")
    }

    @Test
    fun `inviting the same e-mail twice in a clinic is a conflict`() {
        val clinic = newClinic()
        val email = uniqueEmail()
        invite(clinic, email, roleId(clinic, "DOCTOR")).andExpect(status().isCreated)
        invite(clinic, email.uppercase(), roleId(clinic, "DOCTOR")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("USER_ALREADY_MEMBER"))
    }

    @Test
    fun `invalid invitations are rejected`() {
        val clinic = newClinic()
        val doctor = roleId(clinic, "DOCTOR")
        invite(clinic, "sem-arroba", doctor).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        invite(clinic, uniqueEmail()).andExpect(status().isBadRequest)
        invite(clinic, uniqueEmail(), UUID.randomUUID()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"))
    }

    @Test
    fun `a role of another clinic cannot be used`() {
        val a = newClinic()
        val b = newClinic()
        invite(a, uniqueEmail(), roleId(b, "DOCTOR")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"))
    }

    @Test
    fun `the last active CLINIC_ADMIN can be neither demoted nor deactivated`() {
        val clinic = newClinic()
        val self = users(clinic).first { it.get("email").asString() == clinic.adminEmail }.get("membershipId").asString()
        val doctor = roleId(clinic, "DOCTOR")

        mockMvc.perform(call(patch("/api/v1/users/$self").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("roleIds" to listOf(doctor)))), clinic))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LAST_CLINIC_ADMIN"))
        mockMvc.perform(call(post("/api/v1/users/$self/deactivate"), clinic)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LAST_CLINIC_ADMIN"))

        // com um segundo administrador, o primeiro pode sair
        val secondEmail = uniqueEmail()
        invite(clinic, secondEmail, roleId(clinic, "CLINIC_ADMIN")).andExpect(status().isCreated)
        mockMvc.perform(call(post("/api/v1/users/$self/deactivate"), clinic)).andExpect(status().isNoContent)
        mockMvc.perform(call(get("/api/v1/session"), clinic)).andExpect(status().isForbidden) // o 1º admin foi bloqueado
        mockMvc.perform(call(get("/api/v1/session"), clinic, subOf(secondEmail))).andExpect(status().isOk)
    }

    @Test
    fun `update changes the roles that the user gets on the next session`() {
        val clinic = newClinic()
        val email = uniqueEmail()
        val membership = membershipOf(invite(clinic, email, roleId(clinic, "DOCTOR")).andExpect(status().isCreated))

        mockMvc.perform(call(patch("/api/v1/users/$membership").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("roleIds" to listOf(roleId(clinic, "STAFF"), roleId(clinic, "MANAGER"))))), clinic))
            .andExpect(status().isNoContent)

        mockMvc.perform(call(get("/api/v1/session"), clinic, subOf(email)))
            .andExpect(status().isOk).andExpect(jsonPath("$.roles.length()").value(2)).andExpect(jsonPath("$.roles[0]").value("MANAGER"))
    }

    @Test
    fun `deactivating blocks the user's access to the clinic but keeps the record`() {
        val clinic = newClinic()
        val email = uniqueEmail()
        val membership = membershipOf(invite(clinic, email, roleId(clinic, "DOCTOR")).andExpect(status().isCreated))
        mockMvc.perform(call(get("/api/v1/session"), clinic, subOf(email))).andExpect(status().isOk)

        mockMvc.perform(call(post("/api/v1/users/$membership/deactivate"), clinic)).andExpect(status().isNoContent)

        mockMvc.perform(call(get("/api/v1/session"), clinic, subOf(email))).andExpect(status().isForbidden)
        assertThat(users(clinic).first { it.get("membershipId").asString() == membership.toString() }.get("status").asString()).isEqualTo("INACTIVE")
        // convidar de novo reativa o mesmo vínculo
        val again = membershipOf(invite(clinic, email, roleId(clinic, "STAFF")).andExpect(status().isCreated))
        assertThat(again).isEqualTo(membership)
        mockMvc.perform(call(get("/api/v1/session"), clinic, subOf(email))).andExpect(status().isOk).andExpect(jsonPath("$.roles[0]").value("STAFF"))
    }

    @Test
    fun `a pending invitation can be resent only while pending`() {
        val clinic = newClinic()
        val email = uniqueEmail()
        identity.failNext(IdentityProviderException("indisponível"))
        val membership = membershipOf(invite(clinic, email, roleId(clinic, "DOCTOR")).andExpect(status().isCreated).andExpect(jsonPath("$.status").value("PENDING")))
        mockMvc.perform(call(get("/api/v1/session"), clinic, subOf(email))).andExpect(status().isForbidden)

        mockMvc.perform(call(post("/api/v1/users/$membership/resend-invitation"), clinic)).andExpect(status().isOk).andExpect(jsonPath("$.status").value("ACTIVE"))
        mockMvc.perform(call(get("/api/v1/session"), clinic, subOf(email))).andExpect(status().isOk)
        mockMvc.perform(call(post("/api/v1/users/$membership/resend-invitation"), clinic)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("INVITATION_NOT_PENDING"))
    }

    @Test
    fun `users and memberships of another clinic are invisible and untouchable`() {
        val a = newClinic()
        val b = newClinic()
        val bMember = membershipOf(invite(b, uniqueEmail(), roleId(b, "DOCTOR")).andExpect(status().isCreated))

        assertThat(users(a).map { it.get("membershipId").asString() }).doesNotContain(bMember.toString())
        mockMvc.perform(call(post("/api/v1/users/$bMember/deactivate"), a)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("MEMBERSHIP_NOT_FOUND"))
        mockMvc.perform(call(post("/api/v1/users/$bMember/resend-invitation"), a)).andExpect(status().isNotFound)
        mockMvc.perform(call(patch("/api/v1/users/$bMember").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("roleIds" to listOf(roleId(a, "STAFF"))))), a))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `a member without MANAGE_USERS is refused on every route`() {
        val clinic = newClinic()
        val email = uniqueEmail()
        val membership = membershipOf(invite(clinic, email, roleId(clinic, "NURSE")).andExpect(status().isCreated))
        val nurse = subOf(email)

        listOf(
            get("/api/v1/users"),
            post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("email" to uniqueEmail(), "name" to "X", "roleIds" to listOf(roleId(clinic, "NURSE"))))),
            patch("/api/v1/users/$membership").contentType(MediaType.APPLICATION_JSON).content("""{"roleIds":[]}"""),
            post("/api/v1/users/$membership/deactivate"),
            post("/api/v1/users/$membership/resend-invitation"),
        ).forEach { request ->
            mockMvc.perform(call(request, clinic, nurse)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        }
    }
}
