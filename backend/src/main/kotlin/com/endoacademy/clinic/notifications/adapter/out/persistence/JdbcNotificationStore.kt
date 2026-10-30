package com.endoacademy.clinic.notifications.adapter.out.persistence

import com.endoacademy.clinic.notifications.application.NotificationFilter
import com.endoacademy.clinic.notifications.application.NotificationQueryStore
import com.endoacademy.clinic.notifications.application.NotificationStore
import com.endoacademy.clinic.notifications.application.NotificationView
import com.endoacademy.clinic.notifications.domain.Notification
import com.endoacademy.clinic.notifications.domain.NotificationChannel
import com.endoacademy.clinic.notifications.domain.NotificationKind
import com.endoacademy.clinic.notifications.domain.NotificationStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Fila de notificações da clínica do contexto; a RLS por tenant limita todas as consultas. Roda na transação do chamador. */
class JdbcNotificationStore(private val jdbc: JdbcTemplate) : NotificationStore, NotificationQueryStore {

    override fun insertIfAbsent(tenantId: UUID, notification: Notification): Boolean =
        jdbc.update(
            """insert into notifications.notification
               (id, tenant_id, patient_id, reference_id, kind, channel, status, attempts, next_attempt_at, last_error_code)
               values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               on conflict (tenant_id, reference_id, channel, kind) do nothing""",
            notification.id,
            tenantId,
            notification.patientId,
            notification.referenceId,
            notification.kind.name,
            notification.channel.name,
            notification.status.name,
            notification.attempts,
            notification.nextAttemptAt?.let { Timestamp.from(it) },
            notification.lastErrorCode,
        ) > 0

    override fun cancelPending(patientId: UUID): Int =
        jdbc.update(
            "update notifications.notification set status = 'CANCELLED', next_attempt_at = null, updated_at = now() where patient_id = ? and status = 'PENDING'",
            patientId,
        )

    override fun claimDue(now: Instant): Notification? =
        jdbc.query(
            """select id, patient_id, reference_id, kind, channel, status, attempts, next_attempt_at, last_error_code
               from notifications.notification
               where status = 'PENDING' and next_attempt_at <= ?
               order by next_attempt_at, id
               limit 1
               for update skip locked""",
            { rs, _ ->
                Notification.restore(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("patient_id", UUID::class.java),
                    rs.getObject("reference_id", UUID::class.java),
                    NotificationKind.valueOf(rs.getString("kind")),
                    NotificationChannel.valueOf(rs.getString("channel")),
                    NotificationStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getTimestamp("next_attempt_at")?.toInstant(),
                    rs.getString("last_error_code"),
                )
            },
            Timestamp.from(now),
        ).firstOrNull()

    override fun save(notification: Notification) {
        jdbc.update(
            "update notifications.notification set status = ?, attempts = ?, next_attempt_at = ?, last_error_code = ?, updated_at = now() where id = ?",
            notification.status.name,
            notification.attempts,
            notification.nextAttemptAt?.let { Timestamp.from(it) },
            notification.lastErrorCode,
            notification.id,
        )
    }

    override fun list(filter: NotificationFilter, offset: Int, limit: Int): List<NotificationView> =
        jdbc.query(
            """select id, patient_id, kind, channel, status, attempts, next_attempt_at, last_error_code, created_at
               from notifications.notification
               where $FILTER
               order by created_at desc, id
               offset ? limit ?""",
            { rs, _ ->
                NotificationView(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("patient_id", UUID::class.java),
                    NotificationKind.valueOf(rs.getString("kind")),
                    NotificationChannel.valueOf(rs.getString("channel")),
                    NotificationStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getTimestamp("next_attempt_at")?.toInstant(),
                    rs.getString("last_error_code"),
                    rs.getTimestamp("created_at").toInstant(),
                )
            },
            filter.status?.name, filter.status?.name, filter.channel?.name, filter.channel?.name, filter.patientId, filter.patientId, offset, limit,
        )

    override fun count(filter: NotificationFilter): Long =
        jdbc.queryForObject(
            "select count(*) from notifications.notification where $FILTER",
            Long::class.java,
            filter.status?.name, filter.status?.name, filter.channel?.name, filter.channel?.name, filter.patientId, filter.patientId,
        ) ?: 0L

    private companion object {
        const val FILTER = "(cast(? as text) is null or status = ?) and (cast(? as text) is null or channel = ?) and (cast(? as uuid) is null or patient_id = cast(? as uuid))"
    }
}
