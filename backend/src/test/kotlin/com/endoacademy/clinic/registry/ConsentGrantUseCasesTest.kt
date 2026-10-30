package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.registry.api.ConsentAccepted
import com.endoacademy.clinic.registry.api.ConsentRevoked
import com.endoacademy.clinic.registry.application.AcceptConsent
import com.endoacademy.clinic.registry.application.ConsentGrantStore
import com.endoacademy.clinic.registry.application.ConsentTypeStore
import com.endoacademy.clinic.registry.application.EventPublisher
import com.endoacademy.clinic.registry.application.ListPatientConsents
import com.endoacademy.clinic.registry.application.PatientStore
import com.endoacademy.clinic.registry.application.RevokeConsent
import com.endoacademy.clinic.registry.application.Transactions
import com.endoacademy.clinic.registry.domain.Address
import com.endoacademy.clinic.registry.domain.ConsentGrant
import com.endoacademy.clinic.registry.domain.ConsentType
import com.endoacademy.clinic.registry.domain.ConsentVersion
import com.endoacademy.clinic.registry.domain.Cpf
import com.endoacademy.clinic.registry.domain.Email
import com.endoacademy.clinic.registry.domain.Patient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ConsentGrantUseCasesTest {

    private class FakeGrantStore : ConsentGrantStore {
        val rows = linkedMapOf<UUID, Pair<UUID, ConsentGrant>>() // id -> (clinic, grant)
        override fun find(patientId: UUID, id: UUID) = rows[id]?.second?.takeIf { it.patientId == patientId }
        override fun listByPatient(patientId: UUID) = rows.values.map { it.second }.filter { it.patientId == patientId }.sortedByDescending { it.acceptedAt }
        override fun hasActive(patientId: UUID, typeId: UUID) = rows.values.any { it.second.patientId == patientId && it.second.typeId == typeId && it.second.active }
        override fun insert(clinicId: UUID, grant: ConsentGrant) {
            rows[grant.id] = clinicId to grant
        }

        override fun revoke(grant: ConsentGrant) {
            rows[grant.id] = rows.getValue(grant.id).first to grant
        }
    }

    private class FakeTypeStore : ConsentTypeStore {
        val rows = linkedMapOf<UUID, ConsentType>()
        override fun list(active: Boolean?) = rows.values.filter { active == null || it.active == active }
        override fun find(id: UUID) = rows[id]
        override fun insert(clinicId: UUID, type: ConsentType) = error("não usado")
        override fun update(type: ConsentType) = error("não usado")
        override fun addVersion(typeId: UUID, version: ConsentVersion) = error("não usado")
        override fun nameTaken(name: String, exceptId: UUID?) = error("não usado")
    }

    private class FakePatientStore : PatientStore {
        val rows = linkedMapOf<UUID, Patient>()
        override fun find(id: UUID) = rows[id]
        override fun findByCpf(cpf: Cpf) = error("não usado")
        override fun cpfTaken(cpf: Cpf, exceptId: UUID?) = error("não usado")
        override fun insert(clinicId: UUID, patient: Patient) = error("não usado")
        override fun update(patient: Patient) = error("não usado")
        override fun list(nameContains: String?, offset: Int, limit: Int) = error("não usado")
        override fun count(nameContains: String?) = error("não usado")
    }

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<AuditEvent>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += event
        }
    }

    private val clinic = UUID.randomUUID()
    private val recorder = CurrentUser(UUID.randomUUID(), "sub-1", clinic, setOf("RECORD_CONSENT"))
    private val viewer = CurrentUser(UUID.randomUUID(), "sub-2", clinic, setOf("VIEW_PATIENTS"))
    private val outsider = CurrentUser(UUID.randomUUID(), "sub-3", clinic, setOf("MANAGE_PROCEDURES"))
    private val grants = FakeGrantStore()
    private val types = FakeTypeStore()
    private val patients = FakePatientStore()
    private val audit = RecordingAudit()
    private val published = mutableListOf<Any>()
    private val publisher = EventPublisher { published += it }
    private val authorizer = object : Authorizer {
        override fun require(user: CurrentUser, permission: String) {
            if (!user.has(permission)) throw PermissionDeniedException(permission)
        }
    }
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private var now = Instant.parse("2026-10-30T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant(): Instant = now
    }
    private var nextId = 0
    private val accept = AcceptConsent(authorizer, patients, types, grants, publisher, audit, transactions, clock) { UUID(0, (++nextId).toLong()) }
    private val revoke = RevokeConsent(authorizer, grants, publisher, audit, transactions, clock)
    private val list = ListPatientConsents(authorizer, patients, types, grants, audit, transactions)

    private val patient = Patient.register(
        UUID.randomUUID(), "Maria da Silva", Cpf.parse("529.982.247-25"), LocalDate.of(1985, 3, 12), null, Email.parse("maria@example.com"), null,
        Address.of("01310-100", "Avenida Paulista", "1578", null, "Bela Vista", "São Paulo", "SP"), Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC),
    )
    private val messagingType = ConsentType.create(UUID.randomUUID(), "Mensagens", "Autorizo.", true, true, now)
    private val imageType = ConsentType.create(UUID.randomUUID(), "Imagem", "Autorizo a imagem.", false, false, now)

    init {
        patients.rows[patient.id] = patient
        types.rows[messagingType.id] = messagingType
        types.rows[imageType.id] = imageType
    }

    private fun assertApi(kind: ErrorKind, code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(kind)
            assertThat(it.code).isEqualTo(code)
        }
    }

    // --- aceitar ---

    @Test
    fun `accepting records patient, type, current version, time and user, publishes the event and audits`() {
        val grant = accept.accept(recorder, patient.id, messagingType.id)
        assertThat(grant.patientId).isEqualTo(patient.id)
        assertThat(grant.typeId).isEqualTo(messagingType.id)
        assertThat(grant.versionNumber).isEqualTo(1)
        assertThat(grant.acceptedAt).isEqualTo(now)
        assertThat(grant.acceptedBy).isEqualTo(recorder.userId)
        assertThat(grants.rows.getValue(grant.id)).isEqualTo(clinic to grant)
        assertThat(published).containsExactly(ConsentAccepted(clinic, patient.id, grant.id))
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("ACCEPT_CONSENT")
            assertThat(it.entityType).isEqualTo("CONSENT_GRANT")
            assertThat(it.entityId).isEqualTo(grant.id.toString())
            assertThat(it.details).isEmpty()
        })
    }

    @Test
    fun `the acceptance is tied to the version in force, even when the text changes later (CA15)`() {
        val first = accept.accept(recorder, patient.id, imageType.id)
        types.rows[imageType.id] = imageType.revise("Autorizo a imagem e a voz.", now)
        val second = accept.accept(recorder, patient.id, messagingType.id)
        assertThat(first.versionNumber).isEqualTo(1)
        assertThat(second.versionNumber).isEqualTo(1)
        revoke.revoke(recorder, patient.id, first.id)
        val renewed = accept.accept(recorder, patient.id, imageType.id)
        assertThat(renewed.versionNumber).isEqualTo(2)
        assertThat(grants.rows.getValue(first.id).second.versionNumber).isEqualTo(1)
    }

    @Test
    fun `accepting needs RECORD_CONSENT and nothing happens without it`() {
        assertThatThrownBy { accept.accept(viewer, patient.id, messagingType.id) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { accept.accept(outsider, patient.id, messagingType.id) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThat(grants.rows).isEmpty()
        assertThat(published).isEmpty()
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `an unknown patient or type answers 404`() {
        assertApi(ErrorKind.NOT_FOUND, "PATIENT_NOT_FOUND") { accept.accept(recorder, UUID.randomUUID(), messagingType.id) }
        assertApi(ErrorKind.NOT_FOUND, "CONSENT_TYPE_NOT_FOUND") { accept.accept(recorder, patient.id, UUID.randomUUID()) }
        assertThat(grants.rows).isEmpty()
        assertThat(published).isEmpty()
    }

    @Test
    fun `an inactive type cannot be accepted`() {
        types.rows[imageType.id] = imageType.update("Imagem", false, false, active = false)
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_TYPE_INACTIVE") { accept.accept(recorder, patient.id, imageType.id) }
        assertThat(grants.rows).isEmpty()
        assertThat(published).isEmpty()
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `a type already accepted and not revoked cannot be accepted again`() {
        accept.accept(recorder, patient.id, messagingType.id)
        published.clear()
        audit.events.clear()
        assertApi(ErrorKind.CONFLICT, "CONSENT_ALREADY_ACCEPTED") { accept.accept(recorder, patient.id, messagingType.id) }
        assertThat(grants.rows).hasSize(1)
        assertThat(published).isEmpty()
        assertThat(audit.events).isEmpty()
        accept.accept(recorder, patient.id, imageType.id) // outro tipo pode
    }

    // --- revogar ---

    @Test
    fun `revoking fills who and when, publishes the event and audits, and keeps the acceptance`() {
        val grant = accept.accept(recorder, patient.id, messagingType.id)
        published.clear()
        audit.events.clear()
        now = Instant.parse("2026-10-30T11:00:00Z")
        val revoked = revoke.revoke(recorder, patient.id, grant.id)
        assertThat(revoked.revokedAt).isEqualTo(now)
        assertThat(revoked.revokedBy).isEqualTo(recorder.userId)
        assertThat(revoked.acceptedAt).isEqualTo(grant.acceptedAt)
        assertThat(revoked.active).isFalse()
        assertThat(grants.rows.getValue(grant.id).second).isEqualTo(revoked)
        assertThat(published).containsExactly(ConsentRevoked(clinic, patient.id, grant.id))
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("REVOKE_CONSENT")
            assertThat(it.entityId).isEqualTo(grant.id.toString())
        })
    }

    @Test
    fun `the revocation records who revoked, which may be someone other than who accepted`() {
        val other = CurrentUser(UUID.randomUUID(), "sub-9", clinic, setOf("RECORD_CONSENT"))
        val grant = accept.accept(recorder, patient.id, messagingType.id)
        val revoked = revoke.revoke(other, patient.id, grant.id)
        assertThat(revoked.acceptedBy).isEqualTo(recorder.userId)
        assertThat(revoked.revokedBy).isEqualTo(other.userId)
        assertThat(grants.rows.getValue(grant.id).second.revokedBy).isEqualTo(other.userId)
    }

    @Test
    fun `revoking twice is a conflict, an unknown acceptance is 404 and another patient's acceptance is 404 too`() {
        val grant = accept.accept(recorder, patient.id, messagingType.id)
        revoke.revoke(recorder, patient.id, grant.id)
        published.clear()
        audit.events.clear()
        assertApi(ErrorKind.CONFLICT, "CONSENT_ALREADY_REVOKED") { revoke.revoke(recorder, patient.id, grant.id) }
        assertApi(ErrorKind.NOT_FOUND, "CONSENT_NOT_FOUND") { revoke.revoke(recorder, patient.id, UUID.randomUUID()) }
        val other = accept.accept(recorder, patient.id, imageType.id)
        assertApi(ErrorKind.NOT_FOUND, "CONSENT_NOT_FOUND") { revoke.revoke(recorder, UUID.randomUUID(), other.id) }
        assertThat(grants.rows.getValue(other.id).second.active).isTrue()
        assertThat(published.filterIsInstance<ConsentRevoked>()).isEmpty()
    }

    @Test
    fun `revoking needs RECORD_CONSENT`() {
        val grant = accept.accept(recorder, patient.id, messagingType.id)
        published.clear()
        assertThatThrownBy { revoke.revoke(viewer, patient.id, grant.id) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { revoke.revoke(outsider, patient.id, grant.id) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThat(grants.rows.getValue(grant.id).second.active).isTrue()
        assertThat(published).isEmpty()
    }

    // --- listar ---

    @Test
    fun `listing shows the acceptances with the type name, newest first, and audits one event with the count`() {
        val first = accept.accept(recorder, patient.id, messagingType.id)
        now = Instant.parse("2026-10-30T11:00:00Z")
        val second = accept.accept(recorder, patient.id, imageType.id)
        audit.events.clear()
        val views = list.list(viewer, patient.id)
        assertThat(views.map { it.grant.id }).containsExactly(second.id, first.id)
        assertThat(views.map { it.typeName }).containsExactly("Imagem", "Mensagens")
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("LIST_PATIENT_CONSENTS")
            assertThat(it.entityType).isEqualTo("PATIENT")
            assertThat(it.entityId).isEqualTo(patient.id.toString())
            assertThat(it.details).containsExactly("count.2")
        })
        assertThat(list.list(recorder, patient.id)).hasSize(2)
    }

    @Test
    fun `listing needs VIEW_PATIENTS or RECORD_CONSENT, and an unknown patient is 404`() {
        assertThatThrownBy { list.list(outsider, patient.id) }.isInstanceOf(PermissionDeniedException::class.java)
        assertApi(ErrorKind.NOT_FOUND, "PATIENT_NOT_FOUND") { list.list(viewer, UUID.randomUUID()) }
        assertThat(audit.events).isEmpty()
    }
}
