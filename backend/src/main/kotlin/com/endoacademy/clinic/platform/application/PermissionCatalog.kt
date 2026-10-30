package com.endoacademy.clinic.platform.application

import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.api.PermissionDefinition

/** Catálogo de permissões montado no startup. Inconsistência entre contribuidores derruba a subida. */
class PermissionCatalog(contributors: List<PermissionContributor>) {

    private val definitions: Map<String, PermissionDefinition>

    /** Perfil-modelo -> permissões concedidas por padrão (inclui o `CLINIC_ADMIN`). */
    val defaultGrants: Map<String, Set<String>>

    init {
        val byCode = linkedMapOf<String, PermissionDefinition>()
        contributors.flatMap { it.permissions() }.forEach { definition ->
            val existing = byCode.putIfAbsent(definition.code, definition)
            require(existing == null || existing == definition) { "Permissão ${definition.code} declarada duas vezes com definições diferentes" }
        }
        definitions = byCode

        val grants = linkedMapOf<String, MutableSet<String>>()
        contributors.forEach { contributor ->
            contributor.defaultGrants().forEach { (role, codes) ->
                require(role != CLINIC_ADMIN) { "O perfil $CLINIC_ADMIN recebe todas as permissões de clínica; não declare concessões para ele" }
                codes.forEach { code ->
                    require(code in byCode) { "Concessão do perfil $role cita a permissão desconhecida $code" }
                    require(!byCode.getValue(code).platformOnly) { "A permissão $code é exclusiva da plataforma e não pode ser concedida ao perfil $role" }
                }
                grants.getOrPut(role) { linkedSetOf() }.addAll(codes)
            }
        }
        grants[CLINIC_ADMIN] = byCode.values.filterNot { it.platformOnly }.mapTo(linkedSetOf()) { it.code }
        defaultGrants = grants
    }

    fun all(): List<PermissionDefinition> = definitions.values.toList()

    fun contains(code: String): Boolean = code in definitions

    /** Permissões que podem entrar em perfil de clínica (todas menos as exclusivas da plataforma). */
    fun assignable(): Set<String> = definitions.values.filterNot { it.platformOnly }.mapTo(linkedSetOf()) { it.code }

    /** Valida os códigos de um perfil: desconhecida ou exclusiva da plataforma é rejeitada. */
    fun validateAssignable(codes: Collection<String>) {
        val unknown = codes.filterNot { it in definitions }
        require(unknown.isEmpty()) { "Permissões desconhecidas: ${unknown.sorted()}" }
        val platformOnly = codes.filter { definitions.getValue(it).platformOnly }
        require(platformOnly.isEmpty()) { "Permissões exclusivas da plataforma: ${platformOnly.sorted()}" }
    }

    companion object {
        const val CLINIC_ADMIN = "CLINIC_ADMIN"
    }
}
