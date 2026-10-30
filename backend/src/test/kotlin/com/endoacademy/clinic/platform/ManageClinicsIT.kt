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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Editar, desativar, ativar clínica e reenviar convite do 1º admin, de ponta a ponta (T2.5). */
@AutoConfigureMockMvc
class ManageClinicsIT : DatabaseIT() {

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

    /** Administrador da plataforma de teste, criado na primeira vez que é usado (os campos injetados só existem depois do construtor). */
    private val adminSub: String by lazy {
        val id = UUID.randomUUID()
        val sub = "sub-admin-$id"
        TenantScope.runAsPlatform {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Admin')", id, sub, "$id@example.test")
                jdbc.update("insert into platform.platform_admin (user_id) values (?)", id)
            }
        }
        sub
    }

    private class Created(val clinicId: UUID, val membershipId: UUID, val adminSub: String, val email: String)

    private fun createClinic(failIdentity: Boolean = false, name: String = "Clínica Alfa"): Created {
        val email = "admin-${UUID.randomUUID()}@dca.test"
        if (failIdentity) identity.failNext(IdentityProviderException("indisponível"))
        val body = mockMvc.perform(
            post("/api/v1/admin/clinics").header(HttpHeaders.AUTHORIZATION, bearer(adminSub)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("name" to name, "adminEmail" to email, "adminName" to "Admin"))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val tree = json.readTree(body)
        return Created(UUID.fromString(tree.get("id").asString()), UUID.fromString(tree.get("firstAdmin").get("membershipId").asString()), email.let { identityPeek(it) }, email)
    }

    /** O `sub` que o provedor falso daria a este e-mail (determinístico), sem criar o login. */
    private fun identityPeek(email: String) = java.util.UUID.nameUUIDFromBytes(email.toByteArray()).toString()

    private fun admin(request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder, sub: String = adminSub) =
        request.header(HttpHeaders.AUTHORIZATION, bearer(sub))

    private fun session(created: Created) =
        get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, bearer(created.adminSub)).header("X-Clinic-Id", created.clinicId.toString())

    private fun count(clinic: UUID, table: String) =
        TenantScope.runAs(clinic) { tx.execute { jdbc.queryForObject("select count(*) from platform.$table", Int::class.java) }!! }

    @Test
    fun `deactivating blocks every membership and keeps the data, activating brings it back`() {
        val created = createClinic()
        mockMvc.perform(session(created)).andExpect(status().isOk)
        val rolesBefore = count(created.clinicId, "role")
        val membershipsBefore = count(created.clinicId, "membership")

        mockMvc.perform(admin(post("/api/v1/admin/clinics/${created.clinicId}/deactivate"))).andExpect(status().isNoContent)

        mockMvc.perform(session(created)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("CLINIC_ACCESS_DENIED"))
        mockMvc.perform(get("/api/v1/me/clinics").header(HttpHeaders.AUTHORIZATION, bearer(created.adminSub)))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
        assertThat(TenantScope.runAsPlatform { tx.execute { jdbc.queryForObject("select status from platform.clinic where id = ?", String::class.java, created.clinicId) } })
            .isEqualTo("INACTIVE")
        // Nada é apagado: os dados voltam a ser lidos no tenant da clínica (a leitura da clínica pela RLS exige vínculo, não status).
        assertThat(count(created.clinicId, "role")).isEqualTo(rolesBefore)
        assertThat(count(created.clinicId, "membership")).isEqualTo(membershipsBefore)

        mockMvc.perform(admin(post("/api/v1/admin/clinics/${created.clinicId}/activate"))).andExpect(status().isNoContent)
        mockMvc.perform(session(created)).andExpect(status().isOk)
    }

    @Test
    fun `update changes the name that the members see`() {
        val created = createClinic()
        mockMvc.perform(
            admin(patch("/api/v1/admin/clinics/${created.clinicId}").contentType(MediaType.APPLICATION_JSON).content("""{"name":"Clínica Novo Nome"}""")),
        ).andExpect(status().isNoContent)
        mockMvc.perform(session(created)).andExpect(status().isOk).andExpect(jsonPath("$.clinic.name").value("Clínica Novo Nome"))

        mockMvc.perform(admin(patch("/api/v1/admin/clinics/${created.clinicId}").contentType(MediaType.APPLICATION_JSON).content("""{"name":" "}""")))
            .andExpect(status().isBadRequest)
        mockMvc.perform(admin(patch("/api/v1/admin/clinics/${UUID.randomUUID()}").contentType(MediaType.APPLICATION_JSON).content("""{"name":"X"}""")))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CLINIC_NOT_FOUND"))
    }

    @Test
    fun `resend works only for pending memberships and lets the admin in`() {
        val created = createClinic(failIdentity = true)
        mockMvc.perform(session(created)).andExpect(status().isForbidden) // PENDING: ainda sem acesso

        mockMvc.perform(admin(post("/api/v1/admin/clinics/${created.clinicId}/admins/${created.membershipId}/resend-invitation")))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("ACTIVE"))
        mockMvc.perform(session(created)).andExpect(status().isOk).andExpect(jsonPath("$.roles[0]").value("CLINIC_ADMIN"))

        mockMvc.perform(admin(post("/api/v1/admin/clinics/${created.clinicId}/admins/${created.membershipId}/resend-invitation")))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("INVITATION_NOT_PENDING"))
    }

    @Test
    fun `resend with the provider down answers 502 and keeps the membership pending`() {
        val created = createClinic(failIdentity = true)
        identity.failNext(IdentityProviderException("indisponível"))

        mockMvc.perform(admin(post("/api/v1/admin/clinics/${created.clinicId}/admins/${created.membershipId}/resend-invitation")))
            .andExpect(status().isBadGateway).andExpect(jsonPath("$.code").value("IDENTITY_PROVIDER_UNAVAILABLE"))
        assertThat(TenantScope.runAsPlatform { tx.execute { jdbc.queryForObject("select status from platform.membership where id = ?", String::class.java, created.membershipId) } })
            .isEqualTo("PENDING")
    }

    @Test
    fun `resend does not reach memberships of other clinics`() {
        val first = createClinic(failIdentity = true)
        val second = createClinic()
        mockMvc.perform(admin(post("/api/v1/admin/clinics/${second.clinicId}/admins/${first.membershipId}/resend-invitation")))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("MEMBERSHIP_NOT_FOUND"))
    }

    @Test
    fun `a clinic member who is not platform admin is refused on every operation`() {
        val created = createClinic()
        val clinic = created.clinicId
        listOf(
            post("/api/v1/admin/clinics/$clinic/deactivate"),
            post("/api/v1/admin/clinics/$clinic/activate"),
            patch("/api/v1/admin/clinics/$clinic").contentType(MediaType.APPLICATION_JSON).content("""{"name":"Invasor"}"""),
            post("/api/v1/admin/clinics/$clinic/admins/${created.membershipId}/resend-invitation"),
        ).forEach { request ->
            mockMvc.perform(admin(request, sub = created.adminSub)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        }
        mockMvc.perform(session(created)).andExpect(status().isOk).andExpect(jsonPath("$.clinic.name").value("Clínica Alfa"))
    }

    @Test
    fun `list shows the clinics with their status and detail shows the pending administrator that can be resent`() {
        val created = createClinic()

        val listed = json.readTree(
            mockMvc.perform(admin(get("/api/v1/admin/clinics"))).andExpect(status().isOk).andReturn().response.contentAsString,
        )
        val row = listed.first { it.get("id").asString() == created.clinicId.toString() }
        assertThat(row.get("name").asString()).isEqualTo("Clínica Alfa")
        assertThat(row.get("status").asString()).isEqualTo("ACTIVE")

        mockMvc.perform(admin(get("/api/v1/admin/clinics/${created.clinicId}")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.admins.length()").value(1))
            .andExpect(jsonPath("$.admins[0].membershipId").value(created.membershipId.toString()))
            .andExpect(jsonPath("$.admins[0].email").value(created.email))
            .andExpect(jsonPath("$.admins[0].status").value("ACTIVE"))

        mockMvc.perform(admin(post("/api/v1/admin/clinics/${created.clinicId}/deactivate"))).andExpect(status().isNoContent)
        mockMvc.perform(admin(get("/api/v1/admin/clinics/${created.clinicId}"))).andExpect(jsonPath("$.status").value("INACTIVE"))
    }

    @Test
    fun `list is ordered by name regardless of case`() {
        val tag = UUID.randomUUID().toString().take(8)
        val names = listOf("Ord $tag d", "ord $tag B", "Ord $tag a", "ord $tag C")
        names.forEach { createClinic(name = it) }

        val tree = json.readTree(mockMvc.perform(admin(get("/api/v1/admin/clinics"))).andExpect(status().isOk).andReturn().response.contentAsString)
        val listed: List<String> = tree.toList().map { node -> node.get("name").asString() }.filter { name -> name.startsWith("Ord $tag", ignoreCase = true) }

        assertThat(listed).containsExactly("Ord $tag a", "ord $tag B", "ord $tag C", "Ord $tag d")
    }

    @Test
    fun `the detail lists only administrators, not members with other roles`() {
        val created = createClinic()
        val userId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        TenantScope.runAsPlatform {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Médica')", userId, "sub-doctor-$userId", "doctor-$userId@example.test")
                jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, 'ACTIVE', ?)", membershipId, created.clinicId, userId, "sub-doctor-$userId")
            }
        }
        TenantScope.runAs(created.clinicId) {
            tx.executeWithoutResult {
                val doctorRole = jdbc.queryForObject("select id from platform.role where tenant_id = ? and name = 'DOCTOR'", UUID::class.java, created.clinicId)
                jdbc.update("insert into platform.membership_role (tenant_id, membership_id, role_id) values (?, ?, ?)", created.clinicId, membershipId, doctorRole)
            }
        }

        mockMvc.perform(admin(get("/api/v1/admin/clinics/${created.clinicId}")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.admins.length()").value(1))
            .andExpect(jsonPath("$.admins[0].membershipId").value(created.membershipId.toString()))
    }

    @Test
    fun `a pending first administrator appears as PENDING in the detail`() {
        val created = createClinic(failIdentity = true)
        mockMvc.perform(admin(get("/api/v1/admin/clinics/${created.clinicId}")))
            .andExpect(status().isOk).andExpect(jsonPath("$.admins[0].status").value("PENDING"))
    }

    @Test
    fun `reading clinics is for platform admins only and an unknown clinic is 404`() {
        val created = createClinic()
        mockMvc.perform(admin(get("/api/v1/admin/clinics"), created.adminSub)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        mockMvc.perform(admin(get("/api/v1/admin/clinics/${created.clinicId}"), created.adminSub)).andExpect(status().isForbidden)
        mockMvc.perform(admin(get("/api/v1/admin/clinics/${UUID.randomUUID()}")))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CLINIC_NOT_FOUND"))
        mockMvc.perform(get("/api/v1/admin/clinics")).andExpect(status().isUnauthorized)
    }
}
