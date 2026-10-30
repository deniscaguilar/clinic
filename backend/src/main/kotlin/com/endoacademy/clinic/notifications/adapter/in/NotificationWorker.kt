package com.endoacademy.clinic.notifications.adapter.`in`

import com.endoacademy.clinic.notifications.application.DispatchPending
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/**
 * Dispara o envio de tempos em tempos (`clinic.notifications.worker.interval`, padrão 30 s). `fixedDelay` impede que duas
 * execuções se sobreponham numa instância; entre instâncias quem protege é o `SKIP LOCKED`. Uma falha nunca derruba o
 * agendador. Classe aberta porque `@Scheduled` pede proxy.
 */
open class NotificationWorker(private val dispatch: DispatchPending) {
    private val log = LoggerFactory.getLogger(NotificationWorker::class.java)

    @Scheduled(fixedDelayString = "\${clinic.notifications.worker.interval}", initialDelayString = "\${clinic.notifications.worker.initial-delay}")
    open fun tick() {
        try {
            val result = dispatch.run()
            if (result.sent + result.blocked + result.retrying + result.failed + result.clinicErrors > 0) {
                log.info("Notificações: {} enviadas, {} bloqueadas, {} para nova tentativa, {} falhas, {} clínicas com erro", result.sent, result.blocked, result.retrying, result.failed, result.clinicErrors)
            }
        } catch (exception: RuntimeException) {
            log.error("Falha no ciclo de envio de notificações: {}", exception.javaClass.name)
        }
    }
}
