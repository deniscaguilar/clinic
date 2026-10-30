package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.application.AuditFilter
import com.endoacademy.clinic.platform.application.AuditQueryStore
import com.endoacademy.clinic.platform.application.AuditRecord
import com.endoacademy.clinic.platform.application.PageOf
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.util.UUID

/** Consulta `platform.audit_event` na transação do chamador; a RLS mostra só a clínica ativa. */
class JdbcAuditQueryStore(private val jdbc: JdbcTemplate, private val json: JsonMapper) : AuditQueryStore {

    override fun query(filter: AuditFilter, page: Int, size: Int): PageOf<AuditRecord> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        filter.from?.let { conditions += "occurred_at >= ?"; args += Timestamp.from(it) }
        filter.to?.let { conditions += "occurred_at < ?"; args += Timestamp.from(it) }
        filter.userId?.let { conditions += "user_id = ?"; args += it }
        filter.action?.let { conditions += "action = ?"; args += it }
        filter.entityType?.let { conditions += "entity_type = ?"; args += it }
        val where = if (conditions.isEmpty()) "" else "where " + conditions.joinToString(" and ")

        val total = jdbc.queryForObject("select count(*) from platform.audit_event $where", Long::class.java, *args.toTypedArray()) ?: 0L
        val items = jdbc.query(
            """select id, occurred_at, user_id, action, entity_type, entity_id, outcome, details::text as details
               from platform.audit_event $where
               order by occurred_at desc, id desc
               limit ? offset ?""",
            { rs, _ ->
                AuditRecord(
                    id = rs.getObject("id", UUID::class.java),
                    occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                    userId = rs.getObject("user_id", UUID::class.java),
                    action = rs.getString("action"),
                    entityType = rs.getString("entity_type"),
                    entityId = rs.getString("entity_id"),
                    outcome = rs.getString("outcome"),
                    details = json.readValue(rs.getString("details"), Array<String>::class.java).toList(),
                )
            },
            *args.toTypedArray(),
            size,
            page.toLong() * size,
        )
        return PageOf(items, page, size, total)
    }
}
