package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.api.CurrentUser
import io.micrometer.common.KeyValue
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.web.filter.ServerHttpObservationFilter

/**
 * Marca a observação HTTP da requisição (que vira o span do servidor) com a clínica e o usuário: ids, nunca nomes
 * nem documentos. Passa pelo [AllowListObservationFilter], que só deixa esses dois atributos de alta cardinalidade.
 */
class ObservationTagger {
    fun tag(request: HttpServletRequest, user: CurrentUser) {
        ServerHttpObservationFilter.findObservationContext(request).ifPresent { context: ServerRequestObservationContext ->
            context.addHighCardinalityKeyValue(KeyValue.of(AllowListObservationFilter.TENANT_ID, user.clinicId.toString()))
            context.addHighCardinalityKeyValue(KeyValue.of(AllowListObservationFilter.USER_ID, user.userId.toString()))
        }
    }
}
