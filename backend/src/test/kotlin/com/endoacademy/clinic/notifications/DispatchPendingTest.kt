package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.notifications.adapter.out.email.InMemoryEmailSender
import com.endoacademy.clinic.notifications.adapter.out.whatsapp.FakeWhatsAppSender
import com.endoacademy.clinic.notifications.application.ClinicDirectory
import com.endoacademy.clinic.notifications.application.ConsentInfo
import com.endoacademy.clinic.notifications.application.ConsentInfoLookup
import com.endoacademy.clinic.notifications.application.ContactDetails
import com.endoacademy.clinic.notifications.application.DispatchPending
import com.endoacademy.clinic.notifications.application.EmailSender
import com.endoacademy.clinic.notifications.application.MessagingConsentLookup
import com.endoacademy.clinic.notifications.application.NotificationSendException
import com.endoacademy.clinic.notifications.application.NotificationStore
import com.endoacademy.clinic.notifications.application.OutgoingEmail
import com.endoacademy.clinic.notifications.application.PatientContactLookup
import com.endoacademy.clinic.notifications.application.Transactions
import com.endoacademy.clinic.notifications.domain.Notification
import com.endoacademy.clinic.notifications.domain.NotificationChannel
import com.endoacademy.clinic.notifications.domain.NotificationKind
import com.endoacademy.clinic.notifications.domain.NotificationStatus
import com.endoacademy.clinic.notifications.domain.RetryPolicy
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** O worker de envio (T5.9): consentimento antes de enviar (CA17), retentativa com backoff (CA18), clínica a clínica. */
class DispatchPendingTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    /** Respeita o contexto de clínica como a RLS: só entrega e grava linhas do tenant do `TenantScope`. */
    private class FakeStore : NotificationStore {
        val rows = linkedMapOf<UUID, Pair<UUID, Notification>>()
        override fun insertIfAbsent(tenantId: UUID, notification: Notification): Boolean {
            rows[notification.id] = tenantId to notification
            return true
        }

        override fun cancelPending(patientId: UUID) = error("não usado")

        override fun claimDue(now: Instant): Notification? {
            val tenant = TenantScope.current()?.tenantId ?: return null
            return rows.values.filter { it.first == tenant && it.second.isDue(now) }.minByOrNull { it.second.nextAttemptAt!! }?.second
        }

        override fun save(notification: Notification) {
            val tenant = TenantScope.current()?.tenantId ?: error("sem clínica no contexto")
            check(rows[notification.id]?.first == tenant) { "gravando linha de outra clínica" }
            rows[notification.id] = tenant to notification
        }

        fun of(tenant: UUID) = rows.values.filter { it.first == tenant }.map { it.second }
    }

    private data class Audited(val actor: UUID?, val tenant: UUID?, val event: AuditEvent)

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<Audited>()
        override fun record(user: CurrentUser?, event: AuditEvent) = error("o worker não age como usuário de clínica")
        override fun recordPlatform(actorUserId: UUID?, tenantId: UUID?, event: AuditEvent) {
            events += Audited(actorUserId, tenantId, event)
        }
    }

    private val clinicA = UUID.randomUUID()
    private val clinicB = UUID.randomUUID()
    private val patient = UUID.randomUUID()
    private val grant = UUID.randomUUID()
    private val t0 = Instant.parse("2026-10-30T12:00:00Z")
    private val clock = MutableClock(t0)
    private val store = FakeStore()
    private val audit = RecordingAudit()
    private val email = InMemoryEmailSender()
    private val whatsApp = FakeWhatsAppSender()
    private val withConsent = mutableSetOf(patient)
    private val contacts = mutableMapOf(patient to ContactDetails("Maria da Silva", "11987654321", "maria@example.com"))
    private val consents = mutableMapOf((patient to grant) to ConsentInfo("Cadastro e mensagens", t0))
    private var clinics = listOf(clinicA)
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private val policy = RetryPolicy(maxAttempts = 3, baseDelay = Duration.ofMinutes(1), maxDelay = Duration.ofMinutes(10))

    private fun dispatcher(maxPerClinic: Int = 50, emailSender: EmailSender = email) = DispatchPending(
        clinics = ClinicDirectory { clinics },
        store = store,
        contacts = PatientContactLookup { contacts[it] },
        consent = MessagingConsentLookup { it in withConsent },
        consents = ConsentInfoLookup { p, g -> consents[p to g] },
        email = emailSender,
        whatsApp = whatsApp,
        audit = audit,
        transactions = transactions,
        clock = clock,
        policy = policy,
        maxPerClinic = maxPerClinic,
    )

    private fun pending(tenant: UUID = clinicA, channel: NotificationChannel = NotificationChannel.EMAIL, patientId: UUID = patient, reference: UUID = grant): Notification {
        val n = Notification.create(UUID.randomUUID(), patientId, reference, NotificationKind.CONSENT_ACCEPTED, channel, t0)
        store.rows[n.id] = tenant to n
        return n
    }

    private fun stored(n: Notification) = store.rows.getValue(n.id).second

    // --- envio (CA16) ---

    @Test
    fun `a pending notification is sent through its channel with the minimum text and marked as sent`() {
        val byEmail = pending(channel = NotificationChannel.EMAIL)
        val byWhatsApp = pending(channel = NotificationChannel.WHATSAPP)
        val result = dispatcher().run()

        assertThat(result.sent).isEqualTo(2)
        assertThat(email.sent).hasSize(1)
        assertThat(email.sent.single().to).isEqualTo("maria@example.com")
        assertThat(email.sent.single().subject).isEqualTo("Confirmação de consentimento")
        assertThat(email.sent.single().body).contains("Cadastro e mensagens").contains("30/10/2026")
        assertThat(whatsApp.sent).hasSize(1)
        assertThat(whatsApp.sent.single().body).isEqualTo(email.sent.single().body)
        listOf(byEmail, byWhatsApp).forEach {
            assertThat(stored(it).status).isEqualTo(NotificationStatus.SENT)
            assertThat(stored(it).attempts).isEqualTo(1)
            assertThat(stored(it).nextAttemptAt).isNull()
        }
    }

    @Test
    fun `the text carries the type name and the date in the clinic's time zone, and nothing else about the patient`() {
        consents[patient to grant] = ConsentInfo("Uso de imagem", Instant.parse("2026-10-31T01:30:00Z")) // 22h30 de 30/10 em São Paulo
        pending()
        dispatcher().run()
        val body = email.sent.single().body
        assertThat(body).contains("Uso de imagem").contains("30/10/2026").doesNotContain("31/10/2026")
        assertThat(body).doesNotContain("Maria").doesNotContain("98765").doesNotContain("maria@example.com")
    }

    @Test
    fun `a sent notification is not sent again by the next run`() {
        pending()
        dispatcher().run()
        val second = dispatcher().run()
        assertThat(second.sent).isZero()
        assertThat(email.sent).hasSize(1)
    }

    @Test
    fun `every send is audited as a system action of the clinic, with no personal data`() {
        val n = pending()
        dispatcher().run()
        val audited = audit.events.single()
        assertThat(audited.actor).isNull()
        assertThat(audited.tenant).isEqualTo(clinicA)
        assertThat(audited.event.action).isEqualTo("SEND_NOTIFICATION")
        assertThat(audited.event.entityType).isEqualTo("NOTIFICATION")
        assertThat(audited.event.entityId).isEqualTo(n.id.toString())
        assertThat(audited.event.outcome).isEqualTo(AuditOutcome.SUCCESS)
        assertThat(audited.event.details).containsExactly("email")
        assertThat(audited.event.toString()).doesNotContain("maria").doesNotContain("98765")
    }

    // --- consentimento (CA17) ---

    @Test
    fun `without a live messaging consent nothing is sent and the notification is blocked`() {
        withConsent.clear()
        val n = pending()
        val result = dispatcher().run()
        assertThat(result.blocked).isEqualTo(1)
        assertThat(result.sent).isZero()
        assertThat(email.sent).isEmpty()
        assertThat(stored(n).status).isEqualTo(NotificationStatus.BLOCKED_NO_CONSENT)
        assertThat(stored(n).attempts).isZero()
        assertThat(stored(n).nextAttemptAt).isNull()
        val audited = audit.events.single().event
        assertThat(audited.action).isEqualTo("SEND_NOTIFICATION")
        assertThat(audited.outcome).isEqualTo(AuditOutcome.DENIED)
    }

    @Test
    fun `a consent revoked after the notification was created blocks it at send time`() {
        val n = pending()
        withConsent.remove(patient) // o paciente revogou entre a criação e o envio
        dispatcher().run()
        assertThat(stored(n).status).isEqualTo(NotificationStatus.BLOCKED_NO_CONSENT)
        assertThat(email.sent).isEmpty()
        assertThat(whatsApp.sent).isEmpty()
    }

    @Test
    fun `a blocked notification is not retried by later runs`() {
        withConsent.clear()
        val n = pending()
        dispatcher().run()
        withConsent += patient
        clock.now = t0.plus(Duration.ofDays(1))
        assertThat(dispatcher().run().sent).isZero()
        assertThat(stored(n).status).isEqualTo(NotificationStatus.BLOCKED_NO_CONSENT)
    }

    // --- falha e retentativa (CA18) ---

    @Test
    fun `a retryable failure keeps it pending, with the next attempt after the backoff`() {
        email.failWith(NotificationSendException("EMAIL_UNAVAILABLE", retryable = true), times = 1)
        val n = pending()
        val first = dispatcher().run()
        assertThat(first.retrying).isEqualTo(1)
        assertThat(stored(n).status).isEqualTo(NotificationStatus.PENDING)
        assertThat(stored(n).attempts).isEqualTo(1)
        assertThat(stored(n).lastErrorCode).isEqualTo("EMAIL_UNAVAILABLE")
        assertThat(stored(n).nextAttemptAt).isEqualTo(t0.plus(Duration.ofMinutes(1)))
        assertThat(audit.events.single().event.outcome).isEqualTo(AuditOutcome.FAILURE)

        // ainda não é a hora
        clock.now = t0.plusSeconds(30)
        assertThat(dispatcher().run().sent).isZero()
        assertThat(stored(n).attempts).isEqualTo(1)

        // chegou a hora: envia e limpa o erro
        clock.now = t0.plusSeconds(61)
        assertThat(dispatcher().run().sent).isEqualTo(1)
        assertThat(stored(n).status).isEqualTo(NotificationStatus.SENT)
        assertThat(stored(n).attempts).isEqualTo(2)
        assertThat(stored(n).lastErrorCode).isNull()
        assertThat(email.sent).hasSize(1)
    }

    @Test
    fun `after the last allowed attempt the notification fails for good`() {
        email.failWith(NotificationSendException("EMAIL_UNAVAILABLE", retryable = true), times = 10)
        val n = pending()
        repeat(2) {
            dispatcher().run()
            clock.now = clock.now.plus(Duration.ofHours(1))
        }
        assertThat(stored(n).status).isEqualTo(NotificationStatus.PENDING)
        assertThat(stored(n).attempts).isEqualTo(2)
        val last = dispatcher().run()
        assertThat(last.failed).isEqualTo(1)
        assertThat(stored(n).status).isEqualTo(NotificationStatus.FAILED)
        assertThat(stored(n).attempts).isEqualTo(3)
        assertThat(stored(n).nextAttemptAt).isNull()
        clock.now = clock.now.plus(Duration.ofDays(1))
        assertThat(dispatcher().run().sent).isZero()
        assertThat(email.sent).isEmpty()
    }

    @Test
    fun `a failure that is not worth repeating ends the notification at once`() {
        email.failWith(NotificationSendException("EMAIL_REJECTED", retryable = false), times = 1)
        val n = pending()
        val result = dispatcher().run()
        assertThat(result.failed).isEqualTo(1)
        assertThat(stored(n).status).isEqualTo(NotificationStatus.FAILED)
        assertThat(stored(n).attempts).isEqualTo(1)
        assertThat(stored(n).lastErrorCode).isEqualTo("EMAIL_REJECTED")
    }

    @Test
    fun `an unexpected error from the sender is treated as a retryable failure with a generic code`() {
        val exploding = object : EmailSender {
            override fun send(email: OutgoingEmail) = throw IllegalStateException("falha para maria@example.com")
        }
        val n = pending()
        dispatcher(emailSender = exploding).run()
        assertThat(stored(n).status).isEqualTo(NotificationStatus.PENDING)
        assertThat(stored(n).lastErrorCode).isEqualTo("SEND_ERROR")
        assertThat(audit.events.single().event.toString()).doesNotContain("maria")
    }

    @Test
    fun `a failing channel does not hold back the other channel of the same acceptance`() {
        email.failWith(NotificationSendException("EMAIL_UNAVAILABLE", retryable = true), times = 1)
        val byEmail = pending(channel = NotificationChannel.EMAIL)
        val byWhatsApp = pending(channel = NotificationChannel.WHATSAPP)
        val result = dispatcher().run()
        assertThat(result.sent).isEqualTo(1)
        assertThat(result.retrying).isEqualTo(1)
        assertThat(stored(byEmail).status).isEqualTo(NotificationStatus.PENDING)
        assertThat(stored(byWhatsApp).status).isEqualTo(NotificationStatus.SENT)
    }

    // --- dados que sumiram ---

    @Test
    fun `a patient that is gone, a channel that was removed or an acceptance that is missing fail for good`() {
        val gone = pending(patientId = UUID.randomUUID())
        withConsent += gone.patientId
        contacts[patient] = ContactDetails("Maria da Silva", "11987654321", null)
        val noEmail = pending(channel = NotificationChannel.EMAIL)
        val noGrant = pending(channel = NotificationChannel.WHATSAPP, reference = UUID.randomUUID())
        dispatcher().run()
        assertThat(stored(gone).status).isEqualTo(NotificationStatus.FAILED)
        assertThat(stored(gone).lastErrorCode).isEqualTo("CONTACT_UNAVAILABLE")
        assertThat(stored(noEmail).status).isEqualTo(NotificationStatus.FAILED)
        assertThat(stored(noEmail).lastErrorCode).isEqualTo("CONTACT_UNAVAILABLE")
        assertThat(stored(noGrant).status).isEqualTo(NotificationStatus.FAILED)
        assertThat(stored(noGrant).lastErrorCode).isEqualTo("CONSENT_NOT_FOUND")
        assertThat(email.sent).isEmpty()
        assertThat(whatsApp.sent).isEmpty()
    }

    // --- o que é devido, clínica a clínica ---

    @Test
    fun `only pending notifications whose time has come are picked`() {
        val future = Notification.restore(UUID.randomUUID(), patient, grant, NotificationKind.CONSENT_ACCEPTED, NotificationChannel.EMAIL, NotificationStatus.PENDING, 1, t0.plusSeconds(600), "E")
        store.rows[future.id] = clinicA to future
        val sent = pending().markSent()
        store.rows[sent.id] = clinicA to sent
        val cancelled = pending().cancel()
        store.rows[cancelled.id] = clinicA to cancelled
        val due = pending()
        val result = dispatcher().run()
        assertThat(result.sent).isEqualTo(1)
        assertThat(stored(due).status).isEqualTo(NotificationStatus.SENT)
        assertThat(stored(future).status).isEqualTo(NotificationStatus.PENDING)
        assertThat(stored(sent).attempts).isEqualTo(1)
        assertThat(stored(cancelled).status).isEqualTo(NotificationStatus.CANCELLED)
    }

    @Test
    fun `each clinic is worked inside its own context and clinics that are not listed are skipped`() {
        val seenBySender = mutableListOf<UUID?>()
        val capturing = object : EmailSender {
            override fun send(email: OutgoingEmail) {
                seenBySender += TenantScope.current()?.tenantId
            }
        }
        val inA = pending(tenant = clinicA)
        val inB = pending(tenant = clinicB)
        clinics = listOf(clinicA)
        dispatcher(emailSender = capturing).run()
        assertThat(stored(inA).status).isEqualTo(NotificationStatus.SENT)
        assertThat(stored(inB).status).describedAs("clínica fora da lista de ativas").isEqualTo(NotificationStatus.PENDING)
        assertThat(seenBySender).containsExactly(clinicA)

        clinics = listOf(clinicA, clinicB)
        dispatcher(emailSender = capturing).run()
        assertThat(stored(inB).status).isEqualTo(NotificationStatus.SENT)
        assertThat(seenBySender).containsExactly(clinicA, clinicB)
        assertThat(TenantScope.current()).describedAs("o contexto não vaza depois da execução").isNull()
    }

    @Test
    fun `a clinic that breaks does not stop the others`() {
        clinics = listOf(clinicA, clinicB)
        pending(tenant = clinicA)
        val inB = pending(tenant = clinicB)
        val failingForA = object : Transactions {
            override fun <T> inTransaction(block: () -> T): T {
                if (TenantScope.current()?.tenantId == clinicA) throw IllegalStateException("banco fora do ar para a clínica A")
                return block()
            }
        }
        val dispatcher = DispatchPending(
            ClinicDirectory { clinics }, store, PatientContactLookup { contacts[it] }, MessagingConsentLookup { it in withConsent }, ConsentInfoLookup { p, g -> consents[p to g] },
            email, whatsApp, audit, failingForA, clock, policy, 50,
        )
        val result = dispatcher.run()
        assertThat(result.clinicErrors).isEqualTo(1)
        assertThat(result.sent).isEqualTo(1)
        assertThat(stored(inB).status).isEqualTo(NotificationStatus.SENT)
    }

    @Test
    fun `each run handles at most the limit per clinic, and the rest waits for the next run`() {
        val all = (1..5).map { pending() }
        val first = dispatcher(maxPerClinic = 2).run()
        assertThat(first.sent).isEqualTo(2)
        assertThat(all.count { stored(it).status == NotificationStatus.PENDING }).isEqualTo(3)
        assertThat(dispatcher(maxPerClinic = 2).run().sent).isEqualTo(2)
        assertThat(dispatcher(maxPerClinic = 2).run().sent).isEqualTo(1)
        assertThat(dispatcher(maxPerClinic = 2).run().sent).isZero()
    }

    @Test
    fun `with no clinics or nothing due the run does nothing`() {
        clinics = emptyList()
        pending()
        assertThat(dispatcher().run().sent).isZero()
        clinics = listOf(clinicA)
        store.rows.clear()
        val empty = dispatcher().run()
        assertThat(empty.sent + empty.blocked + empty.retrying + empty.failed + empty.clinicErrors).isZero()
    }
}
