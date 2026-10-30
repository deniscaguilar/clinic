package com.endoacademy.clinic.platform.api

import java.util.UUID

/**
 * Quem está agindo e em qual clínica, já validado contra o vínculo ativo (plano, seção 3).
 * `permissions` são os códigos do catálogo de permissões (ex.: `VIEW_PATIENTS`).
 */
data class CurrentUser(
    val userId: UUID,
    val userSub: String,
    val clinicId: UUID,
    val permissions: Set<String>,
) {
    fun has(permission: String): Boolean = permission in permissions

    companion object {
        /** Atributo da requisição onde o filtro de `X-Clinic-Id` deixa o [CurrentUser]; os controllers de outros módulos o leem daqui. */
        const val REQUEST_ATTRIBUTE = "com.endoacademy.clinic.currentUser"
    }
}
