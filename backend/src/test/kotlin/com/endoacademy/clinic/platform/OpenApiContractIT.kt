package com.endoacademy.clinic.platform

import com.endoacademy.clinic.support.DatabaseIT
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.nio.file.Files
import java.nio.file.Path

/**
 * O contrato gerado do código tem que ser igual ao versionado em `backend/openapi/openapi.yaml`. Divergiu: o build
 * falha. Para aceitar a mudança, rode `./mvnw verify -Dopenapi.update=true`, revise o diff e versione o arquivo.
 */
@AutoConfigureMockMvc
class OpenApiContractIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    companion object {
        private val oidc = MockOAuth2Server().also { it.start() }
        private val contract: Path = Path.of("openapi", "openapi.yaml")

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

    private fun generated() = mockMvc.perform(get("/v3/api-docs.yaml").header(HttpHeaders.AUTHORIZATION, bearer()))
        .andReturn().response.also { assertThat(it.status).isEqualTo(200) }.getContentAsString(Charsets.UTF_8).trimEnd() + "\n"

    @Test
    fun `the generated contract matches the versioned one`() {
        val actual = generated()
        if (System.getProperty("openapi.update") == "true") {
            Files.createDirectories(contract.parent)
            Files.writeString(contract, actual)
        }
        assertThat(contract).describedAs("contrato versionado (gere com -Dopenapi.update=true)").exists()
        assertThat(actual).describedAs("o contrato mudou: revise e rode ./mvnw verify -Dopenapi.update=true").isEqualTo(Files.readString(contract))
    }

    @Test
    fun `the contract declares bearer auth and hides test endpoints`() {
        val actual = generated()
        assertThat(actual).contains("bearerAuth", "scheme: bearer")
        assertThat(actual).doesNotContain("test-clinic", "test-ping", "test-body")
    }
}
