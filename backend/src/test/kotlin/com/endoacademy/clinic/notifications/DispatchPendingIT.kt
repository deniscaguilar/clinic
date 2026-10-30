package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.notifications.adapter.out.email.InMemoryEmailSender
import com.endoacademy.clinic.notifications.adapter.out.whatsapp.FakeWhatsAppSender
import com.endoacademy.clinic.notifications.application.ClinicDirectory
import com.endoacademy.clinic.notifications.application.ConsentInfoLookup
import com.endoacademy.clinic.notifications.application.DispatchPending
import com.endoacademy.clinic.notifications.application.MessagingConsentLookup
import com.endoacademy.clinic.notifications.application.NotificationSendException
import com.endoacademy.clinic.notifications.application.NotificationStore
import com.endoacademy.clinic.notifications.application.PatientContactLookup
import com.endoacademy.clinic.notifications.application.Transactions
import com.endoacademy.clinic.notifications.domain.RetryPolicy
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.ClinicApiIT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Do aceite ao envio, com os adaptadores locais (T5.9): CA16, CA17, CA18 e dois workers sem duplicar. */
class DispatchPendingIT : ClinicApiIT() {

    @Autowired lateinit var store: NotificationStore

    @Autowired lateinit var contacts: PatientContactLookup

    @Autowired lateinit var consent: MessagingConsentLookup

    @Autowired lateinit var consents: ConsentInfoLookup

    @Autowired lateinit var email: InMemoryEmailSender

    @Autowired lateinit var whatsApp: FakeWhatsAppSender

    @Autowired lateinit var audit: AuditTrail

    @Autowired lateinit var transactions: Transactions

    @Autowired lateinit var realDirectory: ClinicDirectory

    private class MutableClock(var now: Instant = Instant.now()) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock()
    private val policy = RetryPolicy(maxAttempts = 3, baseDelay = Duration.ofMinutes(1), maxDelay = Duration.ofMinutes(10))

    @BeforeEach
    fun resetSenders() {
        email.clear()
        whatsApp.clear()
        clock.now = Instant.now()
    }

    private fun dispatcher(vararg clinics: Clinic, directory: ClinicDirectory? = null, maxPerClinic: Int = 50) = DispatchPending(
        directory ?: ClinicDirectory { clinics.map { it.id } }, store, contacts, consent, consents, email, whatsApp, audit, transactions, clock, policy, maxPerClinic,
    )

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    private fun newType(clinic: Clinic, messaging: Boolean, name: String = "Tipo ${UUID.randomUUID()}"): UUID = idOf(
        mockMvc.perform(
            call(post("/api/v1/consent-types").contentType(MediaType.APPLICATION_JSON).content(body("name" to name, "text" to "Autorizo.", "requiredOnSignup" to true, "controlsMessaging" to messaging)), clinic),
        ).andExpect(status().isCreated),
    )

    private fun register(clinic: Clinic, type: UUID, address: String = "p-${UUID.randomUUID()}@example.com", cpf: String = "529.982.247-25"): Pair<UUID, String> = idOf(
        mockMvc.perform(
            call(
                post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(
                    json.writeValueAsString(
                        mapOf(
                            "cpf" to cpf, "fullName" to "Maria da Silva", "birthDate" to "1985-03-12", "whatsapp" to "(11) 98765-4321", "email" to address,
                            "address" to mapOf("zipCode" to "01310-100", "street" to "Avenida Paulista", "number" to "1578", "district" to "Bela Vista", "city" to "São Paulo", "state" to "SP"),
                            "consents" to listOf(mapOf("typeId" to type)),
                        ),
                    ),
                ),
                clinic,
            ),
        ).andExpect(status().isCreated),
    ) to address

    private data class Row(val id: UUID, val channel: String, val status: String, val attempts: Int, val next: Instant?, val error: String?)

    private fun rows(clinic: Clinic, patient: UUID): List<Row> = TenantScope.runAs(clinic.id) {
        tx.execute {
            jdbc.query(
                "select id, channel, status, attempts, next_attempt_at, last_error_code from notifications.notification where patient_id = ? order by channel",
                { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getTimestamp(5)?.toInstant(), rs.getString(6)) },
                patient,
            )
        }!!
    }

    /** Espera o ouvinte criar as linhas e põe o relógio do worker depois delas (elas nascem com a hora real). */
    private fun createdRows(clinic: Clinic, patient: UUID, count: Int): List<Row> {
        awaitUntil("$count notificações") { rows(clinic, patient).size == count }
        clock.now = Instant.now()
        return rows(clinic, patient)
    }

    // --- CA16 ---

    @Test
    fun `an acceptance is confirmed by e-mail and by WhatsApp with the type name and the date, nothing more (CA16)`() {
        val clinic = newClinic()
        val type = newType(clinic, messaging = true, name = "Cadastro e mensagens")
        val (patient, address) = register(clinic, type)
        createdRows(clinic, patient, 2)

        val result = dispatcher(clinic).run()
        assertThat(result.sent).isEqualTo(2)
        assertThat(rows(clinic, patient).map { it.status }).containsOnly("SENT")
        assertThat(rows(clinic, patient).map { it.attempts }).containsOnly(1)

        val mail = email.sent.single { it.to == address }
        assertThat(mail.subject).isEqualTo("Confirmação de consentimento")
        val today = java.time.LocalDate.now(ZoneId.of("America/Sao_Paulo")).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        assertThat(mail.body).contains("Cadastro e mensagens").contains(today).doesNotContain("Maria").doesNotContain("52998224725")
        assertThat(whatsApp.sent.map { it.body }).contains(mail.body)
        assertThat(auditCount(clinic, "SEND_NOTIFICATION", "SUCCESS")).isEqualTo(2)
    }

    @Test
    fun `a second run sends nothing more`() {
        val clinic = newClinic()
        val (patient, address) = register(clinic, newType(clinic, messaging = true))
        createdRows(clinic, patient, 2)
        dispatcher(clinic).run()
        val second = dispatcher(clinic).run()
        assertThat(second.sent).isZero()
        assertThat(email.sent.count { it.to == address }).isEqualTo(1)
    }

    // --- CA17 ---

    @Test
    fun `without a messaging consent nothing is sent, the attempt is recorded as blocked (CA17)`() {
        val clinic = newClinic()
        val imageOnly = newType(clinic, messaging = false, name = "Uso de imagem")
        val (patient, address) = register(clinic, imageOnly)
        createdRows(clinic, patient, 2)

        val result = dispatcher(clinic).run()
        assertThat(result.blocked).isEqualTo(2)
        assertThat(result.sent).isZero()
        assertThat(email.sent.filter { it.to == address }).isEmpty()
        assertThat(whatsApp.sent).isEmpty()
        assertThat(rows(clinic, patient).map { it.status }).containsOnly("BLOCKED_NO_CONSENT")
        assertThat(auditCount(clinic, "SEND_NOTIFICATION", "DENIED")).isEqualTo(2)
    }

    @Test
    fun `a pending notification of a patient who revoked the messaging consent is blocked at send time (CA17)`() {
        val clinic = newClinic()
        val (patient, address) = register(clinic, newType(clinic, messaging = true))
        createdRows(clinic, patient, 2)
        val grant = TenantScope.runAs(clinic.id) { tx.execute { jdbc.queryForObject("select id from registry.consent_grant where patient_id = ?", UUID::class.java, patient) }!! }
        mockMvc.perform(call(post("/api/v1/patients/$patient/consents/$grant/revoke"), clinic)).andExpect(status().isOk)
        awaitUntil("a fila ser cancelada") { rows(clinic, patient).all { it.status == "CANCELLED" } }

        // uma notificação que escapou do cancelamento (por exemplo, criada logo depois da revogação)
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult {
                jdbc.update(
                    "insert into notifications.notification (id, tenant_id, patient_id, reference_id, kind, channel, status, next_attempt_at) values (?, ?, ?, ?, 'CONSENT_ACCEPTED', 'EMAIL', 'PENDING', now())",
                    UUID.randomUUID(), clinic.id, patient, UUID.randomUUID(),
                )
            }
        }
        clock.now = Instant.now()
        val result = dispatcher(clinic).run()
        assertThat(result.blocked).isEqualTo(1)
        assertThat(email.sent.filter { it.to == address }).isEmpty()
        assertThat(rows(clinic, patient).count { it.status == "BLOCKED_NO_CONSENT" }).isEqualTo(1)
    }

    // --- CA18 ---

    @Test
    fun `a failed send keeps the acceptance and the message is sent again later, with backoff (CA18)`() {
        val clinic = newClinic()
        val (patient, address) = register(clinic, newType(clinic, messaging = true))
        createdRows(clinic, patient, 2)
        email.failWith(NotificationSendException("EMAIL_UNAVAILABLE", retryable = true), times = 1)

        val first = dispatcher(clinic).run()
        assertThat(first.sent).describedAs("o WhatsApp saiu").isEqualTo(1)
        assertThat(first.retrying).isEqualTo(1)
        val afterFailure = rows(clinic, patient).associateBy { it.channel }
        assertThat(afterFailure.getValue("EMAIL").status).isEqualTo("PENDING")
        assertThat(afterFailure.getValue("EMAIL").attempts).isEqualTo(1)
        assertThat(afterFailure.getValue("EMAIL").error).isEqualTo("EMAIL_UNAVAILABLE")
        assertThat(afterFailure.getValue("EMAIL").next).isAfter(clock.now.plusSeconds(55)).isBefore(clock.now.plusSeconds(65))
        assertThat(afterFailure.getValue("WHATSAPP").status).isEqualTo("SENT")
        val stillAccepted = TenantScope.runAs(clinic.id) { tx.execute { jdbc.queryForObject("select count(*) from registry.consent_grant where patient_id = ? and revoked_at is null", Int::class.java, patient) }!! }
        assertThat(stillAccepted).describedAs("o aceite permanece").isEqualTo(1)
        assertThat(auditCount(clinic, "SEND_NOTIFICATION", "FAILURE")).isEqualTo(1)

        clock.now = clock.now.plusSeconds(30)
        assertThat(dispatcher(clinic).run().sent).describedAs("ainda não é a hora").isZero()
        assertThat(email.sent.filter { it.to == address }).isEmpty()

        clock.now = clock.now.plusSeconds(40)
        assertThat(dispatcher(clinic).run().sent).isEqualTo(1)
        val afterRetry = rows(clinic, patient).associateBy { it.channel }
        assertThat(afterRetry.getValue("EMAIL").status).isEqualTo("SENT")
        assertThat(afterRetry.getValue("EMAIL").attempts).isEqualTo(2)
        assertThat(afterRetry.getValue("EMAIL").error).isNull()
        assertThat(email.sent.count { it.to == address }).isEqualTo(1)
    }

    @Test
    fun `when every attempt fails the notification ends as failed and stays failed`() {
        val clinic = newClinic()
        val (patient, _) = register(clinic, newType(clinic, messaging = true))
        createdRows(clinic, patient, 2)
        email.failWith(NotificationSendException("EMAIL_UNAVAILABLE", retryable = true), times = 20)
        repeat(3) {
            dispatcher(clinic).run()
            clock.now = clock.now.plus(Duration.ofHours(1))
        }
        val emailRow = rows(clinic, patient).single { it.channel == "EMAIL" }
        assertThat(emailRow.status).isEqualTo("FAILED")
        assertThat(emailRow.attempts).isEqualTo(3)
        assertThat(emailRow.next).isNull()
        assertThat(emailRow.error).isEqualTo("EMAIL_UNAVAILABLE")
        assertThat(dispatcher(clinic).run().sent).isZero()
    }

    @Test
    fun `a rejection that is not worth repeating fails at once`() {
        val clinic = newClinic()
        val (patient, _) = register(clinic, newType(clinic, messaging = true))
        createdRows(clinic, patient, 2)
        email.failWith(NotificationSendException("EMAIL_REJECTED", retryable = false), times = 1)
        dispatcher(clinic).run()
        val emailRow = rows(clinic, patient).single { it.channel == "EMAIL" }
        assertThat(emailRow.status).isEqualTo("FAILED")
        assertThat(emailRow.attempts).isEqualTo(1)
        assertThat(emailRow.error).isEqualTo("EMAIL_REJECTED")
    }

    // --- concorrência ---

    /** Insere `count` notificações de e-mail pendentes, devidas há minutos, cada uma com o seu aceite (ids lógicos). */
    private fun insertDue(clinic: Clinic, patient: UUID, count: Int, olderBy: String = "1 minute"): List<UUID> {
        val ids = List(count) { UUID.randomUUID() }
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult {
                ids.forEach {
                    jdbc.update(
                        "insert into notifications.notification (id, tenant_id, patient_id, reference_id, kind, channel, status, next_attempt_at) " +
                            "values (?, ?, ?, ?, 'CONSENT_ACCEPTED', 'EMAIL', 'PENDING', now() - cast(? as interval))",
                        it, clinic.id, patient, UUID.randomUUID(), olderBy,
                    )
                }
            }
        }
        return ids
    }

    private val knownConsent = ConsentInfoLookup { _, _ -> com.endoacademy.clinic.notifications.application.ConsentInfo("Cadastro e mensagens", Instant.now()) }

    private fun dispatcherWithKnownConsent(clinic: Clinic, sender: com.endoacademy.clinic.notifications.application.EmailSender = email, limit: Int = 100) = DispatchPending(
        ClinicDirectory { listOf(clinic.id) }, store, contacts, consent, knownConsent, sender, whatsApp, audit, transactions, clock, policy, limit,
    )

    private fun statusOf(clinic: Clinic, id: UUID): String = TenantScope.runAs(clinic.id) {
        tx.execute { jdbc.queryForObject("select status from notifications.notification where id = ?", String::class.java, id) }!!
    }

    @Test
    fun `the oldest due notification goes first`() {
        val clinic = newClinic()
        val (patient, _) = register(clinic, newType(clinic, messaging = true))
        createdRows(clinic, patient, 2)
        dispatcher(clinic).run()
        val newest = insertDue(clinic, patient, 1, olderBy = "1 minute").single()
        val oldest = insertDue(clinic, patient, 1, olderBy = "30 minutes").single()
        val middle = insertDue(clinic, patient, 1, olderBy = "10 minutes").single()
        clock.now = Instant.now()

        assertThat(dispatcherWithKnownConsent(clinic, limit = 1).run().sent).isEqualTo(1)
        assertThat(statusOf(clinic, oldest)).isEqualTo("SENT")
        assertThat(statusOf(clinic, middle)).isEqualTo("PENDING")
        assertThat(statusOf(clinic, newest)).isEqualTo("PENDING")
        assertThat(dispatcherWithKnownConsent(clinic, limit = 1).run().sent).isEqualTo(1)
        assertThat(statusOf(clinic, middle)).isEqualTo("SENT")
        assertThat(statusOf(clinic, newest)).isEqualTo("PENDING")
    }

    @Test
    fun `a notification is due exactly at its time and not a moment before`() {
        val clinic = newClinic()
        val patient = UUID.randomUUID()
        val id = UUID.randomUUID()
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult {
                jdbc.update(
                    "insert into notifications.notification (id, tenant_id, patient_id, reference_id, kind, channel, status, next_attempt_at) " +
                        "values (?, ?, ?, ?, 'CONSENT_ACCEPTED', 'EMAIL', 'PENDING', timestamptz '2026-01-01 10:00:00+00')",
                    id, clinic.id, patient, UUID.randomUUID(),
                )
            }
        }
        val due = Instant.parse("2026-01-01T10:00:00Z")
        assertThat(TenantScope.runAs(clinic.id) { tx.execute { store.claimDue(due.minusNanos(1_000)) } }).describedAs("um instante antes").isNull()
        assertThat(TenantScope.runAs(clinic.id) { tx.execute { store.claimDue(due) } }?.id).describedAs("na hora exata").isEqualTo(id)
        assertThat(TenantScope.runAs(clinic.id) { tx.execute { store.claimDue(due.plusSeconds(60)) } }?.id).describedAs("depois da hora").isEqualTo(id)
    }

    @Test
    fun `a notification locked by another worker is skipped, not waited for`() {
        val clinic = newClinic()
        val (patient, address) = register(clinic, newType(clinic, messaging = true))
        createdRows(clinic, patient, 2)
        dispatcher(clinic).run()
        email.clear()

        val locked = insertDue(clinic, patient, 1, olderBy = "10 minutes").single() // a mais antiga: seria a primeira da fila
        val others = insertDue(clinic, patient, 4)

        val lockHeld = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val holding = holder.submit {
                TenantScope.runAs(clinic.id) {
                    tx.executeWithoutResult {
                        jdbc.queryForObject("select id from notifications.notification where id = ? for update", UUID::class.java, locked)
                        lockHeld.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
            }
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue()

            val running = worker.submit<com.endoacademy.clinic.notifications.application.DispatchResult> { dispatcherWithKnownConsent(clinic).run() }
            val result = running.get(10, TimeUnit.SECONDS) // com a linha travada, não pode ficar esperando por ela
            assertThat(result.sent).isEqualTo(4)
            others.forEach { assertThat(statusOf(clinic, it)).isEqualTo("SENT") }
            assertThat(statusOf(clinic, locked)).describedAs("a travada ficou para quem a segura").isEqualTo("PENDING")
            assertThat(email.sent.count { it.to == address }).isEqualTo(4)

            release.countDown()
            holding.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            holder.shutdownNow()
            worker.shutdownNow()
        }
        assertThat(dispatcherWithKnownConsent(clinic).run().sent).isEqualTo(1)
        assertThat(statusOf(clinic, locked)).isEqualTo("SENT")
    }

    @Test
    fun `two workers running at the same time send every notification exactly once`() {
        val clinic = newClinic()
        val (patient, address) = register(clinic, newType(clinic, messaging = true))
        createdRows(clinic, patient, 2)
        dispatcher(clinic).run()
        email.clear()

        val ids = insertDue(clinic, patient, 40)
        val slow = object : com.endoacademy.clinic.notifications.application.EmailSender {
            override fun send(email: com.endoacademy.clinic.notifications.application.OutgoingEmail) {
                Thread.sleep(10)
                this@DispatchPendingIT.email.send(email)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val runs = List(2) {
                pool.submit<com.endoacademy.clinic.notifications.application.DispatchResult> {
                    start.await()
                    dispatcherWithKnownConsent(clinic, slow).run()
                }
            }
            start.countDown()
            val results = runs.map { it.get(60, TimeUnit.SECONDS) }
            assertThat(results.sumOf { it.sent }).describedAs("soma dos dois workers").isEqualTo(40)
            assertThat(results.count { it.sent > 0 }).describedAs("os dois trabalharam").isEqualTo(2)
        } finally {
            pool.shutdownNow()
        }
        assertThat(email.sent.count { it.to == address }).describedAs("e-mails enviados").isEqualTo(40)
        ids.forEach { assertThat(statusOf(clinic, it)).isEqualTo("SENT") }
        val attempts = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForList("select distinct attempts from notifications.notification where id = any (?)", Int::class.java, ids.toTypedArray()) }!!
        }
        assertThat(attempts).describedAs("cada linha foi tentada uma única vez").containsExactly(1)
    }

    // --- clínicas ---

    @Test
    fun `each clinic is worked in its own context, and a deactivated clinic is left alone until it is activated again`() {
        val a = newClinic()
        val b = newClinic()
        val (patientA, addressA) = register(a, newType(a, messaging = true))
        val (patientB, addressB) = register(b, newType(b, messaging = true))
        createdRows(a, patientA, 2)
        createdRows(b, patientB, 2)
        mockMvc.perform(post("/api/v1/admin/clinics/${b.id}/deactivate").header("Authorization", bearer(platformAdminSub))).andExpect(status().isNoContent)

        // o diretório de verdade (só clínicas ativas), mas só nos interessa o que aconteceu com estas duas
        val real = DispatchPending(realDirectory, store, contacts, consent, consents, email, whatsApp, audit, transactions, clock, policy, 100)
        real.run()
        assertThat(email.sent.count { it.to == addressA }).isEqualTo(1)
        assertThat(email.sent.count { it.to == addressB }).describedAs("clínica desativada").isZero()
        assertThat(rows(b, patientB).map { it.status }).containsOnly("PENDING")

        mockMvc.perform(post("/api/v1/admin/clinics/${b.id}/activate").header("Authorization", bearer(platformAdminSub))).andExpect(status().isNoContent)
        real.run()
        assertThat(email.sent.count { it.to == addressB }).isEqualTo(1)
        assertThat(email.sent.count { it.to == addressA }).describedAs("a A não é reenviada").isEqualTo(1)
        assertThat(rows(a, patientA).map { it.status }).containsOnly("SENT")
        assertThat(rows(b, patientB).map { it.status }).containsOnly("SENT")
    }

    @Test
    fun `the audit of the sending belongs to the clinic of the notification and holds no personal data`() {
        val a = newClinic()
        val b = newClinic()
        val (patientA, _) = register(a, newType(a, messaging = true))
        createdRows(a, patientA, 2)
        dispatcher(a, b).run()
        assertThat(auditCount(a, "SEND_NOTIFICATION", "SUCCESS")).isEqualTo(2)
        assertThat(auditCount(b, "SEND_NOTIFICATION", "SUCCESS")).isZero()
        val stored = TenantScope.runAs(a.id) {
            tx.execute { jdbc.queryForList("select action || ' ' || coalesce(entity_id, '') || ' ' || details::text || ' ' || coalesce(user_id::text, 'sem-usuario') from platform.audit_event where action = 'SEND_NOTIFICATION'", String::class.java) }!!.filterNotNull()
        }
        assertThat(stored).hasSize(2)
        stored.forEach { assertThat(it).contains("sem-usuario").doesNotContain("Maria").doesNotContain("98765").doesNotContain("example.com") }
    }
}
