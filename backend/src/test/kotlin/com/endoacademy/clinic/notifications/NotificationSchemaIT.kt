package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.DatabaseIT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** O esquema da fila no Postgres real: estados coerentes, uma mensagem por aceite e canal, nada se apaga (T5.7). */
class NotificationSchemaIT : DatabaseIT() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val tx get() = TransactionTemplate(transactionManager)

    private fun insert(
        clinic: UUID,
        id: UUID = UUID.randomUUID(),
        reference: UUID = UUID.randomUUID(),
        channel: String = "EMAIL",
        kind: String = "CONSENT_ACCEPTED",
        status: String = "PENDING",
        attempts: Int = 0,
        due: Boolean = status == "PENDING",
        error: String? = null,
    ) = TenantScope.runAs(clinic) {
        tx.executeWithoutResult {
            jdbc.update(
                "insert into notifications.notification (id, tenant_id, patient_id, reference_id, kind, channel, status, attempts, next_attempt_at, last_error_code) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, case when ? then now() end, ?)",
                id, clinic, UUID.randomUUID(), reference, kind, channel, status, attempts, due, error,
            )
        }
    }

    @Test
    fun `a row is pending exactly when it has a time for the next attempt`() {
        val clinic = UUID.randomUUID()
        insert(clinic)
        insert(clinic, status = "SENT", attempts = 1)
        insert(clinic, status = "FAILED", attempts = 5, error = "SMTP_TIMEOUT")
        insert(clinic, status = "CANCELLED")
        insert(clinic, status = "BLOCKED_NO_CONSENT")
        assertThatThrownBy { insert(clinic, status = "PENDING", due = false) }.rootCause().hasMessageContaining("notification_due_only_when_pending")
        listOf("SENT", "FAILED", "CANCELLED", "BLOCKED_NO_CONSENT").forEach {
            assertThatThrownBy { insert(clinic, status = it, due = true) }.describedAs(it).rootCause().hasMessageContaining("notification_due_only_when_pending")
        }
    }

    @Test
    fun `invalid status, channel, kind, attempts and error code are refused`() {
        val clinic = UUID.randomUUID()
        assertThatThrownBy { insert(clinic, status = "QUEUED") }.rootCause().hasMessageContaining("violates check constraint")
        assertThatThrownBy { insert(clinic, channel = "SMS") }.rootCause().hasMessageContaining("violates check constraint")
        assertThatThrownBy { insert(clinic, kind = "PROMOTION") }.rootCause().hasMessageContaining("violates check constraint")
        assertThatThrownBy { insert(clinic, attempts = -1) }.rootCause().hasMessageContaining("violates check constraint")
        listOf("erro com espaço", "maria@example.com", "minúscula", "A".repeat(65)).forEach {
            assertThatThrownBy { insert(clinic, status = "FAILED", due = false, error = it) }.describedAs(it).rootCause().hasMessageContaining("violates check constraint")
        }
    }

    @Test
    fun `one message per acceptance, channel and kind, in each clinic`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val reference = UUID.randomUUID()
        insert(a, reference = reference, channel = "EMAIL")
        insert(a, reference = reference, channel = "WHATSAPP")
        assertThatThrownBy { insert(a, reference = reference, channel = "EMAIL") }.rootCause().hasMessageContaining("notification_one_per_reference_key")
        insert(b, reference = reference, channel = "EMAIL")
    }

    @Test
    fun `the application can read and update the progress but never delete a row`() {
        val clinic = UUID.randomUUID()
        val id = UUID.randomUUID()
        insert(clinic, id = id)
        TenantScope.runAs(clinic) {
            tx.executeWithoutResult { jdbc.update("update notifications.notification set status = 'SENT', attempts = 1, next_attempt_at = null where id = ?", id) }
        }
        assertThat(TenantScope.runAs(clinic) { tx.execute { jdbc.queryForObject("select status from notifications.notification where id = ?", String::class.java, id) } }).isEqualTo("SENT")
        assertThatThrownBy { TenantScope.runAs(clinic) { tx.executeWithoutResult { jdbc.update("delete from notifications.notification where id = ?", id) } } }
            .rootCause().hasMessageContaining("permission denied")
    }
}
