package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.application.MembershipAccess
import com.endoacademy.clinic.platform.application.MembershipAccessReader
import com.endoacademy.clinic.support.DatabaseIT
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Observabilidade sem dado de paciente (T1.13, RF9): spans só com ids, rota e status; log sem CPF nem e-mail. */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@Import(ObservabilityIT.Config::class)
class ObservabilityIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var spans: CollectingSpanExporter

    class CollectingSpanExporter : SpanExporter {
        val exported = CopyOnWriteArrayList<SpanData>()
        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            exported.addAll(spans)
            return CompletableResultCode.ofSuccess()
        }
        override fun flush() = CompletableResultCode.ofSuccess()
        override fun shutdown() = CompletableResultCode.ofSuccess()
    }

    @TestConfiguration
    class Config {
        @Bean
        fun collectingSpanExporter() = CollectingSpanExporter()

        @Bean
        @Primary
        fun fakeMembershipAccessReader() = MembershipAccessReader { _, clinic ->
            if (clinic == clinicId) MembershipAccess(userId, true, true, setOf("VIEW_PATIENTS")) else null
        }
    }

    companion object {
        private val oidc = MockOAuth2Server().also { it.start() }
        val clinicId: UUID = UUID.randomUUID()
        val userId: UUID = UUID.randomUUID()

        // Valores que NUNCA podem aparecer em log nem em span.
        private const val CPF = "529.982.247-25"
        private const val CPF_DIGITS = "52998224725"
        private const val EMAIL = "maria.silva@example.com"
        private const val NAME = "Maria da Silva"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { oidc.issuerUrl("clinic").toString() }
            registry.add("management.tracing.sampling.probability") { "1.0" }
            registry.add("management.opentelemetry.tracing.export.schedule-delay") { "50ms" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = oidc.shutdown()

        private fun bearer() = "Bearer " + oidc.issueToken(
            "clinic", "client", DefaultOAuth2TokenCallback("clinic", "user-1", "JWT", listOf("clinic-api"), emptyMap(), 3600),
        ).serialize()
    }

    private fun spansFor(route: String): List<SpanData> {
        await().atMost(Duration.ofSeconds(10)).until { spans.exported.any { it.name.contains(route) } }
        return spans.exported.filter { it.name.contains(route) }
    }

    @Test
    fun `server spans carry only tenant, user, route and status`() {
        mockMvc.perform(
            get("/api/v1/test-clinic").queryParam("cpf", CPF).queryParam("email", EMAIL)
                .header(HttpHeaders.AUTHORIZATION, bearer()).header("X-Clinic-Id", clinicId.toString()),
        ).andExpect(status().isOk)

        val span = spansFor("/api/v1/test-clinic").first()
        val attributes = span.attributes.asMap().mapKeys { it.key.key }.mapValues { it.value.toString() }

        assertThat(attributes["tenant_id"]).isEqualTo(clinicId.toString())
        assertThat(attributes["user_id"]).isEqualTo(userId.toString())
        assertThat(attributes.keys).describedAs("atributos do span: %s", attributes.keys)
            .allMatch { it in setOf("tenant_id", "user_id") || it in ALLOWED_LOW_CARDINALITY }
        assertThat(attributes.values.joinToString("|") + span.name).doesNotContain(CPF, CPF_DIGITS, EMAIL, NAME, "cpf=", "email=")
    }

    @Test
    fun `an invalid body with patient data leaves no trace in logs or spans`(output: CapturedOutput) {
        val body = """{"name": "$NAME", "cpf": "$CPF", "email": "$EMAIL", "broken": """

        mockMvc.perform(
            post("/api/v1/test-body").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer()).header("X-Clinic-Id", clinicId.toString()),
        ).andExpect(status().isBadRequest)

        await().atMost(Duration.ofSeconds(10)).until { spans.exported.any { it.name.contains("test-body") } }
        val everything = output.all + spans.exported.joinToString("\n") { span ->
            span.name + span.attributes.asMap().entries.joinToString { "${it.key.key}=${it.value}" } +
                span.events.joinToString { event -> event.name + event.attributes.asMap().values.joinToString() }
        }
        listOf(CPF, CPF_DIGITS, EMAIL, NAME).forEach { assertThat(everything).describedAs("dado sensível '%s'", it).doesNotContain(it) }
    }

    @Test
    fun `a rejected clinic access shows no clinic data either`(output: CapturedOutput) {
        mockMvc.perform(
            get("/api/v1/test-clinic").queryParam("cpf", CPF).header(HttpHeaders.AUTHORIZATION, bearer()).header("X-Clinic-Id", UUID.randomUUID().toString()),
        ).andExpect(status().isForbidden)

        assertThat(output.all).doesNotContain(CPF, CPF_DIGITS)
    }

    @Test
    fun `health endpoints answer without details`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist())
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("UP"))
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("UP"))
    }
}

/** Atributos de baixa cardinalidade e de semântica OTel aceitos; qualquer outro reprova o teste. */
private val ALLOWED_LOW_CARDINALITY = setOf("method", "uri", "status", "outcome", "exception", "error")
