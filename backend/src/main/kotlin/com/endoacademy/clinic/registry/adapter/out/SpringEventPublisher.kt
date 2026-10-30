package com.endoacademy.clinic.registry.adapter.out

import com.endoacademy.clinic.registry.application.EventPublisher
import org.springframework.context.ApplicationEventPublisher

/**
 * Publica pelo `ApplicationEventPublisher`, dentro da transação do chamador. O registro de eventos do Spring Modulith
 * (schema `eventing`) grava uma publicação por ouvinte na mesma transação e a marca como concluída quando o ouvinte termina.
 */
class SpringEventPublisher(private val publisher: ApplicationEventPublisher) : EventPublisher {
    override fun publish(event: Any) = publisher.publishEvent(event)
}
