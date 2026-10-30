package com.endoacademy.clinic.notifications.config

import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.api.PermissionDefinition
import org.springframework.stereotype.Component

/** Permissões do `notifications`. O `CLINIC_ADMIN` recebe todas; nenhum outro perfil-modelo recebe esta por padrão. */
@Component
class NotificationsPermissions : PermissionContributor {
    override fun permissions() = listOf(
        PermissionDefinition("VIEW_NOTIFICATIONS", "Consultar o andamento dos envios de mensagens (canal, status e tentativas, sem destinatário nem texto)"),
    )
}
