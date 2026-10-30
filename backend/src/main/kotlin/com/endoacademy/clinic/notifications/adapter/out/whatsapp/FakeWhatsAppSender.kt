package com.endoacademy.clinic.notifications.adapter.out.whatsapp

import com.endoacademy.clinic.notifications.application.NotificationSendException
import com.endoacademy.clinic.notifications.application.OutgoingWhatsApp
import com.endoacademy.clinic.notifications.application.WhatsAppSender
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/** O que o falso registrou de um envio: **só o texto**. O destinatário nunca é guardado nem logado. */
data class SentWhatsApp(val body: String)

/**
 * Adaptador falso de WhatsApp: o provedor e o custo ainda não foram decididos (pergunta em aberto do M0). Registra o
 * texto enviado, sem o destinatário, e escreve em log apenas que houve um envio. Permite simular falhas (`failWith`).
 */
class FakeWhatsAppSender : WhatsAppSender {
    private val log = LoggerFactory.getLogger(FakeWhatsAppSender::class.java)
    private val messages = CopyOnWriteArrayList<SentWhatsApp>()

    @Volatile
    private var failure: NotificationSendException? = null

    @Volatile
    private var failuresLeft = 0

    val sent: List<SentWhatsApp> get() = messages.toList()

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
    override fun send(message: OutgoingWhatsApp) {
        val pending = failure
        if (pending != null && failuresLeft > 0) {
            failuresLeft--
            throw NotificationSendException(pending.code, pending.retryable)
        }
        messages += SentWhatsApp(message.body)
        log.info("WhatsApp (falso): mensagem registrada, {} no total", messages.size)
    }
}
