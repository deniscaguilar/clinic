package com.endoacademy.clinic.platform

import com.endoacademy.clinic.support.DatabaseIT
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Autenticação OIDC (T1.9, CA3): token válido passa; ausente, inválido, expirado, audiência ou emissor errados dão 401. */
@AutoConfigureMockMvc
class SecurityIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    companion object {
        private val oidc = MockOAuth2Server().also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun oidc(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { oidc.issuerUrl("clinic").toString() }
            registry.add("spring.security.oauth2.resourceserver.jwt.audiences") { "clinic-api" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = oidc.shutdown()

        private fun token(
            issuer: String = "clinic",
            subject: String = "user-1",
            audience: String = "clinic-api",
            expirySeconds: Long = 3600,
        ): String = oidc.issueToken(issuer, "client", DefaultOAuth2TokenCallback(issuer, subject, "JWT", listOf(audience), emptyMap(), expirySeconds)).serialize()
    }

    private val path = "/test/ping"

    @Test
    fun `valid token passes and the subject is the identity`() {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer ${token(subject = "abc-123")}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sub").value("abc-123"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
    }

    @Test
    fun `missing token is 401`() {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `malformed token is 401`() {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `expired token is 401`() {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer ${token(expirySeconds = -120)}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `token for another audience is 401`() {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer ${token(audience = "other-api")}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `token from another issuer is 401`() {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer ${token(issuer = "intruder")}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `health endpoint is public`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
    }

    @Test
    fun `other actuator endpoints require a token`() {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `security headers are present`() {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"))
    }

    @Test
    fun `CORS allows only the configured origin`() {
        mockMvc.perform(
            options(path).header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,x-clinic-id"),
        ).andExpect(status().isOk).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))

        mockMvc.perform(
            options(path).header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"),
        ).andExpect(status().isForbidden)
    }
}
