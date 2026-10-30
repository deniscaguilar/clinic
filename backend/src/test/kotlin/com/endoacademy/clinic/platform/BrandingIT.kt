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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Marca da clínica de ponta a ponta (T3.2; CA6, CA7). */
@AutoConfigureMockMvc
class BrandingIT : DatabaseIT() {

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

    private class Clinic(val id: UUID, val name: String, val adminSub: String)

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

    private fun newClinic(name: String): Clinic {
        val email = "admin-${UUID.randomUUID()}@clinica.test"
        val body = mockMvc.perform(
            post("/api/v1/admin/clinics").header(HttpHeaders.AUTHORIZATION, bearer(platformSub)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("name" to name, "adminEmail" to email, "adminName" to "Admin"))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return Clinic(UUID.fromString(json.readTree(body).get("id").asString()), name, UUID.nameUUIDFromBytes(email.toByteArray()).toString())
    }

    private fun call(builder: MockHttpServletRequestBuilder, clinic: Clinic, sub: String = clinic.adminSub) =
        builder.header(HttpHeaders.AUTHORIZATION, bearer(sub)).header("X-Clinic-Id", clinic.id.toString())

    private fun putBranding(clinic: Clinic, name: String, primary: String, secondary: String, sub: String = clinic.adminSub) =
        mockMvc.perform(
            call(put("/api/v1/branding").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("displayName" to name, "primaryColor" to primary, "secondaryColor" to secondary))), clinic, sub),
        )

    private fun memberWithoutBrandingPermission(clinic: Clinic): String {
        val roleId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val sub = "sub-staff-$userId"
        val membershipId = UUID.randomUUID()
        TenantScope.runAsPlatform {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Equipe')", userId, sub, "$userId@example.test")
                jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, 'ACTIVE', ?)", membershipId, clinic.id, userId, sub)
            }
        }
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.role (id, tenant_id, name) values (?, ?, 'Sem marca')", roleId, clinic.id)
                jdbc.update("insert into platform.membership_role (tenant_id, membership_id, role_id) values (?, ?, ?)", clinic.id, membershipId, roleId)
            }
        }
        return sub
    }

    @Test
    fun `a new clinic starts with a default brand named after it`() {
        val clinic = newClinic("Clínica da DCA")

        mockMvc.perform(call(get("/api/v1/branding"), clinic))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.displayName").value("Clínica da DCA"))
            .andExpect(jsonPath("$.onPrimary").value("#FFFFFF"))
            .andExpect(jsonPath("$.logoUrl").doesNotExist())
        val rows = TenantScope.runAs(clinic.id) { tx.execute { jdbc.queryForObject("select count(*) from platform.tenant_branding", Int::class.java) } }
        assertThat(rows).isEqualTo(1)
    }

    @Test
    fun `each clinic has its own colors (CA6) and the session carries them`() {
        val green = newClinic("Clínica Verde")
        val blue = newClinic("Clínica Azul")

        putBranding(green, "Clínica Verde", "#198038", "#DEFBE6").andExpect(status().isOk).andExpect(jsonPath("$.primaryColor").value("#198038")).andExpect(jsonPath("$.onPrimary").value("#FFFFFF"))
        putBranding(blue, "Clínica Azul", "#0F62FE", "#D0E2FF").andExpect(status().isOk)

        mockMvc.perform(call(get("/api/v1/branding"), green)).andExpect(jsonPath("$.primaryColor").value("#198038")).andExpect(jsonPath("$.displayName").value("Clínica Verde"))
        mockMvc.perform(call(get("/api/v1/branding"), blue)).andExpect(jsonPath("$.primaryColor").value("#0F62FE")).andExpect(jsonPath("$.displayName").value("Clínica Azul"))
        mockMvc.perform(call(get("/api/v1/session"), green))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.branding.primaryColor").value("#198038"))
            .andExpect(jsonPath("$.branding.secondaryColor").value("#DEFBE6"))
            .andExpect(jsonPath("$.branding.onPrimary").value("#FFFFFF"))
    }

    @Test
    fun `a primary color with insufficient contrast is rejected with a stable code (CA7) and the brand is unchanged`() {
        val clinic = newClinic("Clínica")
        putBranding(clinic, "Clínica", "#198038", "#6B7280").andExpect(status().isOk)

        putBranding(clinic, "Clínica", "#FFFF00", "#6B7280")
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.code").value("BRAND_CONTRAST_INSUFFICIENT"))
        putBranding(clinic, "Clínica", "verde", "#6B7280").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COLOR_INVALID"))
        putBranding(clinic, "Clínica", "#198038", "").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        mockMvc.perform(call(get("/api/v1/branding"), clinic)).andExpect(jsonPath("$.primaryColor").value("#198038"))
    }

    @Test
    fun `only who has MANAGE_BRANDING changes the brand, any member reads it`() {
        val clinic = newClinic("Clínica")
        val staff = memberWithoutBrandingPermission(clinic)

        mockMvc.perform(call(get("/api/v1/branding"), clinic, staff)).andExpect(status().isOk)
        putBranding(clinic, "Invasor", "#198038", "#6B7280", sub = staff).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        mockMvc.perform(call(get("/api/v1/branding"), clinic)).andExpect(jsonPath("$.displayName").value("Clínica"))
    }

    @Test
    fun `updating the brand is audited and the audit holds only field names`() {
        val clinic = newClinic("Clínica")
        putBranding(clinic, "Marca Secreta", "#198038", "#6B7280").andExpect(status().isOk)

        val rows = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForList("select details::text from platform.audit_event where action = 'UPDATE_BRANDING'", String::class.java) }!!
        }
        assertThat(rows).hasSize(1)
        assertThat(rows.single()).doesNotContain("Marca Secreta").doesNotContain("#198038")
    }
}
