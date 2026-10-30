package com.endoacademy.clinic.platform.api

/**
 * Uma permissão do catálogo. `code` é textual e estável (ex.: `MANAGE_USERS`).
 * `platformOnly`: exclusiva do `PLATFORM_ADMIN`, nunca atribuível a perfil de clínica.
 */
data class PermissionDefinition(val code: String, val description: String, val platformOnly: Boolean = false)

/**
 * Cada módulo declara as suas permissões e as concessões padrão dos perfis-modelo (plano, seção 5).
 * O `platform` monta o catálogo no startup a partir de todos os contribuidores e não conhece os módulos em compilação.
 */
interface PermissionContributor {
    fun permissions(): List<PermissionDefinition>

    /** Perfil-modelo (`DOCTOR`, `NURSE`, `MANAGER`, `STAFF`) -> códigos concedidos. O `CLINIC_ADMIN` recebe todas as permissões de clínica. */
    fun defaultGrants(): Map<String, Set<String>> = emptyMap()
}
