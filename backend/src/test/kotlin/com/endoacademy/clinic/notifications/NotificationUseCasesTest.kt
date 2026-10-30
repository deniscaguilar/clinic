package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.notifications.application.ContactDetails
import com.endoacademy.clinic.notifications.application.NotificationStore
import com.endoacademy.clinic.notifications.application.OnConsentAccepted
import com.endoacademy.clinic.notifications.application.OnConsentRevoked
import com.endoacademy.clinic.notifications.application.PatientContactLookup
import com.endoacademy.clinic.notifications.application.Transactions
import com.endoacademy.clinic.notifications.domain.Notification
import com.endoacademy.clinic.notifications.domain.NotificationChannel
import com.endoacademy.clinic.notifications.domain.NotificationKind
import com.endoacademy.clinic.notifications.domain.NotificationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class NotificationUseCasesTest {

    private class FakeStore : NotificationStore {
        val rows = linkedMapOf<UUID, Pair<UUID, Notification>>() // id -> (tenant, notification)
        override fun insertIfAbsent(tenantId: UUID, notification: Notification): Boolean {
            val duplicate = rows.values.any {
                it.first == tenantId && it.second.referenceId == notification.referenceId && it.second.channel == notification.channel && it.second.kind == notification.kind
            }
            if (duplicate) return false
            rows[notification.id] = tenantId to notification
            return true
        }

        override fun claimDue(now: Instant): Notification? = error("não usado aqui")

        override fun save(notification: Notification) = error("não usado aqui")

        override fun cancelPending(patientId: UUID): Int {
            var count = 0
            rows.replaceAll { _, (tenant, n) ->
                if (n.patientId == patientId && n.status == NotificationStatus.PENDING) {
                    count++
                    tenant to n.cancel()
                } else {
                    tenant to n
                }
            }
            return count
        }
    }

    private class FakeContacts : PatientContactLookup {
        val contacts = mutableMapOf<UUID, ContactDetails>()
        override fun find(patientId: UUID) = contacts[patientId]
    }

    private val tenant = UUID.randomUUID()
    private val patient = UUID.randomUUID()
    private val grant = UUID.randomUUID()
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val store = FakeStore()
    private val registry = FakeContacts()
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private var nextId = 0
    private val onAccepted = OnConsentAccepted(registry, store, transactions, Clock.fixed(now, ZoneOffset.UTC)) { UUID(0, (++nextId).toLong()) }
    private val onRevoked = OnConsentRevoked(store, transactions)

    private fun contact(whatsapp: String? = "11987654321", email: String? = "maria@example.com") {
        registry.contacts[patient] = ContactDetails("Maria da Silva", whatsapp, email)
    }

    // --- OnConsentAccepted ---

    @Test
    fun `an acceptance creates one pending notification per channel the patient has`() {
        contact()
        assertThat(onAccepted.handle(tenant, patient, grant)).isEqualTo(2)
        val created = store.rows.values.map { it.second }
        assertThat(created.map { it.channel }).containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.WHATSAPP)
        created.forEach {
            assertThat(it.patientId).isEqualTo(patient)
            assertThat(it.referenceId).isEqualTo(grant)
            assertThat(it.kind).isEqualTo(NotificationKind.CONSENT_ACCEPTED)
            assertThat(it.status).isEqualTo(NotificationStatus.PENDING)
            assertThat(it.attempts).isZero()
            assertThat(it.nextAttemptAt).isEqualTo(now)
        }
        assertThat(store.rows.values.map { it.first }.toSet()).containsExactly(tenant)
    }

    @Test
    fun `only the channels the patient registered get a notification`() {
        contact(whatsapp = null)
        assertThat(onAccepted.handle(tenant, patient, grant)).isEqualTo(1)
        assertThat(store.rows.values.single().second.channel).isEqualTo(NotificationChannel.EMAIL)

        val other = UUID.randomUUID()
        registry.contacts[other] = ContactDetails("João Pereira", "11912345678", null)
        assertThat(onAccepted.handle(tenant, other, UUID.randomUUID())).isEqualTo(1)
        assertThat(store.rows.values.map { it.second }.last().channel).isEqualTo(NotificationChannel.WHATSAPP)
    }

    @Test
    fun `a patient with no channel, or one that is gone, gets nothing`() {
        contact(whatsapp = null, email = null)
        assertThat(onAccepted.handle(tenant, patient, grant)).isZero()
        assertThat(onAccepted.handle(tenant, UUID.randomUUID(), grant)).isZero()
        assertThat(store.rows).isEmpty()
    }

    @Test
    fun `handling the same event twice does not duplicate the notifications`() {
        contact()
        assertThat(onAccepted.handle(tenant, patient, grant)).isEqualTo(2)
        assertThat(onAccepted.handle(tenant, patient, grant)).isZero()
        assertThat(store.rows).hasSize(2)
        assertThat(onAccepted.handle(tenant, patient, UUID.randomUUID())).describedAs("outro aceite é outra mensagem").isEqualTo(2)
        assertThat(store.rows).hasSize(4)
    }

    @Test
    fun `the notification never holds the recipient or any text`() {
        contact()
        onAccepted.handle(tenant, patient, grant)
        store.rows.values.forEach {
            val text = it.second.toString()
            assertThat(text).doesNotContain("11987654321").doesNotContain("maria@example.com").doesNotContain("Maria")
        }
    }

    // --- OnConsentRevoked ---

    @Test
    fun `a revocation cancels the pending notifications of that patient only`() {
        contact()
        onAccepted.handle(tenant, patient, grant)
        val other = UUID.randomUUID()
        registry.contacts[other] = ContactDetails("João Pereira", null, "joao@example.com")
        onAccepted.handle(tenant, other, UUID.randomUUID())

        assertThat(onRevoked.handle(patient)).isEqualTo(2)
        val ofPatient = store.rows.values.map { it.second }.filter { it.patientId == patient }
        assertThat(ofPatient).hasSize(2)
        ofPatient.forEach { assertThat(it.status).isEqualTo(NotificationStatus.CANCELLED) }
        assertThat(store.rows.values.map { it.second }.single { it.patientId == other }.status).isEqualTo(NotificationStatus.PENDING)
    }

    @Test
    fun `a revocation leaves alone what was already sent, failed or blocked`() {
        contact()
        onAccepted.handle(tenant, patient, grant)
        val (first, second) = store.rows.values.map { it.second }
        store.rows[first.id] = tenant to first.markSent()
        store.rows[second.id] = tenant to second.block()
        assertThat(onRevoked.handle(patient)).isZero()
        assertThat(store.rows.values.map { it.second.status }).containsExactlyInAnyOrder(NotificationStatus.SENT, NotificationStatus.BLOCKED_NO_CONSENT)
    }

    @Test
    fun `revoking with nothing pending does nothing`() {
        assertThat(onRevoked.handle(UUID.randomUUID())).isZero()
    }
}
