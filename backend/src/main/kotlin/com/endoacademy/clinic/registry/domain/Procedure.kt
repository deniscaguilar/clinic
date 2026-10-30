package com.endoacademy.clinic.registry.domain

import java.util.UUID

/** Procedimento oferecido pela clínica (RF6). Não se apaga: sai de uso ficando inativo. */
@ConsistentCopyVisibility
data class Procedure private constructor(val id: UUID, val name: String, val description: String?, val active: Boolean) {

    fun update(name: String, description: String?, active: Boolean): Procedure = of(id, name, description, active)

    companion object {
        private const val MAX_NAME_LENGTH = 120
        private const val MAX_DESCRIPTION_LENGTH = 500

        fun create(id: UUID, name: String, description: String?): Procedure = of(id, name, description, active = true)

        /** Reconstrói a partir do que está gravado; as mesmas regras valem. */
        fun restore(id: UUID, name: String, description: String?, active: Boolean): Procedure = of(id, name, description, active)

        private fun of(id: UUID, name: String, description: String?, active: Boolean): Procedure {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty()) throw RegistryException("PROCEDURE_NAME_REQUIRED")
            if (trimmedName.length > MAX_NAME_LENGTH) throw RegistryException("PROCEDURE_NAME_TOO_LONG")
            val trimmedDescription = description?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmedDescription != null && trimmedDescription.length > MAX_DESCRIPTION_LENGTH) throw RegistryException("PROCEDURE_DESCRIPTION_TOO_LONG")
            return Procedure(id, trimmedName, trimmedDescription, active)
        }
    }
}
