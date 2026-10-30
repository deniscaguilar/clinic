package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.notifications.application.ListNotifications
import com.endoacademy.clinic.notifications.application.NotificationFilter
import com.endoacademy.clinic.notifications.application.NotificationQueryStore
import com.endoacademy.clinic.notifications.application.NotificationView
import com.endoacademy.clinic.notifications.application.Transactions
import com.endoacademy.clinic.notifications.domain.NotificationChannel
import com.endoacademy.clinic.notifications.domain.NotificationKind
import com.endoacademy.clinic.notifications.domain.NotificationStatus
import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** A lista de notificações da clínica (T5.10): canal, status e tentativas, nada de destinatário nem texto. */
class ListNotificationsTest {

    private class FakeQueryStore : NotificationQueryStore {
        val rows = mutableListOf<NotificationView>()
        var lastFilter: NotificationFilter? = null
        override fun list(filter: NotificationFilter, offset: Int, limit: Int): List<NotificationView> {
            lastFilter = filter
            return filtered(filter).drop(offset).take(limit)
        }

        override fun count(filter: NotificationFilter) = filtered(filter).size.toLong()

        private fun filtered(filter: NotificationFilter) = rows
            .filter { filter.status == null || it.status == filter.status }
            .filter { filter.channel == null || it.channel == filter.channel }
            .filter { filter.patientId == null || it.patientId == filter.patientId }
            .sortedByDescending { it.createdAt }
    }

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<AuditEvent>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += event
        }
    }

    private val clinic = UUID.randomUUID()
    private val viewer = CurrentUser(UUID.randomUUID(), "sub-1", clinic, setOf("VIEW_NOTIFICATIONS"))
    private val outsider = CurrentUser(UUID.randomUUID(), "sub-2", clinic, setOf("VIEW_PATIENTS"))
    private val store = FakeQueryStore()
    private val audit = RecordingAudit()
    private val authorizer = object : Authorizer {
        override fun require(user: CurrentUser, permission: String) {
            if (!user.has(permission)) throw PermissionDeniedException(permission)
        }
    }
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private val list = ListNotifications(authorizer, store, audit, transactions)
    private val t0 = Instant.parse("2026-10-30T12:00:00Z")
    private val patient = UUID.randomUUID()

    private fun view(
        channel: NotificationChannel = NotificationChannel.EMAIL,
        status: NotificationStatus = NotificationStatus.SENT,
        patientId: UUID = patient,
        minutes: Long = 0,
        attempts: Int = 1,
    ) = NotificationView(UUID.randomUUID(), patientId, NotificationKind.CONSENT_ACCEPTED, channel, status, attempts, null, null, t0.plusSeconds(minutes * 60)).also { store.rows += it }

    private fun assertBadRequest(block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(ErrorKind.BAD_REQUEST)
            assertThat(it.code).isEqualTo("VALIDATION_FAILED")
        }
    }

    @Test
    fun `lists the notifications, newest first, with the total, the page and the size`() {
        val old = view(minutes = 0)
        val newer = view(minutes = 10)
        val newest = view(minutes = 20)
        val page = list.list(viewer, NotificationFilter(), null, null)
        assertThat(page.items.map { it.id }).containsExactly(newest.id, newer.id, old.id)
        assertThat(page.total).isEqualTo(3)
        assertThat(page.page).isZero()
        assertThat(page.size).isEqualTo(20)
    }

    @Test
    fun `filters by status, channel and patient, alone or together`() {
        val other = UUID.randomUUID()
        view(NotificationChannel.EMAIL, NotificationStatus.SENT, minutes = 1)
        val whatsAppSent = view(NotificationChannel.WHATSAPP, NotificationStatus.SENT, minutes = 2)
        val emailBlocked = view(NotificationChannel.EMAIL, NotificationStatus.BLOCKED_NO_CONSENT, minutes = 3)
        val ofOther = view(NotificationChannel.EMAIL, NotificationStatus.SENT, patientId = other, minutes = 4)

        assertThat(list.list(viewer, NotificationFilter(status = NotificationStatus.BLOCKED_NO_CONSENT), null, null).items.map { it.id }).containsExactly(emailBlocked.id)
        assertThat(list.list(viewer, NotificationFilter(channel = NotificationChannel.WHATSAPP), null, null).items.map { it.id }).containsExactly(whatsAppSent.id)
        assertThat(list.list(viewer, NotificationFilter(patientId = other), null, null).items.map { it.id }).containsExactly(ofOther.id)
        assertThat(list.list(viewer, NotificationFilter(status = NotificationStatus.SENT, channel = NotificationChannel.EMAIL, patientId = patient), null, null).total).isEqualTo(1)
        assertThat(list.list(viewer, NotificationFilter(status = NotificationStatus.FAILED), null, null).items).isEmpty()
        assertThat(store.lastFilter).isEqualTo(NotificationFilter(status = NotificationStatus.FAILED))
    }

    @Test
    fun `pages the result`() {
        val all = (0..4).map { view(minutes = it.toLong()) }.reversed()
        val first = list.list(viewer, NotificationFilter(), 0, 2)
        val second = list.list(viewer, NotificationFilter(), 1, 2)
        val third = list.list(viewer, NotificationFilter(), 2, 2)
        assertThat(first.items.map { it.id }).containsExactly(all[0].id, all[1].id)
        assertThat(second.items.map { it.id }).containsExactly(all[2].id, all[3].id)
        assertThat(third.items.map { it.id }).containsExactly(all[4].id)
        assertThat(third.total).isEqualTo(5)
    }

    @Test
    fun `refuses a negative page and a size outside 1 to 100, and accepts the limits`() {
        assertBadRequest { list.list(viewer, NotificationFilter(), -1, 10) }
        assertBadRequest { list.list(viewer, NotificationFilter(), 0, 0) }
        assertBadRequest { list.list(viewer, NotificationFilter(), 0, 101) }
        assertThat(list.list(viewer, NotificationFilter(), 0, 100).size).isEqualTo(100)
        assertThat(list.list(viewer, NotificationFilter(), 0, 1).size).isEqualTo(1)
        assertThat(audit.events).hasSize(2)
    }

    @Test
    fun `needs VIEW_NOTIFICATIONS and audits nothing without it`() {
        view()
        assertThatThrownBy { list.list(outsider, NotificationFilter(), null, null) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `each listing is one audit event with the count and no patient data`() {
        view(minutes = 1)
        view(minutes = 2)
        list.list(viewer, NotificationFilter(patientId = patient), 0, 1)
        val event = audit.events.single()
        assertThat(event.action).isEqualTo("LIST_NOTIFICATIONS")
        assertThat(event.entityType).isEqualTo("NOTIFICATION")
        assertThat(event.entityId).isNull()
        assertThat(event.details).containsExactly("count.1")
        assertThat(event.toString()).doesNotContain(patient.toString())
    }

    @Test
    fun `the view holds ids, status and counters only`() {
        assertThat(NotificationView::class.java.declaredFields.map { it.name })
            .containsExactlyInAnyOrder("id", "patientId", "kind", "channel", "status", "attempts", "nextAttemptAt", "lastErrorCode", "createdAt")
    }
}
