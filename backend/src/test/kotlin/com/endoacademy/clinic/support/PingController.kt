package com.endoacademy.clinic.support

import com.endoacademy.clinic.platform.adapter.`in`.web.ClinicContextFilter
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.TenantScope
import jakarta.servlet.http.HttpServletRequest
import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import io.swagger.v3.oas.annotations.Hidden
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Endpoints só de teste, para exercitar autenticação e clínica ativa antes de existir rota de negócio. */
@Hidden
@RestController
class PingController {
    /** Fora de `/api/v1`: só autenticação. */
    @GetMapping("/test/ping")
    fun ping(@AuthenticationPrincipal jwt: Jwt) = mapOf("sub" to jwt.subject)

    /** Exige `X-Clinic-Id`. */
    @GetMapping("/api/v1/test-clinic")
    fun clinic(request: HttpServletRequest): Map<String, Any?> {
        val user = request.getAttribute(ClinicContextFilter.CURRENT_USER) as CurrentUser
        return mapOf("clinicId" to user.clinicId.toString(), "permissions" to user.permissions.sorted(), "tenantInScope" to TenantScope.current()?.tenantId?.toString())
    }

    /** Isento do cabeçalho (rotas de /admin). */
    @GetMapping("/api/v1/admin/test")
    fun admin(@AuthenticationPrincipal jwt: Jwt) = mapOf("sub" to jwt.subject)

    /** Recebe JSON; o teste de observabilidade manda corpo inválido com dado sensível. */
    @PostMapping("/api/v1/test-body")
    fun body(@RequestBody body: Map<String, Any?>) = mapOf("keys" to body.keys.sorted())

    data class Person(@field:NotBlank val name: String?, @field:Pattern(regexp = "\\d{11}") val cpf: String?)

    /** Validação: o teste manda valores inválidos que não podem voltar na resposta. */
    @PostMapping("/api/v1/test-validate")
    fun validate(@Valid @RequestBody person: Person) = mapOf("ok" to true)

    /** Parâmetro de caminho com tipo errado. */
    @GetMapping("/api/v1/test-uuid/{id}")
    fun uuid(@PathVariable id: java.util.UUID) = mapOf("id" to id.toString())

    /** Erro de negócio com código estável. */
    @GetMapping("/api/v1/test-conflict")
    fun conflict(): Nothing = throw ApiException(ErrorKind.CONFLICT, "CPF_ALREADY_REGISTERED")

    /** Falha inesperada cuja mensagem cita dado de paciente (como faria uma exceção do banco). */
    @GetMapping("/api/v1/test-boom")
    fun boom(): Nothing = throw IllegalStateException("duplicate key value violates unique constraint: Key (cpf)=(529.982.247-25) already exists")
}
