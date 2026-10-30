package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.CurrentUser
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Grava em `platform.audit_event`.
 * - `SUCCESS` entra na **mesma transação** da ação: se a auditoria falhar, a ação é desfeita.
 * - `DENIED` e `FAILURE` entram em transação **própria** (`REQUIRES_NEW`), porque a principal será revertida.
 * O tenant da linha é o da clínica ativa do usuário; o banco recusa se não bater com o contexto da transação.
 */
class JdbcAuditTrail(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    private val json: JsonMapper,
) : AuditTrail {

    private val joining = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED }
    private val separate = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    override fun record(user: CurrentUser?, event: AuditEvent) = write(user?.userId, user?.clinicId, event)

    override fun recordPlatform(actorUserId: UUID?, tenantId: UUID?, event: AuditEvent) = write(actorUserId, tenantId, event)

    private fun write(userId: UUID?, tenantId: UUID?, event: AuditEvent) {
        val template = if (event.outcome == AuditOutcome.SUCCESS) joining else separate
        template.executeWithoutResult { insert(userId, tenantId, event) }
    }

    private fun insert(userId: UUID?, tenantId: UUID?, event: AuditEvent) {
        jdbc.update(
            """insert into platform.audit_event
               (id, tenant_id, occurred_at, user_id, action, entity_type, entity_id, outcome, channel, ip, details)
               values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))""",
            UUID.randomUUID(),
            tenantId,
            Timestamp.from(clock.instant()),
            userId,
            event.action,
            event.entityType,
            event.entityId,
            event.outcome.name,
            CHANNEL_API,
            remoteAddress(),
            json.writeValueAsString(event.details.sorted()),
        )
    }

    private fun remoteAddress(): String? = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.remoteAddr

    private companion object {
        const val CHANNEL_API = "API"
    }
}
