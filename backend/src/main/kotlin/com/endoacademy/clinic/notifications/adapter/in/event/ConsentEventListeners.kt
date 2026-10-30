package com.endoacademy.clinic.notifications.adapter.`in`.event

import com.endoacademy.clinic.notifications.application.OnConsentAccepted
import com.endoacademy.clinic.notifications.application.OnConsentRevoked
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.registry.api.ConsentAccepted
import com.endoacademy.clinic.registry.api.ConsentRevoked
import org.springframework.scheduling.annotation.Async
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Ouve os eventos de consentimento do `registry`, depois do commit de quem os publicou e fora da requisição. O contexto
 * de clínica vem do `tenantId` do evento e é aberto **antes** de qualquer transação (`TenantScope.runAs`): por isso este
 * ouvinte não usa `@ApplicationModuleListener`, que abre a transação antes de entrar no método. Os ouvintes anotados com
 * `@TransactionalEventListener` também entram no registro de publicações do Spring Modulith (schema `eventing`): se o
 * método falhar, a publicação fica pendente para nova tentativa. Classe aberta porque `@Async` pede proxy.
 */
open class ConsentEventListeners(
    private val onAccepted: OnConsentAccepted,
    private val onRevoked: OnConsentRevoked,
) {

    @Async
    @TransactionalEventListener
    open fun on(event: ConsentAccepted) {
        TenantScope.runAs(event.tenantId) { onAccepted.handle(event.tenantId, event.patientId, event.grantId) }
    }

    @Async
    @TransactionalEventListener
    open fun on(event: ConsentRevoked) {
        TenantScope.runAs(event.tenantId) { onRevoked.handle(event.patientId) }
    }
}
