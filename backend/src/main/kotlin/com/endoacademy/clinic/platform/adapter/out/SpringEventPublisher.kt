package com.endoacademy.clinic.platform.adapter.out

import com.endoacademy.clinic.platform.application.EventPublisher
import org.springframework.context.ApplicationEventPublisher

class SpringEventPublisher(private val publisher: ApplicationEventPublisher) : EventPublisher {
    override fun publish(event: Any) = publisher.publishEvent(event)
}
