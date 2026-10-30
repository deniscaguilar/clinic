package com.endoacademy.clinic.platform.adapter.`in`.web

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.net.URI

/**
 * Todos os erros da API saem em Problem Details (RFC 9457), com `code` estável e **sem eco do valor rejeitado**
 * (plano, seção 6). Nenhum `detail`, `instance` nem mensagem de exceção é copiado para a resposta: URL, query e
 * mensagens de banco ou de validação podem trazer dado de paciente.
 */
@RestControllerAdvice
class ProblemDetailsAdvice {

    private val log = LoggerFactory.getLogger(ProblemDetailsAdvice::class.java)

    @ExceptionHandler(ApiException::class)
    fun api(exception: ApiException) = problem(HttpStatus.valueOf(exception.kind.httpStatus), exception.code)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable() = problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST")

    /** Só nome do campo e tipo da regra violada; nunca o valor nem a mensagem padrão. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(exception: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> {
        val errors = exception.bindingResult.fieldErrors
            .map { mapOf("field" to it.field, "rule" to (it.code ?: "invalid")) }
            .sortedBy { it["field"] }
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "errors" to errors)
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun invalidParameters(exception: HandlerMethodValidationException): ResponseEntity<ProblemDetail> {
        val errors = exception.parameterValidationResults.map { mapOf("parameter" to (it.methodParameter.parameterName ?: "unknown")) }
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "errors" to errors)
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(exception: MethodArgumentTypeMismatchException) =
        problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "parameter" to exception.name)

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(exception: MissingServletRequestParameterException) =
        problem(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", "parameter" to exception.parameterName)

    @ExceptionHandler(MissingServletRequestPartException::class)
    fun missingPart() = problem(HttpStatus.BAD_REQUEST, "FILE_REQUIRED")

    /** Barrada pelo limite do contêiner antes de chegar ao caso de uso. */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge() = problem(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE")

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound() = problem(HttpStatus.NOT_FOUND, "NOT_FOUND")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun methodNotAllowed() = problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED")

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun unsupportedMediaType() = problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun notAcceptable() = problem(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE")

    /**
     * Qualquer outra falha. Registra só a classe e o ponto de origem: a **mensagem** da exceção pode citar dado de
     * paciente (ex.: "Key (cpf)=(...) already exists" do banco), então não vai para o log nem para a resposta.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(exception: Exception): ResponseEntity<ProblemDetail> {
        // Erros de segurança continuam com a cadeia do Spring Security (401 e 403 próprios).
        if (exception is AccessDeniedException || exception is AuthenticationException) throw exception
        log.error("Erro não tratado: {} em {}", exception.javaClass.name, exception.stackTrace.firstOrNull())
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
    }

    companion object {
        fun problem(status: HttpStatus, code: String, vararg extra: Pair<String, Any>): ResponseEntity<ProblemDetail> {
            val body = ProblemDetail.forStatus(status)
            body.title = status.reasonPhrase
            // Se ficar nulo, o Spring preenche `instance` com a URL da requisição, que pode carregar dado de paciente.
            body.instance = URI.create("about:blank")
            body.setProperty("code", code)
            extra.forEach { (name, value) -> body.setProperty(name, value) }
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body)
        }
    }
}
