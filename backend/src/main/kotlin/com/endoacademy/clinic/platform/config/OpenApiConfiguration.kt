package com.endoacademy.clinic.platform.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Contrato da API (plano, seção 6): gerado do código, versionado em `backend/openapi/openapi.yaml` e comparado no
 * build (`OpenApiContractIT`). O cliente TypeScript do front é gerado dele. Mantido determinístico: sem URL de
 * servidor dependente da requisição e sem versão do artefato.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(Info().title("Sistema da Clínica API").version("v1"))
        .servers(listOf(Server().url("/")))
        .components(
            Components().addSecuritySchemes(
                "bearerAuth",
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList("bearerAuth"))

    /** `X-Clinic-Id` é obrigatório fora de `/me`, `/me/clinics` e `/admin` (mesma regra do `ClinicContextFilter`). */
    @Bean
    fun clinicHeaderCustomizer() = GlobalOpenApiCustomizer { openApi ->
        openApi.paths?.forEach { (path, item) ->
            val relative = path.removePrefix("/api/v1")
            val exempt = relative == "/me" || relative == "/me/clinics" || relative == "/admin" || relative.startsWith("/admin/")
            if (!exempt) {
                item.readOperations().forEach { operation ->
                    operation.addParametersItem(
                        HeaderParameter().name("X-Clinic-Id").required(true).description("Clínica ativa").schema(StringSchema().format("uuid")),
                    )
                }
            }
        }
    }
}
