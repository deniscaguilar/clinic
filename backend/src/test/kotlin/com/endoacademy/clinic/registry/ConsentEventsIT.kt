package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.registry.api.ConsentAccepted
import com.endoacademy.clinic.registry.api.ConsentRevoked
import com.endoacademy.clinic.support.ClinicApiIT
import com.endoacademy.clinic.support.TenantFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Os eventos de consentimento vão para o registro de publicações do Spring Modulith, no schema `eventing` (T5.4).
 * Os ouvintes reais (`notifications`) chegam na T5.7; aqui dois ouvintes de teste provam o mecanismo.
 */
@Import(ConsentEventsIT.Listeners::class)
class ConsentEventsIT : ClinicApiIT() {

    /** Eventos recebidos. Bean simples (sem proxy), para o teste ler sem passar pelo proxy transacional do ouvinte. */
    class EventLog {
        val accepted = CopyOnWriteArrayList<ConsentAccepted>()
        val revoked = CopyOnWriteArrayList<ConsentRevoked>()
    }

    open class RecordingListener(private val log: EventLog) {
        @ApplicationModuleListener
        open fun on(event: ConsentAccepted) {
            log.accepted += event
        }

        @ApplicationModuleListener
        open fun on(event: ConsentRevoked) {
            log.revoked += event
        }
    }

    /** Um ouvinte que sempre falha ao receber a revogação: o aceite e a revogação já gravados não podem ser desfeitos. */
    open class FailingRevokeListener {
        @ApplicationModuleListener
        open fun on(@Suppress("UNUSED_PARAMETER") event: ConsentRevoked) {
            error("falha simulada do ouvinte")
        }
    }

    @TestConfiguration
    class Listeners {
        @Bean
        fun eventLog() = EventLog()

        @Bean
        fun recordingListener(log: EventLog) = RecordingListener(log)

        @Bean
        fun failingRevokeListener() = FailingRevokeListener()
    }

    @org.springframework.beans.factory.annotation.Autowired
    lateinit var recording: EventLog

    private fun newPatient(clinic: Clinic): UUID = TenantScope.runAs(clinic.id) { tx.execute { TenantFixtures.newPatient(jdbc, clinic.id) }!! }

    private fun newType(clinic: Clinic): UUID = UUID.fromString(
        json.readTree(
            mockMvc.perform(
                call(post("/api/v1/consent-types").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Tipo ${UUID.randomUUID()}", "text" to "Autorizo.", "requiredOnSignup" to true, "controlsMessaging" to false)), clinic),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get("id").asString(),
    )

    private fun accept(clinic: Clinic, patient: UUID, type: UUID): ResultActions =
        mockMvc.perform(call(post("/api/v1/patients/$patient/consents").contentType(MediaType.APPLICATION_JSON).content(body("typeId" to type)), clinic))

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "tempo esgotado esperando: $what" }
            Thread.sleep(100)
        }
    }

    private fun publications(eventType: String, grant: UUID): List<Map<String, Any?>> =
        jdbc.queryForList(
            "select listener_id, serialized_event, completion_date from eventing.event_publication where event_type like ? and serialized_event like ?",
            "%$eventType",
            "%$grant%",
        )

    @Test
    fun `accepting publishes ConsentAccepted with only ids, it is stored in the eventing schema and completed by the listener`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val grant = idOf(accept(clinic, patient, newType(clinic)).andExpect(status().isCreated))

        await("o ouvinte receber ConsentAccepted") { recording.accepted.any { it.grantId == grant } }
        val event = recording.accepted.first { it.grantId == grant }
        assertThat(event).isEqualTo(ConsentAccepted(clinic.id, patient, grant))

        await("a publicação ser concluída") { publications("ConsentAccepted", grant).any { it["completion_date"] != null } }
        val rows = publications("ConsentAccepted", grant)
        assertThat(rows).isNotEmpty()
        rows.forEach {
            val payload = it["serialized_event"] as String
            assertThat(payload).contains(clinic.id.toString()).contains(patient.toString()).contains(grant.toString())
            assertThat(payload).doesNotContain("Paciente de teste").doesNotContain("Autorizo")
        }
    }

    @Test
    fun `revoking publishes ConsentRevoked, and a listener that fails leaves the publication pending but keeps the revocation`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val grant = idOf(accept(clinic, patient, newType(clinic)).andExpect(status().isCreated))
        mockMvc.perform(call(post("/api/v1/patients/$patient/consents/$grant/revoke"), clinic)).andExpect(status().isOk)

        await("o ouvinte receber ConsentRevoked") { recording.revoked.any { it.grantId == grant } }
        assertThat(recording.revoked.first { it.grantId == grant }).isEqualTo(ConsentRevoked(clinic.id, patient, grant))
        await("a publicação do ouvinte que funciona ser concluída") {
            publications("ConsentRevoked", grant).any { it["completion_date"] != null }
        }
        Thread.sleep(500)
        // só os dois ouvintes deste teste (o ouvinte real de notificações também registra a sua publicação)
        val rows = publications("ConsentRevoked", grant).filter { (it["listener_id"] as String).contains("ConsentEventsIT") }
        assertThat(rows.size).isEqualTo(2)
        assertThat(rows.count { it["completion_date"] == null }).describedAs("o ouvinte que falhou fica pendente para nova tentativa").isEqualTo(1)
        assertThat(rows.count { it["completion_date"] != null }).isEqualTo(1)

        // CA18 na origem: a falha do ouvinte não desfaz o que foi gravado
        val revoked = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForObject("select count(*) from registry.consent_grant where id = ? and revoked_at is not null", Int::class.java, grant) }!!
        }
        assertThat(revoked).isEqualTo(1)
    }

    @Test
    fun `a refused request publishes nothing`() {
        val clinic = newClinic()
        val patient = newPatient(clinic)
        val type = newType(clinic)
        val grant = idOf(accept(clinic, patient, type).andExpect(status().isCreated))
        await("o primeiro evento") { recording.accepted.any { it.grantId == grant } }
        val before = jdbc.queryForObject("select count(*) from eventing.event_publication", Int::class.java)!!
        val receivedBefore = recording.accepted.size

        accept(clinic, patient, type).andExpect(status().isConflict) // já aceito
        accept(clinic, UUID.randomUUID(), type).andExpect(status().isNotFound) // paciente que não existe
        accept(clinic, patient, UUID.randomUUID()).andExpect(status().isNotFound) // tipo que não existe
        Thread.sleep(500)

        assertThat(jdbc.queryForObject("select count(*) from eventing.event_publication", Int::class.java)).isEqualTo(before)
        assertThat(recording.accepted.size).isEqualTo(receivedBefore)
    }
}
