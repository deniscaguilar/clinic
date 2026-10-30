package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.application.MembershipAccess
import com.endoacademy.clinic.platform.application.MembershipAccessReader
import com.endoacademy.clinic.support.DatabaseIT
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Erros em Problem Details (T1.14): `code` estável, content type certo e nenhum eco do valor rejeitado. */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@Import(ProblemDetailsIT.Config::class)
class ProblemDetailsIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun fakeMembershipAccessReader() = MembershipAccessReader { _, clinic ->
            if (clinic == clinicId) MembershipAccess(UUID.randomUUID(), true, true, setOf("VIEW_PATIENTS")) else null
        }
    }

    companion object {
        private val oidc = MockOAuth2Server().also { it.start() }
        val clinicId: UUID = UUID.randomUUID()
        private const val CPF = "529.982.247-25"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { oidc.issuerUrl("clinic").toString() }
        }

        @JvmStatic
        @AfterAll
        fun stop() = oidc.shutdown()

        private fun bearer() = "Bearer " + oidc.issueToken(
            "clinic", "client", DefaultOAuth2TokenCallback("clinic", "user-1", "JWT", listOf("clinic-api"), emptyMap(), 3600),
        ).serialize()
    }

    private fun authed(builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) =
        builder.header(HttpHeaders.AUTHORIZATION, bearer()).header("X-Clinic-Id", clinicId.toString())

    private fun body(builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) =
        mockMvc.perform(builder).andReturn().response.contentAsString

    @Test
    fun `business errors carry a stable code, the right status and problem+json`() {
        mockMvc.perform(authed(get("/api/v1/test-conflict")))
            .andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("CPF_ALREADY_REGISTERED"))
            .andExpect(jsonPath("$.status").value(409))
    }

    @Test
    fun `validation errors list field and rule but never the rejected value`() {
        val json = """{"name": "", "cpf": "$CPF"}"""
        mockMvc.perform(authed(post("/api/v1/test-validate").contentType(MediaType.APPLICATION_JSON).content(json)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[?(@.field == 'cpf')].rule").value("Pattern"))
            .andExpect(jsonPath("$.errors[?(@.field == 'name')].rule").value("NotBlank"))
        assertThat(body(authed(post("/api/v1/test-validate").contentType(MediaType.APPLICATION_JSON).content(json)))).doesNotContain(CPF, "529")
    }

    @Test
    fun `malformed JSON does not echo the payload`() {
        val broken = """{"cpf": "$CPF", "x": """
        mockMvc.perform(authed(post("/api/v1/test-validate").contentType(MediaType.APPLICATION_JSON).content(broken)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
        assertThat(body(authed(post("/api/v1/test-validate").contentType(MediaType.APPLICATION_JSON).content(broken)))).doesNotContain(CPF)
    }

    @Test
    fun `a wrong path parameter names the parameter but not the value`() {
        val request = authed(get("/api/v1/test-uuid/$CPF"))
        mockMvc.perform(request)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
            .andExpect(jsonPath("$.parameter").value("id"))
        assertThat(body(request)).doesNotContain(CPF)
    }

    @Test
    fun `an unexpected failure is a generic 500 and its message reaches neither the response nor the log`(output: CapturedOutput) {
        val response = body(authed(get("/api/v1/test-boom")))

        mockMvc.perform(authed(get("/api/v1/test-boom")))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        assertThat(response).doesNotContain(CPF, "duplicate key")
        assertThat(output.all).doesNotContain(CPF, "duplicate key")
        assertThat(output.all).contains("IllegalStateException")
    }

    @Test
    fun `unknown route, wrong method and unsupported media type have their own codes`() {
        mockMvc.perform(authed(get("/api/v1/does-not-exist"))).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
        mockMvc.perform(authed(delete("/api/v1/test-conflict"))).andExpect(status().isMethodNotAllowed).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        mockMvc.perform(authed(post("/api/v1/test-validate").contentType(MediaType.TEXT_PLAIN).content("x")))
            .andExpect(status().isUnsupportedMediaType).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
    }

    @Test
    fun `401 and 403 from the security chain use the same format`() {
        mockMvc.perform(get("/api/v1/test-conflict"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))

        mockMvc.perform(get("/api/v1/test-conflict").header(HttpHeaders.AUTHORIZATION, bearer()).header("X-Clinic-Id", UUID.randomUUID().toString()))
            .andExpect(status().isForbidden)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("CLINIC_ACCESS_DENIED"))
    }
}
