package com.endoacademy.clinic.platform.api

import java.util.UUID

/**
 * Exige `PLATFORM_ADMIN` para quem age fora de uma clínica (rotas de administração de outros módulos, como os modelos de
 * consentimento). Identifica pelo `sub` do token. Quem não é recebe 403 `PERMISSION_DENIED` e a negativa é auditada.
 * Devolve o id do usuário para a auditoria da ação. Deve ser chamado **antes** de abrir transação ou escopo, porque a
 * negativa grava a auditoria na sua própria transação.
 */
interface PlatformAdminAccess {
    fun require(actorSub: String): UUID
}
