package com.endoacademy.clinic.platform.adapter.`in`.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import tools.jackson.databind.json.JsonMapper

/**
 * 401 e 403 da cadeia de segurança no mesmo formato de erro da API. O cabeçalho `WWW-Authenticate` fica sem
 * `error_description`, que repete detalhes do token.
 */
class ProblemJsonSecurityHandlers(private val json: JsonMapper) : AuthenticationEntryPoint, AccessDeniedHandler {

    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
    }

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) {
        write(response, HttpStatus.FORBIDDEN, "FORBIDDEN")
    }

    private fun write(response: HttpServletResponse, status: HttpStatus, code: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(json.writeValueAsString(mapOf("title" to status.reasonPhrase, "status" to status.value(), "code" to code)))
    }
}
