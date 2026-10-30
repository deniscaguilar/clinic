package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.registry.api.ConsentAccepted
import com.endoacademy.clinic.registry.application.AddressData
import com.endoacademy.clinic.registry.application.ConsentGrantStore
import com.endoacademy.clinic.registry.application.ConsentTypeStore
import com.endoacademy.clinic.registry.application.EventPublisher
import com.endoacademy.clinic.registry.application.PatientData
import com.endoacademy.clinic.registry.application.PatientStore
import com.endoacademy.clinic.registry.application.RegisterPatient
import com.endoacademy.clinic.registry.application.Transactions
import com.endoacademy.clinic.registry.domain.ConsentGrant
import com.endoacademy.clinic.registry.domain.ConsentType
import com.endoacademy.clinic.registry.domain.ConsentVersion
import com.endoacademy.clinic.registry.domain.Cpf
import com.endoacademy.clinic.registry.domain.Patient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** O cadastro de paciente com o aceite dos consentimentos obrigatórios (T5.5; RF5a, CA13). */
class RegisterPatientConsentTest {

    private class FakePatientStore : PatientStore {
        val rows = linkedMapOf<UUID, Patient>()
        override fun find(id: UUID) = rows[id]
        override fun findByCpf(cpf: Cpf) = rows.values.firstOrNull { it.cpf == cpf }
        override fun cpfTaken(cpf: Cpf, exceptId: UUID?) = rows.values.any { it.cpf == cpf && it.id != exceptId }
        override fun insert(clinicId: UUID, patient: Patient) {
            rows[patient.id] = patient
        }

        override fun update(patient: Patient) = error("não usado")
        override fun list(nameContains: String?, offset: Int, limit: Int) = error("não usado")
        override fun count(nameContains: String?) = error("não usado")
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

    private class FakeGrantStore : ConsentGrantStore {
        val rows = linkedMapOf<UUID, ConsentGrant>()
        override fun find(patientId: UUID, id: UUID) = rows[id]?.takeIf { it.patientId == patientId }
        override fun listByPatient(patientId: UUID) = rows.values.filter { it.patientId == patientId }
        override fun hasActive(patientId: UUID, typeId: UUID) = rows.values.any { it.patientId == patientId && it.typeId == typeId && it.active }
        override fun insert(clinicId: UUID, grant: ConsentGrant) {
            rows[grant.id] = grant
        }

        override fun revoke(grant: ConsentGrant) = error("não usado")
    }

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<AuditEvent>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += event
        }
    }

    private val clinic = UUID.randomUUID()
    private val staff = CurrentUser(UUID.randomUUID(), "sub-1", clinic, setOf("MANAGE_PATIENTS", "RECORD_CONSENT"))
    private val managerOnly = CurrentUser(UUID.randomUUID(), "sub-2", clinic, setOf("MANAGE_PATIENTS"))
    private val patients = FakePatientStore()
    private val types = FakeTypeStore()
    private val grants = FakeGrantStore()
    private val audit = RecordingAudit()
    private val published = mutableListOf<Any>()
    private val authorizer = object : Authorizer {
        override fun require(user: CurrentUser, permission: String) {
            if (!user.has(permission)) throw PermissionDeniedException(permission)
        }
    }
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var nextId = 0
    private val register = RegisterPatient(authorizer, patients, types, grants, EventPublisher { published += it }, audit, transactions, clock) { UUID(0, (++nextId).toLong()) }

    private val required = ConsentType.create(UUID.randomUUID(), "Cadastro e mensagens", "Autorizo.", requiredOnSignup = true, controlsMessaging = true, now = now)
    private val optional = ConsentType.create(UUID.randomUUID(), "Uso de imagem", "Autorizo a imagem.", requiredOnSignup = false, controlsMessaging = false, now = now)

    private fun data() = PatientData(
        "Maria da Silva", LocalDate.of(1985, 3, 12), "(11) 98765-4321", "maria@example.com", null,
        AddressData("01310-100", "Avenida Paulista", "1578", null, "Bela Vista", "São Paulo", "SP"),
    )

    private fun withTypes(vararg all: ConsentType) = all.forEach { types.rows[it.id] = it }

    private fun assertApi(kind: ErrorKind, code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(kind)
            assertThat(it.code).isEqualTo(code)
        }
    }

    private fun assertNothingWritten() {
        assertThat(patients.rows).isEmpty()
        assertThat(grants.rows).isEmpty()
        assertThat(published).isEmpty()
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `without a required consent the patient is refused and nothing is written (CA13)`() {
        withTypes(required, optional)
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_REQUIRED") { register.register(staff, "529.982.247-25", data(), emptyList()) }
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_REQUIRED") { register.register(staff, "529.982.247-25", data(), listOf(optional.id)) }
        assertNothingWritten()
    }

    @Test
    fun `with every required consent the patient and the acceptances are written together, with events and audit`() {
        withTypes(required, optional)
        val registration = register.register(staff, "529.982.247-25", data(), listOf(required.id, optional.id))

        assertThat(patients.rows).containsKey(registration.patient.id)
        assertThat(registration.consents).hasSize(2)
        assertThat(grants.rows.values.map { it.typeId }).containsExactlyInAnyOrder(required.id, optional.id)
        grants.rows.values.forEach {
            assertThat(it.patientId).isEqualTo(registration.patient.id)
            assertThat(it.versionNumber).isEqualTo(1)
            assertThat(it.acceptedAt).isEqualTo(now)
            assertThat(it.acceptedBy).isEqualTo(staff.userId)
            assertThat(it.active).isTrue()
        }
        assertThat(published).containsExactlyInAnyOrderElementsOf(grants.rows.values.map { ConsentAccepted(clinic, registration.patient.id, it.id) })
        assertThat(audit.events.map { it.action }).containsExactlyInAnyOrder("REGISTER_PATIENT", "ACCEPT_CONSENT", "ACCEPT_CONSENT")
    }

    @Test
    fun `every required consent is needed, accepting only some of them is not enough`() {
        val secondRequired = ConsentType.create(UUID.randomUUID(), "Tratamento de dados", "Autorizo o tratamento.", requiredOnSignup = true, controlsMessaging = false, now = now)
        withTypes(required, secondRequired, optional)
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_REQUIRED") { register.register(staff, "529.982.247-25", data(), listOf(required.id)) }
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_REQUIRED") { register.register(staff, "529.982.247-25", data(), listOf(secondRequired.id, optional.id)) }
        assertNothingWritten()
        assertThat(register.register(staff, "529.982.247-25", data(), listOf(required.id, secondRequired.id)).consents).hasSize(2)
    }

    @Test
    fun `an optional consent may be left out, and a clinic with no required type needs no acceptance`() {
        withTypes(required, optional)
        val onlyRequired = register.register(staff, "529.982.247-25", data(), listOf(required.id))
        assertThat(onlyRequired.consents.map { it.typeId }).containsExactly(required.id)

        val emptyClinic = FakeTypeStore().also { it.rows[optional.id] = optional }
        val relaxed = RegisterPatient(authorizer, FakePatientStore(), emptyClinic, FakeGrantStore(), EventPublisher { }, RecordingAudit(), transactions, clock)
        assertThat(relaxed.register(managerOnly, "111.444.777-35", data(), emptyList()).consents).isEmpty()
    }

    @Test
    fun `an inactive required type is not required, and an inactive type cannot be accepted`() {
        val inactive = required.update(required.name, requiredOnSignup = true, controlsMessaging = false, active = false)
        withTypes(inactive, optional)
        assertThat(register.register(staff, "529.982.247-25", data(), emptyList()).consents).isEmpty()
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_TYPE_INACTIVE") { register.register(staff, "111.444.777-35", data(), listOf(inactive.id)) }
        assertThat(patients.rows).hasSize(1)
    }

    @Test
    fun `an unknown consent type is refused and nothing is written`() {
        withTypes(required)
        assertApi(ErrorKind.NOT_FOUND, "CONSENT_TYPE_NOT_FOUND") { register.register(staff, "529.982.247-25", data(), listOf(required.id, UUID.randomUUID())) }
        assertNothingWritten()
    }

    @Test
    fun `repeating a type in the list records it once`() {
        withTypes(required)
        val registration = register.register(staff, "529.982.247-25", data(), listOf(required.id, required.id, required.id))
        assertThat(registration.consents).hasSize(1)
        assertThat(grants.rows).hasSize(1)
        assertThat(published).hasSize(1)
    }

    @Test
    fun `an invalid patient or a duplicate CPF writes no acceptance at all`() {
        withTypes(required)
        assertApi(ErrorKind.UNPROCESSABLE, "CPF_INVALID") { register.register(staff, "529.982.247-24", data(), listOf(required.id)) }
        assertApi(ErrorKind.UNPROCESSABLE, "PATIENT_CONTACT_REQUIRED") { register.register(staff, "529.982.247-25", data().copy(whatsapp = null, email = null), listOf(required.id)) }
        assertNothingWritten()
        register.register(staff, "529.982.247-25", data(), listOf(required.id))
        patients.rows.values.forEach { _ -> }
        val grantsBefore = grants.rows.size
        published.clear()
        audit.events.clear()
        assertApi(ErrorKind.CONFLICT, "CPF_ALREADY_REGISTERED") { register.register(staff, "52998224725", data(), listOf(required.id)) }
        assertThat(grants.rows).hasSize(grantsBefore)
        assertThat(published).isEmpty()
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `recording the acceptance needs RECORD_CONSENT in addition to MANAGE_PATIENTS`() {
        withTypes(required, optional)
        assertThatThrownBy { register.register(managerOnly, "529.982.247-25", data(), listOf(required.id)) }
            .isInstanceOfSatisfying(PermissionDeniedException::class.java) { assertThat(it.permission).isEqualTo("RECORD_CONSENT") }
        assertThatThrownBy { register.register(managerOnly, "529.982.247-25", data(), emptyList()) }.isInstanceOf(PermissionDeniedException::class.java)
        assertNothingWritten()
    }

    @Test
    fun `an acceptance is tied to the version in force when the patient was registered`() {
        withTypes(required.revise("Texto novo.", now))
        val registration = register.register(staff, "529.982.247-25", data(), listOf(required.id))
        assertThat(registration.consents.single().versionNumber).isEqualTo(2)
    }
}
