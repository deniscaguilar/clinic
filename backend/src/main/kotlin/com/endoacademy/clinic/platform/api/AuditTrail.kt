package com.endoacademy.clinic.platform.api

import java.util.UUID

enum class AuditOutcome { SUCCESS, DENIED, FAILURE }

/**
 * Registro de auditoria: quem (do [CurrentUser]), quando (o relógio do adaptador), o quê e com que resultado.
 * `details` guarda só **nomes de campos**, nunca valores nem dado de paciente (CLAUDE.md, seção 4).
 */
data class AuditEvent(
    val action: String,
    val entityType: String,
    val entityId: String? = null,
    val outcome: AuditOutcome = AuditOutcome.SUCCESS,
    val details: Set<String> = emptySet(),
) {
    init {
        require(action.isNotBlank() && entityType.isNotBlank()) { "action e entityType são obrigatórios" }
        details.forEach {
            require(FIELD_NAME.matches(it)) { "details aceita só nomes de campos (letras, números, _ e .), nunca valores" }
        }
    }

    private companion object {
        val FIELD_NAME = Regex("[A-Za-z][A-Za-z0-9_.]{0,63}")
    }
}

/** Port de auditoria usado por todos os módulos. A implementação grava no schema `platform` (T1.12). */
interface AuditTrail {
    fun record(user: CurrentUser?, event: AuditEvent)

    /**
     * Ação do `PLATFORM_ADMIN`, que não tem clínica ativa: grava o autor e, se houver, a clínica afetada. O banco só
     * aceita `tenantId` igual ao tenant do contexto da transação (ou nulo). O padrão não grava o autor; o adaptador
     * real sobrescreve.
     */
    fun recordPlatform(actorUserId: UUID?, tenantId: UUID?, event: AuditEvent) = record(null, event)
}
