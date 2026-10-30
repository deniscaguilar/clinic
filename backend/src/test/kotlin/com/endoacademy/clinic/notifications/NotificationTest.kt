package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.notifications.domain.Notification
import com.endoacademy.clinic.notifications.domain.NotificationChannel
import com.endoacademy.clinic.notifications.domain.NotificationException
import com.endoacademy.clinic.notifications.domain.NotificationKind
import com.endoacademy.clinic.notifications.domain.NotificationStatus
import com.endoacademy.clinic.notifications.domain.RetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class NotificationTest {

    private val t0 = Instant.parse("2026-10-30T10:00:00Z")
    private val id = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val patient = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val reference = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val policy = RetryPolicy(maxAttempts = 4, baseDelay = Duration.ofMinutes(1), maxDelay = Duration.ofMinutes(5))

    private fun assertCode(code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(NotificationException::class.java) { assertThat(it.code).isEqualTo(code) }
    }

    private fun pending(channel: NotificationChannel = NotificationChannel.EMAIL) = Notification.create(id, patient, reference, NotificationKind.CONSENT_ACCEPTED, channel, t0)

    // --- política de retentativa ---

    @Test
    fun `the retry delay doubles after each failed attempt and stops growing at the cap`() {
        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofMinutes(1))
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofMinutes(2))
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofMinutes(4))
        assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofMinutes(5))
        assertThat(policy.delayAfter(30)).isEqualTo(Duration.ofMinutes(5))
        assertThat(policy.delayAfter(10_000)).isEqualTo(Duration.ofMinutes(5))
    }

    @Test
    fun `the default policy tries a few times with minutes of backoff`() {
        val default = RetryPolicy()
        assertThat(default.maxAttempts).isEqualTo(5)
        assertThat(default.delayAfter(1)).isEqualTo(Duration.ofMinutes(1))
        assertThat(default.delayAfter(5)).isLessThanOrEqualTo(Duration.ofHours(1))
    }

    @Test
    fun `a policy needs at least one attempt, a positive delay and a cap not below the base`() {
        assertThatThrownBy { RetryPolicy(maxAttempts = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RetryPolicy(baseDelay = Duration.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RetryPolicy(baseDelay = Duration.ofMinutes(-1)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RetryPolicy(baseDelay = Duration.ofMinutes(10), maxDelay = Duration.ofMinutes(5)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { policy.delayAfter(0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- Notification ---

    @Test
    fun `a new notification is pending, due now, with no attempts and no error`() {
        val n = pending(NotificationChannel.WHATSAPP)
        assertThat(n.id).isEqualTo(id)
        assertThat(n.patientId).isEqualTo(patient)
        assertThat(n.referenceId).isEqualTo(reference)
        assertThat(n.kind).isEqualTo(NotificationKind.CONSENT_ACCEPTED)
        assertThat(n.channel).isEqualTo(NotificationChannel.WHATSAPP)
        assertThat(n.status).isEqualTo(NotificationStatus.PENDING)
        assertThat(n.attempts).isZero()
        assertThat(n.nextAttemptAt).isEqualTo(t0)
        assertThat(n.lastErrorCode).isNull()
    }

    @Test
    fun `a sent notification counts the attempt and is no longer due`() {
        val sent = pending().markSent()
        assertThat(sent.status).isEqualTo(NotificationStatus.SENT)
        assertThat(sent.attempts).isEqualTo(1)
        assertThat(sent.nextAttemptAt).isNull()
        assertThat(sent.lastErrorCode).isNull()
    }

    @Test
    fun `a failure schedules the next attempt with exponential backoff and keeps the notification pending`() {
        val first = pending().recordFailure(t0, "SMTP_TIMEOUT", policy)
        assertThat(first.status).isEqualTo(NotificationStatus.PENDING)
        assertThat(first.attempts).isEqualTo(1)
        assertThat(first.nextAttemptAt).isEqualTo(t0.plus(Duration.ofMinutes(1)))
        assertThat(first.lastErrorCode).isEqualTo("SMTP_TIMEOUT")

        val secondAt = t0.plusSeconds(90)
        val second = first.recordFailure(secondAt, "SMTP_TIMEOUT", policy)
        assertThat(second.attempts).isEqualTo(2)
        assertThat(second.nextAttemptAt).isEqualTo(secondAt.plus(Duration.ofMinutes(2)))

        val third = second.recordFailure(secondAt.plusSeconds(200), "OTHER_ERROR", policy)
        assertThat(third.attempts).isEqualTo(3)
        assertThat(third.nextAttemptAt).isEqualTo(secondAt.plusSeconds(200).plus(Duration.ofMinutes(4)))
        assertThat(third.lastErrorCode).isEqualTo("OTHER_ERROR")
    }

    @Test
    fun `after the last allowed attempt the notification fails for good`() {
        var n = pending()
        repeat(3) { n = n.recordFailure(t0, "E", policy) }
        assertThat(n.status).isEqualTo(NotificationStatus.PENDING)
        val failed = n.recordFailure(t0, "E", policy)
        assertThat(failed.status).isEqualTo(NotificationStatus.FAILED)
        assertThat(failed.attempts).isEqualTo(4)
        assertThat(failed.nextAttemptAt).isNull()
        assertThat(failed.lastErrorCode).isEqualTo("E")
    }

    @Test
    fun `a single attempt policy fails at the first failure`() {
        val failed = pending().recordFailure(t0, "E", RetryPolicy(maxAttempts = 1))
        assertThat(failed.status).isEqualTo(NotificationStatus.FAILED)
    }

    @Test
    fun `the error code is a short stable code, never free text that could carry personal data`() {
        listOf("", " ", "erro com espaço", "maria@example.com", "a".repeat(65), "com-hífen", "minúscula").forEach {
            assertCode("NOTIFICATION_ERROR_CODE_INVALID") { pending().recordFailure(t0, it, policy) }
        }
        assertThat(pending().recordFailure(t0, "A".repeat(64), policy).lastErrorCode).hasSize(64)
        assertThat(pending().recordFailure(t0, "HTTP_503", policy).lastErrorCode).isEqualTo("HTTP_503")
    }

    @Test
    fun `a permanent failure ends the notification at once, whatever the attempts left`() {
        val failed = pending().failPermanently("EMAIL_REJECTED")
        assertThat(failed.status).isEqualTo(NotificationStatus.FAILED)
        assertThat(failed.attempts).isEqualTo(1)
        assertThat(failed.nextAttemptAt).isNull()
        assertThat(failed.lastErrorCode).isEqualTo("EMAIL_REJECTED")

        val afterRetry = pending().recordFailure(t0, "EMAIL_UNAVAILABLE", policy).failPermanently("EMAIL_REJECTED")
        assertThat(afterRetry.status).isEqualTo(NotificationStatus.FAILED)
        assertThat(afterRetry.attempts).isEqualTo(2)
        assertThat(afterRetry.lastErrorCode).isEqualTo("EMAIL_REJECTED")
        assertCode("NOTIFICATION_ERROR_CODE_INVALID") { pending().failPermanently("maria@example.com") }
        assertCode("NOTIFICATION_NOT_PENDING") { pending().markSent().failPermanently("E") }
        assertCode("NOTIFICATION_NOT_PENDING") { pending().cancel().failPermanently("E") }
    }

    @Test
    fun `without consent the notification is blocked, not sent and not retried`() {
        val blocked = pending().block()
        assertThat(blocked.status).isEqualTo(NotificationStatus.BLOCKED_NO_CONSENT)
        assertThat(blocked.nextAttemptAt).isNull()
        assertThat(blocked.attempts).isZero()
    }

    @Test
    fun `only a pending notification can be cancelled`() {
        val cancelled = pending().cancel()
        assertThat(cancelled.status).isEqualTo(NotificationStatus.CANCELLED)
        assertThat(cancelled.nextAttemptAt).isNull()
        assertCode("NOTIFICATION_NOT_PENDING") { cancelled.cancel() }
        assertCode("NOTIFICATION_NOT_PENDING") { pending().markSent().cancel() }
        assertCode("NOTIFICATION_NOT_PENDING") { pending().block().cancel() }
    }

    @Test
    fun `a notification that is not pending cannot be sent, failed or blocked`() {
        val sent = pending().markSent()
        val cancelled = pending().cancel()
        listOf(sent, cancelled, pending().block()).forEach { n ->
            assertCode("NOTIFICATION_NOT_PENDING") { n.markSent() }
            assertCode("NOTIFICATION_NOT_PENDING") { n.recordFailure(t0, "E", policy) }
            assertCode("NOTIFICATION_NOT_PENDING") { n.block() }
        }
    }

    @Test
    fun `a notification is due only while pending and once its time has come`() {
        val n = pending()
        assertThat(n.isDue(t0)).isTrue()
        assertThat(n.isDue(t0.minusSeconds(1))).isFalse()
        val retry = n.recordFailure(t0, "E", policy)
        assertThat(retry.isDue(t0.plusSeconds(59))).isFalse()
        assertThat(retry.isDue(t0.plusSeconds(60))).isTrue()
        assertThat(n.markSent().isDue(t0)).isFalse()
        assertThat(n.cancel().isDue(t0)).isFalse()
    }

    @Test
    fun `due depends on the status too, even for a stored row with an inconsistent time`() {
        listOf(NotificationStatus.SENT, NotificationStatus.FAILED, NotificationStatus.CANCELLED, NotificationStatus.BLOCKED_NO_CONSENT).forEach { status ->
            val stored = Notification.restore(id, patient, reference, NotificationKind.CONSENT_ACCEPTED, NotificationChannel.EMAIL, status, 1, t0, null)
            assertThat(stored.isDue(t0.plusSeconds(10))).describedAs(status.name).isFalse()
        }
    }

    @Test
    fun `a later success clears the error of the earlier failures`() {
        val retried = pending().recordFailure(t0, "SMTP_TIMEOUT", policy)
        assertThat(retried.lastErrorCode).isEqualTo("SMTP_TIMEOUT")
        val sent = retried.markSent()
        assertThat(sent.status).isEqualTo(NotificationStatus.SENT)
        assertThat(sent.attempts).isEqualTo(2)
        assertThat(sent.lastErrorCode).isNull()
    }

    @Test
    fun `a stored notification is restored as it is`() {
        val failed = pending().recordFailure(t0, "E", policy)
        val restored = Notification.restore(failed.id, failed.patientId, failed.referenceId, failed.kind, failed.channel, failed.status, failed.attempts, failed.nextAttemptAt, failed.lastErrorCode)
        assertThat(restored).isEqualTo(failed)
    }

    @Test
    fun `the notification carries ids only and its toString shows no more than that`() {
        val text = pending().toString()
        assertThat(text).contains(id.toString()).doesNotContain("@")
    }
}
