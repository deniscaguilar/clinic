package com.endoacademy.clinic.notifications.application

import com.endoacademy.clinic.notifications.domain.Notification
import com.endoacademy.clinic.notifications.domain.NotificationChannel
import com.endoacademy.clinic.notifications.domain.NotificationStatus
import com.endoacademy.clinic.notifications.domain.RetryPolicy
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.TenantScope
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/** Totais de uma execução. `clinicErrors` conta as clínicas em que a execução quebrou (as outras seguiram). */
data class DispatchResult(val sent: Int = 0, val blocked: Int = 0, val retrying: Int = 0, val failed: Int = 0, val clinicErrors: Int = 0) {
    operator fun plus(other: DispatchResult) =
        DispatchResult(sent + other.sent, blocked + other.blocked, retrying + other.retrying, failed + other.failed, clinicErrors + other.clinicErrors)
}

/**
 * O worker de envio (ADR-0011). Percorre as clínicas **ativas** e, em cada uma, pega as notificações pendentes e devidas
 * **uma por transação** (`FOR UPDATE SKIP LOCKED`): dois workers nunca enviam a mesma. Antes de enviar confere o
 * consentimento de mensagens (sem ele, `BLOCKED_NO_CONSENT`, nada sai, CA17); resolve o contato e o texto na hora, pela
 * `registry.api`; e registra o resultado. Falha de envio: backoff exponencial até `FAILED` (CA18). A entrega é
 * *pelo menos uma vez*: se o processo cair entre enviar e gravar, a linha volta a ficar pendente.
 */
class DispatchPending(
    private val clinics: ClinicDirectory,
    private val store: NotificationStore,
    private val contacts: PatientContactLookup,
    private val consent: MessagingConsentLookup,
    private val consents: ConsentInfoLookup,
    private val email: EmailSender,
    private val whatsApp: WhatsAppSender,
    private val audit: AuditTrail,
    private val transactions: Transactions,
    private val clock: Clock,
    private val policy: RetryPolicy = RetryPolicy(),
    private val maxPerClinic: Int = 50,
) {
    private val log = LoggerFactory.getLogger(DispatchPending::class.java)

    init {
        require(maxPerClinic >= 1) { "maxPerClinic precisa ser pelo menos 1" }
    }

    fun run(): DispatchResult {
        var total = DispatchResult()
        for (clinic in clinics.activeClinicIds()) {
            total += try {
                dispatchClinic(clinic)
            } catch (exception: RuntimeException) {
                // só o id da clínica e a classe do erro: a mensagem pode citar dado de paciente
                log.error("Falha ao processar as notificações da clínica {}: {}", clinic, exception.javaClass.name)
                DispatchResult(clinicErrors = 1)
            }
        }
        return total
    }

    private fun dispatchClinic(clinic: UUID): DispatchResult {
        var result = DispatchResult()
        repeat(maxPerClinic) {
            val step = TenantScope.runAs(clinic) { transactions.inTransaction { processOne(clinic) } } ?: return result
            result += step
        }
        return result
    }

    private enum class Outcome { SENT, BLOCKED, RETRYING, FAILED }

    private fun processOne(clinic: UUID): DispatchResult? {
        val now = clock.instant()
        val notification = store.claimDue(now) ?: return null
        val (updated, outcome) = attempt(notification, now)
        store.save(updated)
        audit.recordPlatform(
            null,
            clinic,
            AuditEvent(
                "SEND_NOTIFICATION",
                "NOTIFICATION",
                notification.id.toString(),
                when (outcome) {
                    Outcome.SENT -> AuditOutcome.SUCCESS
                    Outcome.BLOCKED -> AuditOutcome.DENIED
                    Outcome.RETRYING, Outcome.FAILED -> AuditOutcome.FAILURE
                },
                details = setOf(notification.channel.name.lowercase()),
            ),
        )
        return when (outcome) {
            Outcome.SENT -> DispatchResult(sent = 1)
            Outcome.BLOCKED -> DispatchResult(blocked = 1)
            Outcome.RETRYING -> DispatchResult(retrying = 1)
            Outcome.FAILED -> DispatchResult(failed = 1)
        }
    }

    private fun attempt(notification: Notification, now: java.time.Instant): Pair<Notification, Outcome> {
        if (!consent.hasActive(notification.patientId)) return notification.block() to Outcome.BLOCKED
        val contact = contacts.find(notification.patientId) ?: return permanent(notification, "CONTACT_UNAVAILABLE")
        val destination = when (notification.channel) {
            NotificationChannel.EMAIL -> contact.email
            NotificationChannel.WHATSAPP -> contact.whatsapp
        } ?: return permanent(notification, "CONTACT_UNAVAILABLE")
        val info = consents.find(notification.patientId, notification.referenceId) ?: return permanent(notification, "CONSENT_NOT_FOUND")
        val message = MessageTemplates.build(notification.kind, info)
        return try {
            when (notification.channel) {
                NotificationChannel.EMAIL -> email.send(OutgoingEmail(destination, message.subject, message.body))
                NotificationChannel.WHATSAPP -> whatsApp.send(OutgoingWhatsApp(destination, message.body))
            }
            notification.markSent() to Outcome.SENT
        } catch (exception: NotificationSendException) {
            if (exception.retryable) retry(notification, exception.code, now) else permanent(notification, exception.code)
        } catch (_: RuntimeException) {
            retry(notification, "SEND_ERROR", now)
        }
    }

    private fun permanent(notification: Notification, code: String) = notification.failPermanently(code) to Outcome.FAILED

    private fun retry(notification: Notification, code: String, now: java.time.Instant): Pair<Notification, Outcome> {
        val next = notification.recordFailure(now, code, policy)
        return next to if (next.status == NotificationStatus.FAILED) Outcome.FAILED else Outcome.RETRYING
    }
}
