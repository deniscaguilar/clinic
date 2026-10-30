package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.notifications.application.OnConsentAccepted
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.PostgresContainer
import com.endoacademy.clinic.support.TenantFixtures
import com.endoacademy.clinic.support.ClinicApiIT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.DriverManager
import java.util.UUID

/** Do aceite de consentimento à fila de notificações, passando pelos eventos do Spring Modulith (T5.7; RF13, CA16 na origem). */
class NotificationFlowIT : ClinicApiIT() {

    @Autowired
    lateinit var onAccepted: OnConsentAccepted

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    private fun newType(clinic: Clinic, name: String = "Mensagens ${UUID.randomUUID()}"): UUID = idOf(
        mockMvc.perform(
            call(post("/api/v1/consent-types").contentType(MediaType.APPLICATION_JSON).content(body("name" to name, "text" to "Autorizo.", "requiredOnSignup" to true, "controlsMessaging" to false)), clinic),
        ).andExpect(status().isCreated),
    )

    private fun register(clinic: Clinic, type: UUID, whatsapp: String? = "(11) 98765-4321", email: String? = "maria@example.com", cpf: String = "529.982.247-25"): UUID = idOf(
        mockMvc.perform(
            call(
                post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(
                    json.writeValueAsString(
                        mapOf(
                            "cpf" to cpf, "fullName" to "Maria da Silva", "birthDate" to "1985-03-12", "whatsapp" to whatsapp, "email" to email,
                            "address" to mapOf("zipCode" to "01310-100", "street" to "Avenida Paulista", "number" to "1578", "district" to "Bela Vista", "city" to "São Paulo", "state" to "SP"),
                            "consents" to listOf(mapOf("typeId" to type)),
                        ),
                    ),
                ),
                clinic,
            ),
        ).andExpect(status().isCreated),
    )

    private data class Row(val patient: UUID, val reference: UUID, val kind: String, val channel: String, val status: String, val attempts: Int, val due: Boolean)

    private fun rows(clinic: Clinic, patient: UUID? = null): List<Row> = TenantScope.runAs(clinic.id) {
        tx.execute {
            jdbc.query(
                "select patient_id, reference_id, kind, channel, status, attempts, next_attempt_at is not null from notifications.notification where (cast(? as uuid) is null or patient_id = cast(? as uuid)) order by channel",
                { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getBoolean(7)) },
                patient,
                patient,
            )
        }!!
    }

    private fun grantOf(clinic: Clinic, patient: UUID): UUID = TenantScope.runAs(clinic.id) {
        tx.execute { jdbc.queryForObject("select id from registry.consent_grant where patient_id = ?", UUID::class.java, patient) }!!
    }

    @Test
    fun `an acceptance creates one pending notification per channel, pointing at the acceptance`() {
        val clinic = newClinic()
        val patient = register(clinic, newType(clinic))
        awaitUntil("duas notificações") { rows(clinic, patient).size == 2 }

        val grant = grantOf(clinic, patient)
        val created = rows(clinic, patient)
        assertThat(created.map { it.channel }).containsExactly("EMAIL", "WHATSAPP")
        created.forEach {
            assertThat(it.kind).isEqualTo("CONSENT_ACCEPTED")
            assertThat(it.reference).isEqualTo(grant)
            assertThat(it.status).isEqualTo("PENDING")
            assertThat(it.attempts).isZero()
            assertThat(it.due).isTrue()
        }
    }

    @Test
    fun `only the channels the patient has get a notification`() {
        val clinic = newClinic()
        val type = newType(clinic)
        val onlyEmail = register(clinic, type, whatsapp = null)
        awaitUntil("a notificação de e-mail") { rows(clinic, onlyEmail).isNotEmpty() }
        assertThat(rows(clinic, onlyEmail).map { it.channel }).containsExactly("EMAIL")

        val onlyWhatsapp = register(clinic, type, email = null, cpf = "111.444.777-35")
        awaitUntil("a notificação de WhatsApp") { rows(clinic, onlyWhatsapp).isNotEmpty() }
        assertThat(rows(clinic, onlyWhatsapp).map { it.channel }).containsExactly("WHATSAPP")
    }

    @Test
    fun `a revocation cancels what is still pending and leaves the rest alone`() {
        val clinic = newClinic()
        val patient = register(clinic, newType(clinic))
        awaitUntil("duas notificações") { rows(clinic, patient).size == 2 }
        // uma já enviada: a revogação não mexe nela
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult {
                jdbc.update(
                    "update notifications.notification set status = 'SENT', attempts = 1, next_attempt_at = null where patient_id = ? and channel = 'EMAIL'",
                    patient,
                )
            }
        }
        mockMvc.perform(call(post("/api/v1/patients/$patient/consents/${grantOf(clinic, patient)}/revoke"), clinic)).andExpect(status().isOk)

        awaitUntil("o WhatsApp pendente ser cancelado") { rows(clinic, patient).any { it.channel == "WHATSAPP" && it.status == "CANCELLED" } }
        val after = rows(clinic, patient).associateBy { it.channel }
        assertThat(after.getValue("WHATSAPP").status).isEqualTo("CANCELLED")
        assertThat(after.getValue("WHATSAPP").due).isFalse()
        assertThat(after.getValue("EMAIL").status).isEqualTo("SENT")
        assertThat(after.getValue("EMAIL").attempts).isEqualTo(1)
    }

    @Test
    fun `revoking one patient cancels only that patient's pending notifications`() {
        val clinic = newClinic()
        val type = newType(clinic)
        val maria = register(clinic, type)
        val joao = register(clinic, type, cpf = "111.444.777-35")
        awaitUntil("as notificações dos dois") { rows(clinic, maria).size == 2 && rows(clinic, joao).size == 2 }

        mockMvc.perform(call(post("/api/v1/patients/$maria/consents/${grantOf(clinic, maria)}/revoke"), clinic)).andExpect(status().isOk)
        awaitUntil("as da Maria serem canceladas") { rows(clinic, maria).all { it.status == "CANCELLED" } }
        assertThat(rows(clinic, joao).map { it.status }).describedAs("as do João seguem pendentes").containsOnly("PENDING")
    }

    @Test
    fun `the acceptance does not wait for the notification handler`() {
        val clinic = newClinic()
        val type = newType(clinic)
        val patient = TenantScope.runAs(clinic.id) { tx.execute { TenantFixtures.newPatient(jdbc, clinic.id) }!! }
        fun owner(sql: String) = DriverManager.getConnection(PostgresContainer.instance.jdbcUrl, "clinic_migrator", PostgresContainer.MIGRATOR_PASSWORD).use { c -> c.createStatement().use { it.execute(sql) } }
        owner(
            """create or replace function notifications.zz_slow_for_test() returns trigger language plpgsql as
               ${'$'}${'$'} begin perform pg_sleep(3); return new; end; ${'$'}${'$'}""",
        )
        owner("create trigger zz_slow before insert on notifications.notification for each row when (new.patient_id = '$patient') execute function notifications.zz_slow_for_test()")
        try {
            val started = System.nanoTime()
            mockMvc.perform(call(post("/api/v1/patients/$patient/consents").contentType(MediaType.APPLICATION_JSON).content(body("typeId" to type)), clinic))
                .andExpect(status().isCreated)
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertThat(elapsedMillis).describedAs("o pedido não espera o tratador lento").isLessThan(2_000)
            awaitUntil("a notificação aparecer depois") { rows(clinic, patient).isNotEmpty() }
            assertThat(rows(clinic, patient).map { it.channel }).containsExactly("WHATSAPP") // o paciente da fixture só tem WhatsApp
        } finally {
            owner("drop trigger if exists zz_slow on notifications.notification")
            owner("drop function if exists notifications.zz_slow_for_test()")
        }
    }

    @Test
    fun `the same event handled again creates nothing new`() {
        val clinic = newClinic()
        val patient = register(clinic, newType(clinic))
        awaitUntil("duas notificações") { rows(clinic, patient).size == 2 }
        val grant = grantOf(clinic, patient)

        val created = TenantScope.runAs(clinic.id) { onAccepted.handle(clinic.id, patient, grant) }
        assertThat(created).isZero()
        assertThat(rows(clinic, patient)).hasSize(2)
        val another = TenantScope.runAs(clinic.id) { onAccepted.handle(clinic.id, patient, UUID.randomUUID()) }
        assertThat(another).describedAs("outro aceite é outra mensagem").isEqualTo(2)
    }

    @Test
    fun `notifications of one clinic never appear in another`() {
        val a = newClinic()
        val b = newClinic()
        val patientOfA = register(a, newType(a))
        awaitUntil("as notificações da clínica A") { rows(a, patientOfA).size == 2 }
        assertThat(rows(b)).isEmpty()
        assertThat(rows(b, patientOfA)).isEmpty()
        val seenByPlatform = TenantScope.runAsPlatform { tx.execute { jdbc.queryForObject("select count(*) from notifications.notification", Int::class.java) }!! }
        assertThat(seenByPlatform).describedAs("o escopo platform não abre a fila").isZero()
    }

    @Test
    fun `the queue holds no recipient and no text, and the event is recorded as handled`() {
        val clinic = newClinic()
        val patient = register(clinic, newType(clinic))
        awaitUntil("duas notificações") { rows(clinic, patient).size == 2 }
        val raw = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForList("select n::text from notifications.notification n where patient_id = ?", String::class.java, patient) }!!.filterNotNull()
        }
        assertThat(raw).hasSize(2)
        raw.forEach { assertThat(it).doesNotContain("Maria").doesNotContain("98765").doesNotContain("maria@example.com").doesNotContain("Autorizo") }

        awaitUntil("a publicação do ouvinte ser concluída") {
            jdbc.queryForObject(
                "select count(*) from eventing.event_publication where listener_id like '%ConsentEventListeners%' and event_type like '%ConsentAccepted' and serialized_event like ? and completion_date is not null",
                Int::class.java,
                "%${grantOf(clinic, patient)}%",
            ) == 1
        }
    }
    @Test
    fun `a failing notification handler never fails the acceptance and leaves the event pending for another try`() {
        val clinic = newClinic()
        val type = newType(clinic)
        val patient = TenantScope.runAs(clinic.id) { tx.execute { TenantFixtures.newPatient(jdbc, clinic.id) }!! }
        fun owner(sql: String) = DriverManager.getConnection(PostgresContainer.instance.jdbcUrl, "clinic_migrator", PostgresContainer.MIGRATOR_PASSWORD).use { c -> c.createStatement().use { it.execute(sql) } }
        owner(
            """create or replace function notifications.zz_fail_for_test() returns trigger language plpgsql as
               ${'$'}${'$'} begin raise exception 'falha simulada ao gravar a fila'; end; ${'$'}${'$'}""",
        )
        owner("create trigger zz_fail before insert on notifications.notification for each row when (new.patient_id = '$patient') execute function notifications.zz_fail_for_test()")
        try {
            mockMvc.perform(call(post("/api/v1/patients/$patient/consents").contentType(MediaType.APPLICATION_JSON).content(body("typeId" to type)), clinic))
                .andExpect(status().isCreated)
            val grant = grantOf(clinic, patient)
            Thread.sleep(1500)
            assertThat(rows(clinic, patient)).describedAs("nada entrou na fila").isEmpty()
            val pending = jdbc.queryForObject(
                "select count(*) from eventing.event_publication where listener_id like '%ConsentEventListeners%' and event_type like '%ConsentAccepted' and serialized_event like ? and completion_date is null",
                Int::class.java,
                "%$grant%",
            )
            assertThat(pending).describedAs("a publicação do ouvinte fica pendente").isEqualTo(1)
            val accepted = TenantScope.runAs(clinic.id) { tx.execute { jdbc.queryForObject("select count(*) from registry.consent_grant where id = ?", Int::class.java, grant) }!! }
            assertThat(accepted).describedAs("o aceite continua gravado").isEqualTo(1)
        } finally {
            owner("drop trigger if exists zz_fail on notifications.notification")
            owner("drop function if exists notifications.zz_fail_for_test()")
        }
    }
}
