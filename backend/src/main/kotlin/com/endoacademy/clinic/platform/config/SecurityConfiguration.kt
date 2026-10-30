package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.adapter.`in`.web.ClinicContextFilter
import com.endoacademy.clinic.platform.adapter.`in`.web.ProblemJsonSecurityHandlers
import com.endoacademy.clinic.platform.application.ResolveClinicAccess
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.servlet.HandlerExceptionResolver
import tools.jackson.databind.json.JsonMapper

/**
 * Resource server OIDC genérico (ADR-0003, plano 7): o emissor, a chave pública (JWKS) e a audiência vêm de
 * `spring.security.oauth2.resourceserver.jwt.*`; o Spring valida assinatura, `iss`, `exp` e `aud`.
 * O token só identifica (`sub`); a autorização fica no banco da aplicação.
 *
 * Sem sessão e sem cookie, então CSRF não se aplica (o navegador não anexa o token sozinho).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfiguration {

    @Bean
    fun observationTagger() = ObservationTagger()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        corsConfigurationSource: CorsConfigurationSource,
        resolveClinicAccess: ResolveClinicAccess,
        observationTagger: ObservationTagger,
        json: JsonMapper,
        @Qualifier("handlerExceptionResolver") exceptionResolver: HandlerExceptionResolver,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .cors { it.configurationSource(corsConfigurationSource) }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint(ProblemJsonSecurityHandlers(json))
                it.accessDeniedHandler(ProblemJsonSecurityHandlers(json))
            }
            .oauth2ResourceServer {
                it.jwt(Customizer.withDefaults())
                it.authenticationEntryPoint(ProblemJsonSecurityHandlers(json))
                it.accessDeniedHandler(ProblemJsonSecurityHandlers(json))
            }
            .addFilterAfter(ClinicContextFilter(resolveClinicAccess, exceptionResolver, observationTagger::tag), BearerTokenAuthenticationFilter::class.java)
            .headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives("default-src 'none'; frame-ancestors 'none'") }
                headers.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                headers.frameOptions { it.deny() }
            }
            .build()

    /** CORS restrito às origens do app; nada de `*`. */
    @Bean
    fun corsConfigurationSource(@Value("\${clinic.security.cors.allowed-origins}") allowedOrigins: List<String>): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            this.allowedOrigins = allowedOrigins
            allowedMethods = listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS)
                .map { it.name() }
            allowedHeaders = listOf(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-Clinic-Id")
            maxAge = 3600
            allowCredentials = false
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", configuration) }
    }
}
