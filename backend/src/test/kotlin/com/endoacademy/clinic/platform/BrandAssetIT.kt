package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.storage.InMemoryFileStorage
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.DatabaseIT
import com.endoacademy.clinic.support.TestImages
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** `PUT /branding/logo|favicon` de ponta a ponta, com o armazenamento em memória (T3.4). */
@AutoConfigureMockMvc
class BrandAssetIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var json: JsonMapper

    @Autowired
    lateinit var files: InMemoryFileStorage

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

    private fun send(
        clinic: Clinic,
        path: String,
        bytes: ByteArray,
        filename: String = "arquivo.png",
        contentType: String = "image/png",
        sub: String = clinic.adminSub,
    ): ResultActions = mockMvc.perform(
        multipart(org.springframework.http.HttpMethod.PUT, "/api/v1/branding/$path")
            .file(MockMultipartFile("file", filename, contentType, bytes))
            .header(HttpHeaders.AUTHORIZATION, bearer(sub))
            .header("X-Clinic-Id", clinic.id.toString()),
    )

    private fun staffWithoutPermission(clinic: Clinic): String {
        val userId = UUID.randomUUID()
        val sub = "sub-staff-$userId"
        val membershipId = UUID.randomUUID()
        TenantScope.runAsPlatform {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Equipe')", userId, sub, "$userId@example.test")
                jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, 'ACTIVE', ?)", membershipId, clinic.id, userId, sub)
            }
        }
        return sub
    }

    private fun storedKeys(clinic: Clinic) = files.keys().filter { it.startsWith("branding/${clinic.id}/") }

    @Test
    fun `a valid logo is stored, appears in the brand and in the session`() {
        val clinic = newClinic()

        send(clinic, "logo", TestImages.png(300, 100)).andExpect(status().isOk).andExpect(jsonPath("$.logoUrl").exists())

        val key = storedKeys(clinic).single()
        assertThat(key).startsWith("branding/${clinic.id}/logo-").endsWith(".png")
        assertThat(files.contentType(key)).isEqualTo("image/png")
        mockMvc.perform(get("/api/v1/branding").header(HttpHeaders.AUTHORIZATION, bearer(clinic.adminSub)).header("X-Clinic-Id", clinic.id.toString()))
            .andExpect(jsonPath("$.logoUrl").value(org.hamcrest.Matchers.containsString(key)))
        mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, bearer(clinic.adminSub)).header("X-Clinic-Id", clinic.id.toString()))
            .andExpect(jsonPath("$.branding.logoUrl").value(org.hamcrest.Matchers.containsString(key)))
    }

    @Test
    fun `a lying extension or mime type does not help, the content decides`() {
        val clinic = newClinic()

        send(clinic, "logo", TestImages.svg, filename = "logo.png", contentType = "image/png")
            .andExpect(status().isUnsupportedMediaType).andExpect(jsonPath("$.code").value("IMAGE_TYPE_UNSUPPORTED"))
        send(clinic, "logo", TestImages.html, filename = "logo.png", contentType = "image/png").andExpect(status().isUnsupportedMediaType)
        send(clinic, "logo", TestImages.png(100, 100), filename = "logo.svg", contentType = "image/svg+xml").andExpect(status().isOk)
        send(clinic, "logo", TestImages.jpeg(100, 100), filename = "foto.png", contentType = "image/png").andExpect(status().isOk)

        assertThat(storedKeys(clinic).single()).endsWith(".jpg") // o JPEG que se dizia PNG foi guardado como JPEG e o PNG anterior saiu
    }

    @Test
    fun `big files are refused with 413`() {
        val clinic = newClinic()

        send(clinic, "logo", TestImages.png(64, 64) + ByteArray(1_200_000)).andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
        send(clinic, "favicon", TestImages.png(32, 32) + ByteArray(300_000)).andExpect(status().isPayloadTooLarge)
        assertThat(storedKeys(clinic)).isEmpty()
    }

    @Test
    fun `dimensions and the square favicon are enforced`() {
        val clinic = newClinic()
        send(clinic, "logo", TestImages.png(4000, 100)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("IMAGE_DIMENSIONS_INVALID"))
        send(clinic, "favicon", TestImages.png(64, 32)).andExpect(status().isUnprocessableContent)
        send(clinic, "favicon", TestImages.webpLossless(48, 48)).andExpect(status().isOk).andExpect(jsonPath("$.faviconUrl").exists())
        assertThat(storedKeys(clinic).single()).contains("favicon-").endsWith(".webp")
    }

    @Test
    fun `a request without the file part is a 400`() {
        val clinic = newClinic()
        mockMvc.perform(
            multipart(org.springframework.http.HttpMethod.PUT, "/api/v1/branding/logo")
                .header(HttpHeaders.AUTHORIZATION, bearer(clinic.adminSub)).header("X-Clinic-Id", clinic.id.toString()),
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_REQUIRED"))
    }

    @Test
    fun `only MANAGE_BRANDING uploads and a refused upload leaves no file`() {
        val clinic = newClinic()
        val staff = staffWithoutPermission(clinic)

        send(clinic, "logo", TestImages.png(100, 100), sub = staff).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))

        assertThat(storedKeys(clinic)).isEmpty()
    }

    @Test
    fun `each clinic keeps its files under its own prefix`() {
        val a = newClinic()
        val b = newClinic()
        send(a, "logo", TestImages.png(100, 100)).andExpect(status().isOk)
        send(b, "logo", TestImages.png(120, 120)).andExpect(status().isOk)

        assertThat(storedKeys(a)).hasSize(1)
        assertThat(storedKeys(b)).hasSize(1)
        mockMvc.perform(get("/api/v1/branding").header(HttpHeaders.AUTHORIZATION, bearer(a.adminSub)).header("X-Clinic-Id", a.id.toString()))
            .andExpect(jsonPath("$.logoUrl").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(b.id.toString()))))
    }

    @Test
    fun `changing the colors afterwards keeps the logo`() {
        val clinic = newClinic()
        send(clinic, "logo", TestImages.png(100, 100)).andExpect(status().isOk)

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/branding")
                .header(HttpHeaders.AUTHORIZATION, bearer(clinic.adminSub)).header("X-Clinic-Id", clinic.id.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("displayName" to "Nova", "primaryColor" to "#198038", "secondaryColor" to "#6B7280"))),
        ).andExpect(status().isOk).andExpect(jsonPath("$.logoUrl").exists())
    }
}
