package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.api.PermissionDefinition
import org.springframework.stereotype.Component

/** Permissões do próprio `platform`. Os outros módulos declaram as suas com o mesmo contrato. */
@Component
class PlatformPermissions : PermissionContributor {
    override fun permissions() = listOf(
        PermissionDefinition("MANAGE_USERS", "Convidar, editar e desativar usuários da clínica"),
        PermissionDefinition("MANAGE_ROLES", "Criar, editar e apagar perfis da clínica"),
        PermissionDefinition("MANAGE_BRANDING", "Alterar nome, cores, logo e favicon da clínica"),
        PermissionDefinition("VIEW_AUDIT_TRAIL", "Consultar a trilha de auditoria da clínica"),
        PermissionDefinition("MANAGE_CLINICS", "Criar, editar, ativar e desativar clínicas", platformOnly = true),
        PermissionDefinition("MANAGE_CONSENT_TEMPLATES", "Manter os modelos de consentimento da plataforma", platformOnly = true),
    )
}
