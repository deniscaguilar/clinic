package com.endoacademy.clinic.platform.api

import java.util.UUID

/**
 * Leitura mínima da pertença à clínica para outros módulos (plano, seção 14): só responde se o usuário tem vínculo
 * **ativo** com a clínica. Não cria login, não convida e não concede perfil; isso é do `InviteUser`.
 * Roda na transação do chamador, já no contexto de tenant da requisição.
 */
interface ClinicMembers {
    fun isActiveMember(clinicId: UUID, userId: UUID): Boolean
}
