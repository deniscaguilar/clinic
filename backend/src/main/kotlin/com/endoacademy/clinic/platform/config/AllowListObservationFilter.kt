package com.endoacademy.clinic.platform.config

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationFilter
import org.springframework.stereotype.Component

/**
 * Plano, seção 7: os spans levam só `tenant_id`, `user_id`, rota e status. Todo atributo de alta cardinalidade que o
 * Spring acrescenta por conta própria (URL completa com query, cliente, user agent...) é removido, porque URL e query
 * podem carregar dado de paciente. Os de baixa cardinalidade (método, rota em modelo, status, resultado) ficam.
 */
@Component
class AllowListObservationFilter : ObservationFilter {

    override fun map(context: Observation.Context): Observation.Context {
        context.highCardinalityKeyValues
            .map { it.key }
            .filterNot { it in ALLOWED_HIGH_CARDINALITY }
            .forEach { context.removeHighCardinalityKeyValue(it) }
        return context
    }

    companion object {
        const val TENANT_ID = "tenant_id"
        const val USER_ID = "user_id"
        private val ALLOWED_HIGH_CARDINALITY = setOf(TENANT_ID, USER_ID)
    }
}
