package com.endoacademy.clinic.notifications.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Violação de regra de notificação, com `code` estável (sem eco de valor). */
class NotificationException(val code: String) : RuntimeException(code)

enum class NotificationChannel { EMAIL, WHATSAPP }

/** O modelo da mensagem. No M0 só a confirmação de aceite de consentimento (RF13). */
enum class NotificationKind { CONSENT_ACCEPTED }

enum class NotificationStatus { PENDING, SENT, FAILED, BLOCKED_NO_CONSENT, CANCELLED }

/**
 * Retentativa com backoff exponencial: depois da tentativa `n` falhar, espera `baseDelay * 2^(n-1)`, limitado a
 * `maxDelay`. Depois de `maxAttempts` tentativas a notificação falha de vez (CA18).
 */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelay: Duration = Duration.ofMinutes(1),
    val maxDelay: Duration = Duration.ofHours(1),
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts precisa ser pelo menos 1" }
        require(!baseDelay.isZero && !baseDelay.isNegative) { "baseDelay precisa ser positivo" }
        require(maxDelay >= baseDelay) { "maxDelay não pode ser menor que baseDelay" }
    }

    /** Espera antes da próxima tentativa, dado quantas tentativas já falharam (1 ou mais). */
    fun delayAfter(failedAttempts: Int): Duration {
        require(failedAttempts >= 1) { "failedAttempts precisa ser pelo menos 1" }
        var delay = baseDelay
        repeat(failedAttempts - 1) {
            if (delay >= maxDelay) return maxDelay
            delay = delay.multipliedBy(2)
        }
        return if (delay > maxDelay) maxDelay else delay
    }
}

/**
 * Intenção de enviar uma mensagem a um paciente. **Só ids**: nem destinatário nem texto são guardados; o envio resolve o
 * contato e monta a mensagem na hora, pela `registry.api` (ADR-0011). `referenceId` é o aceite que originou a mensagem.
 */
@ConsistentCopyVisibility
data class Notification private constructor(
    val id: UUID,
    val patientId: UUID,
    val referenceId: UUID,
    val kind: NotificationKind,
    val channel: NotificationChannel,
    val status: NotificationStatus,
    val attempts: Int,
    val nextAttemptAt: Instant?,
    val lastErrorCode: String?,
) {

    /** Pendente e com a hora de tentar já chegada. */
    fun isDue(now: Instant): Boolean = status == NotificationStatus.PENDING && nextAttemptAt != null && !now.isBefore(nextAttemptAt)

    fun markSent(): Notification {
        requirePending()
        return copy(status = NotificationStatus.SENT, attempts = attempts + 1, nextAttemptAt = null, lastErrorCode = null)
    }

    /** Registra uma tentativa que falhou: agenda a próxima ou, esgotadas as tentativas, falha de vez. */
    fun recordFailure(now: Instant, errorCode: String, policy: RetryPolicy): Notification {
        requirePending()
        if (!ERROR_CODE.matches(errorCode)) throw NotificationException("NOTIFICATION_ERROR_CODE_INVALID")
        val made = attempts + 1
        return if (made >= policy.maxAttempts) {
            copy(status = NotificationStatus.FAILED, attempts = made, nextAttemptAt = null, lastErrorCode = errorCode)
        } else {
            copy(attempts = made, nextAttemptAt = now.plus(policy.delayAfter(made)), lastErrorCode = errorCode)
        }
    }

    /** Falha que não adianta repetir (endereço recusado, contato que sumiu): encerra a notificação de uma vez. */
    fun failPermanently(errorCode: String): Notification {
        requirePending()
        if (!ERROR_CODE.matches(errorCode)) throw NotificationException("NOTIFICATION_ERROR_CODE_INVALID")
        return copy(status = NotificationStatus.FAILED, attempts = attempts + 1, nextAttemptAt = null, lastErrorCode = errorCode)
    }

    /** Sem consentimento de mensagens vigente: não envia e não tenta de novo (CA17). */
    fun block(): Notification {
        requirePending()
        return copy(status = NotificationStatus.BLOCKED_NO_CONSENT, nextAttemptAt = null)
    }

    fun cancel(): Notification {
        requirePending()
        return copy(status = NotificationStatus.CANCELLED, nextAttemptAt = null)
    }

    private fun requirePending() {
        if (status != NotificationStatus.PENDING) throw NotificationException("NOTIFICATION_NOT_PENDING")
    }

    companion object {
        private val ERROR_CODE = Regex("[A-Z0-9_]{1,64}")

        fun create(id: UUID, patientId: UUID, referenceId: UUID, kind: NotificationKind, channel: NotificationChannel, now: Instant): Notification =
            Notification(id, patientId, referenceId, kind, channel, NotificationStatus.PENDING, 0, now, null)

        /** Reconstrói a partir do gravado. */
        fun restore(
            id: UUID,
            patientId: UUID,
            referenceId: UUID,
            kind: NotificationKind,
            channel: NotificationChannel,
            status: NotificationStatus,
            attempts: Int,
            nextAttemptAt: Instant?,
            lastErrorCode: String?,
        ): Notification = Notification(id, patientId, referenceId, kind, channel, status, attempts, nextAttemptAt, lastErrorCode)
    }
}
