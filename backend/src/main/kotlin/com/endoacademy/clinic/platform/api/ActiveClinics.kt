package com.endoacademy.clinic.platform.api

import java.util.UUID

/**
 * Ids das clínicas **ativas**, para trabalhos de fundo (o worker de notificações) que precisam varrer todas as clínicas.
 * A RLS só mostra a clínica do contexto, então o trabalho percorre estes ids e abre `TenantScope.runAs(id)` em cada um.
 * Só devolve ids: nome, dados e usuários continuam fechados. Não precisa de contexto nem de transação do chamador.
 */
interface ActiveClinics {
    fun ids(): List<UUID>
}
