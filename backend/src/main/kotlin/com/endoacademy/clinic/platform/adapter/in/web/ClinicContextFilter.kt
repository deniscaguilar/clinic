package com.endoacademy.clinic.platform.adapter.`in`.web

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.platform.application.ClinicAccessResult
import com.endoacademy.clinic.platform.application.ResolveClinicAccess
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver
import java.util.UUID

/**
 * Lê `X-Clinic-Id`, valida contra o vínculo ativo do usuário e a clínica `ACTIVE`, e executa o resto da requisição
 * dentro de `TenantScope.runAs(clínica)`. O resultado fica no atributo [CURRENT_USER] da requisição.
 *
 * Só atua nas rotas sob `/api/v1`, exceto `/me`, `/me/clinics` e as de `/admin` (plano, seção 6). Roda depois da autenticação.
 * As falhas passam pelos `@ExceptionHandler`, para sair no formato de erro da API.
 */
class ClinicContextFilter(
    private val resolveClinicAccess: ResolveClinicAccess,
    private val exceptionResolver: HandlerExceptionResolver,
    private val onResolved: (HttpServletRequest, CurrentUser) -> Unit = { _, _ -> },
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        if (!path.startsWith(API_PREFIX)) return true
        val relative = path.removePrefix(API_PREFIX)
        return relative == "/me" || relative == "/me/clinics" || relative.startsWith("/admin/") || relative == "/admin"
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        if (authentication == null) { // sem token: a cadeia de segurança já respondeu 401
            chain.doFilter(request, response)
            return
        }
        val user = try {
            resolve(authentication.name, request.getHeader(HEADER))
        } catch (exception: ApiException) {
            exceptionResolver.resolveException(request, response, null, exception)
            return
        }
        request.setAttribute(CURRENT_USER, user)
        onResolved(request, user)
        TenantScope.runAs(user.clinicId) { chain.doFilter(request, response) }
    }

    private fun resolve(userSub: String, header: String?): CurrentUser {
        if (header.isNullOrBlank()) throw ApiException(ErrorKind.BAD_REQUEST, "CLINIC_HEADER_REQUIRED")
        val clinicId = runCatching { UUID.fromString(header.trim()) }.getOrNull()
            ?: throw ApiException(ErrorKind.BAD_REQUEST, "CLINIC_HEADER_INVALID")
        return when (val result = resolveClinicAccess.resolve(userSub, clinicId)) {
            is ClinicAccessResult.Granted -> result.user
            is ClinicAccessResult.Denied -> throw ApiException(ErrorKind.FORBIDDEN, "CLINIC_ACCESS_DENIED")
        }
    }

    companion object {
        const val HEADER = "X-Clinic-Id"
        const val CURRENT_USER = CurrentUser.REQUEST_ATTRIBUTE
        private const val API_PREFIX = "/api/v1"
    }
}
