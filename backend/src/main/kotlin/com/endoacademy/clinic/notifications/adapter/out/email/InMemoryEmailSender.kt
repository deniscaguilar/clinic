package com.endoacademy.clinic.notifications.adapter.out.email

import com.endoacademy.clinic.notifications.application.EmailSender
import com.endoacademy.clinic.notifications.application.NotificationSendException
import com.endoacademy.clinic.notifications.application.OutgoingEmail
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Guarda os e-mails em memória, **inclusive o destinatário**, para os testes conferirem o que seria enviado. Só para
 * teste e desenvolvimento (`clinic.notifications.email.provider=memory`): em produção o provedor é `ses`. Não registra
 * nada em log. Permite simular falhas (`failWith`) para provar a retentativa (CA18).
 */
class InMemoryEmailSender : EmailSender {
    private val messages = CopyOnWriteArrayList<OutgoingEmail>()

    @Volatile
    private var failure: NotificationSendException? = null

    @Volatile
    private var failuresLeft = 0

    val sent: List<OutgoingEmail> get() = messages.toList()

    /** Faz os próximos `times` envios falharem com [exception]; depois volta a enviar. */
    @Synchronized
    fun failWith(exception: NotificationSendException, times: Int = 1) {
        failure = exception
        failuresLeft = times
    }

    @Synchronized
    fun clear() {
        messages.clear()
        failure = null
        failuresLeft = 0
    }

    @Synchronized
    override fun send(email: OutgoingEmail) {
        val pending = failure
        if (pending != null && failuresLeft > 0) {
            failuresLeft--
            throw NotificationSendException(pending.code, pending.retryable)
        }
        messages += email
    }
}
