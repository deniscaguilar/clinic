package com.endoacademy.clinic.platform.api

/** Tipo do erro, independente de HTTP; o adaptador web traduz para o status (plano, seção 6). */
enum class ErrorKind(val httpStatus: Int) {
    BAD_REQUEST(400),
    UNAUTHENTICATED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    PAYLOAD_TOO_LARGE(413),
    UNSUPPORTED_MEDIA_TYPE(415),
    UNPROCESSABLE(422),
    BAD_GATEWAY(502),
}

/**
 * Erro de negócio com `code` estável e em inglês (ex.: `CPF_INVALID`, `CPF_ALREADY_REGISTERED`). A resposta sai em
 * Problem Details (RFC 9457) **sem eco do valor rejeitado**: a mensagem exibida ao usuário vem do i18n do front.
 * A mensagem da exceção é só o código, para nunca carregar dado de paciente.
 */
open class ApiException(val kind: ErrorKind, val code: String) : RuntimeException(code)
