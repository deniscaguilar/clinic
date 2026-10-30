package com.endoacademy.clinic.platform.adapter.`in`.event

import com.endoacademy.clinic.platform.api.ClinicCreated
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.platform.application.ProvisionBranding
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Cria a marca padrão da clínica nova, sob o tenant do evento. Falha aqui não derruba a criação da clínica: a leitura
 * da marca cai na padrão quando não há linha, e o log traz só o id da clínica.
 */
@Component
class ClinicCreatedListener(private val provisionBranding: ProvisionBranding) {

    private val log = LoggerFactory.getLogger(ClinicCreatedListener::class.java)

    @EventListener
    fun on(event: ClinicCreated) {
        try {
            TenantScope.runAs(event.clinicId) { provisionBranding.provision(event.clinicId) }
        } catch (exception: RuntimeException) {
            log.error("Falha ao criar a marca padrão da clínica {} ({})", event.clinicId, exception.javaClass.simpleName)
        }
    }
}
