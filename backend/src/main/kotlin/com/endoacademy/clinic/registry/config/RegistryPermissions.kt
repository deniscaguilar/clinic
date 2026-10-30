package com.endoacademy.clinic.registry.config

import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.api.PermissionDefinition
import org.springframework.stereotype.Component

/** Permissões do `registry`. O `CLINIC_ADMIN` recebe todas; as concessões dos demais perfis-modelo seguem o plano (seção 7). */
@Component
class RegistryPermissions : PermissionContributor {
    override fun permissions() = listOf(
        PermissionDefinition("MANAGE_PROCEDURES", "Criar, editar e inativar procedimentos da clínica"),
        PermissionDefinition("MANAGE_PRACTITIONERS", "Cadastrar, editar e inativar profissionais e seus horários de trabalho"),
        PermissionDefinition("VIEW_PATIENTS", "Consultar pacientes (cada leitura é auditada)"),
        PermissionDefinition("MANAGE_PATIENTS", "Cadastrar e editar dados cadastrais de pacientes"),
        PermissionDefinition("CHANGE_PATIENT_CPF", "Alterar o CPF de um paciente já cadastrado"),
        PermissionDefinition("MANAGE_CONSENT_TYPES", "Criar, revisar e importar tipos de consentimento da clínica"),
        PermissionDefinition("RECORD_CONSENT", "Registrar o aceite e a revogação de consentimento de pacientes"),
    )

    override fun defaultGrants() = mapOf(
        "DOCTOR" to setOf("VIEW_PATIENTS"),
        "NURSE" to setOf("VIEW_PATIENTS"),
        "MANAGER" to setOf("VIEW_PATIENTS"),
        "STAFF" to setOf("VIEW_PATIENTS", "MANAGE_PATIENTS", "RECORD_CONSENT"),
    )
}
