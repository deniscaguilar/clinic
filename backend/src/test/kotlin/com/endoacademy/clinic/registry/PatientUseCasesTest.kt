package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.registry.application.AddressData
import com.endoacademy.clinic.registry.application.ConsentGrantStore
import com.endoacademy.clinic.registry.application.ConsentTypeStore
import com.endoacademy.clinic.registry.application.GetPatient
import com.endoacademy.clinic.registry.application.ListPatients
import com.endoacademy.clinic.registry.application.PatientData
import com.endoacademy.clinic.registry.application.PatientStore
import com.endoacademy.clinic.registry.application.RegisterPatient
import com.endoacademy.clinic.registry.application.SearchPatientByCpf
import com.endoacademy.clinic.registry.application.Transactions
import com.endoacademy.clinic.registry.application.UpdatePatient
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

class PatientUseCasesTest {

    private class FakePatientStore : PatientStore {
        val rows = linkedMapOf<UUID, Patient>()
        override fun find(id: UUID) = rows[id]
        override fun findByCpf(cpf: Cpf) = rows.values.firstOrNull { it.cpf == cpf }
        override fun cpfTaken(cpf: Cpf, exceptId: UUID?) = rows.values.any { it.cpf == cpf && it.id != exceptId }
        override fun insert(clinicId: UUID, patient: Patient) {
            rows[patient.id] = patient
        }

        override fun update(patient: Patient) {
            rows[patient.id] = patient
        }

        private fun filtered(nameContains: String?) = rows.values.filter { nameContains == null || it.fullName.contains(nameContains, ignoreCase = true) }.sortedBy { it.fullName.lowercase() }
        override fun list(nameContains: String?, offset: Int, limit: Int) = filtered(nameContains).drop(offset).take(limit)
        override fun count(nameContains: String?) = filtered(nameContains).size.toLong()
    }

    private class FakeConsentTypes : ConsentTypeStore {
        override fun list(active: Boolean?) = emptyList<ConsentType>()
        override fun find(id: UUID): ConsentType? = null
        override fun insert(clinicId: UUID, type: ConsentType) = error("não usado")
        override fun update(type: ConsentType) = error("não usado")
        override fun addVersion(typeId: UUID, version: ConsentVersion) = error("não usado")
        override fun nameTaken(name: String, exceptId: UUID?) = error("não usado")
    }

    private class FakeConsentGrants : ConsentGrantStore {
        override fun find(patientId: UUID, id: UUID): ConsentGrant? = null
        override fun listByPatient(patientId: UUID) = emptyList<ConsentGrant>()
        override fun hasActive(patientId: UUID, typeId: UUID) = false
        override fun insert(clinicId: UUID, grant: ConsentGrant) = error("este teste não tem tipos obrigatórios")
        override fun revoke(grant: ConsentGrant) = error("não usado")
    }

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<AuditEvent>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += event
        }
    }

    private val clinic = UUID.randomUUID()
    private val staff = CurrentUser(UUID.randomUUID(), "sub-1", clinic, setOf("VIEW_PATIENTS", "MANAGE_PATIENTS"))
    private val admin = CurrentUser(UUID.randomUUID(), "sub-2", clinic, setOf("VIEW_PATIENTS", "MANAGE_PATIENTS", "CHANGE_PATIENT_CPF"))
    private val viewer = CurrentUser(UUID.randomUUID(), "sub-3", clinic, setOf("VIEW_PATIENTS"))
    private val nobody = CurrentUser(UUID.randomUUID(), "sub-4", clinic, setOf("MANAGE_PROCEDURES"))
    private val store = FakePatientStore()
    private val audit = RecordingAudit()
    private val authorizer = object : Authorizer {
        override fun require(user: CurrentUser, permission: String) {
            if (!user.has(permission)) throw PermissionDeniedException(permission)
        }
    }
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private val clock = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC)
    private var nextId = 0
    private val registerUseCase = RegisterPatient(authorizer, store, FakeConsentTypes(), FakeConsentGrants(), { }, audit, transactions, clock) { UUID(0, (++nextId).toLong()) }

    /** Cadastro sem tipos de consentimento obrigatórios na clínica: devolve só o paciente. */
    private fun registerPatient(user: CurrentUser, cpf: String?, data: PatientData) = registerUseCase.register(user, cpf, data, emptyList()).patient
    private val update = UpdatePatient(authorizer, store, audit, transactions, clock)
    private val get = GetPatient(authorizer, store, audit, transactions)
    private val list = ListPatients(authorizer, store, audit, transactions)
    private val search = SearchPatientByCpf(authorizer, store, audit, transactions)

    private val address = AddressData("01310-100", "Avenida Paulista", "1578", null, "Bela Vista", "São Paulo", "SP")

    private fun data(
        name: String = "Maria da Silva",
        birthDate: LocalDate? = LocalDate.of(1985, 3, 12),
        whatsapp: String? = "(11) 98765-4321",
        email: String? = "maria@example.com",
        landline: String? = null,
        address: AddressData? = this.address,
    ) = PatientData(name, birthDate, whatsapp, email, landline, address)

    private fun assertApi(kind: ErrorKind, code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(kind)
            assertThat(it.code).isEqualTo(code)
        }
    }

    private val cpfA = "529.982.247-25"
    private val cpfB = "111.444.777-35"

    // --- cadastro ---

    @Test
    fun `registering parses the raw fields, stores the patient and audits only field names`() {
        val created = registerPatient(staff, cpfA, data(landline = "(11) 3456-7890"))
        assertThat(created.cpf.value).isEqualTo("52998224725")
        assertThat(created.whatsapp!!.value).isEqualTo("11987654321")
        assertThat(created.email!!.value).isEqualTo("maria@example.com")
        assertThat(created.address.zipCode).isEqualTo("01310100")
        assertThat(store.rows).containsKey(created.id)
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("REGISTER_PATIENT")
            assertThat(it.entityType).isEqualTo("PATIENT")
            assertThat(it.entityId).isEqualTo(created.id.toString())
            assertThat(it.details).containsExactlyInAnyOrder("fullName", "cpf", "birthDate", "whatsapp", "email", "landline", "address")
        })
    }

    @Test
    fun `empty optional fields are treated as absent`() {
        val onlyEmail = registerPatient(staff, cpfA, data(whatsapp = "  ", email = "maria@example.com", landline = " "))
        assertThat(onlyEmail.whatsapp).isNull()
        assertThat(onlyEmail.landline).isNull()
        val onlyWhatsapp = registerPatient(staff, cpfB, data(name = "João Pereira", whatsapp = "(11) 98765-4321", email = "", landline = ""))
        assertThat(onlyWhatsapp.email).isNull()
        // em branco não conta como contato
        assertApi(ErrorKind.UNPROCESSABLE, "PATIENT_CONTACT_REQUIRED") { registerPatient(staff, "000.000.006-04", data(whatsapp = " ", email = "", landline = "(11) 3456-7890")) }
    }

    @Test
    fun `registering needs MANAGE_PATIENTS and a viewer or outsider stores nothing`() {
        assertThatThrownBy { registerPatient(viewer, cpfA, data()) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { registerPatient(nobody, cpfA, data()) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThat(store.rows).isEmpty()
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `an invalid or duplicate CPF is refused and nothing is stored (CA14)`() {
        assertApi(ErrorKind.UNPROCESSABLE, "CPF_INVALID") { registerPatient(staff, "529.982.247-24", data()) }
        assertApi(ErrorKind.UNPROCESSABLE, "CPF_INVALID") { registerPatient(staff, "111.111.111-11", data()) }
        assertApi(ErrorKind.UNPROCESSABLE, "CPF_INVALID") { registerPatient(staff, "", data()) }
        registerPatient(staff, cpfA, data())
        audit.events.clear()
        assertApi(ErrorKind.CONFLICT, "CPF_ALREADY_REGISTERED") { registerPatient(staff, "52998224725", data(name = "Outra Pessoa")) }
        assertThat(store.rows).hasSize(1)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `each missing required field is refused with its own code (CA13)`() {
        assertApi(ErrorKind.UNPROCESSABLE, "PATIENT_FULL_NAME_REQUIRED") { registerPatient(staff, cpfA, data(name = "Maria")) }
        assertApi(ErrorKind.UNPROCESSABLE, "PATIENT_BIRTH_DATE_INVALID") { registerPatient(staff, cpfA, data(birthDate = null)) }
        assertApi(ErrorKind.UNPROCESSABLE, "PATIENT_BIRTH_DATE_INVALID") { registerPatient(staff, cpfA, data(birthDate = LocalDate.of(2026, 10, 30))) }
        assertApi(ErrorKind.UNPROCESSABLE, "PATIENT_ADDRESS_REQUIRED") { registerPatient(staff, cpfA, data(address = null)) }
        assertApi(ErrorKind.UNPROCESSABLE, "PATIENT_CONTACT_REQUIRED") { registerPatient(staff, cpfA, data(whatsapp = null, email = null)) }
        assertApi(ErrorKind.UNPROCESSABLE, "PATIENT_WHATSAPP_NOT_MOBILE") { registerPatient(staff, cpfA, data(whatsapp = "(11) 3456-7890")) }
        assertApi(ErrorKind.UNPROCESSABLE, "EMAIL_INVALID") { registerPatient(staff, cpfA, data(email = "sem-arroba")) }
        assertApi(ErrorKind.UNPROCESSABLE, "PHONE_INVALID") { registerPatient(staff, cpfA, data(whatsapp = "123")) }
        assertApi(ErrorKind.UNPROCESSABLE, "CEP_INVALID") { registerPatient(staff, cpfA, data(address = address.copy(zipCode = "123"))) }
        assertApi(ErrorKind.UNPROCESSABLE, "UF_INVALID") { registerPatient(staff, cpfA, data(address = address.copy(state = "XX"))) }
        assertApi(ErrorKind.UNPROCESSABLE, "ADDRESS_STREET_REQUIRED") { registerPatient(staff, cpfA, data(address = address.copy(street = " "))) }
        assertThat(store.rows).isEmpty()
        assertThat(audit.events).isEmpty()
    }

    // --- atualização ---

    @Test
    fun `updating changes the data and audits only the names of the fields that changed`() {
        val created = registerPatient(staff, cpfA, data())
        audit.events.clear()
        val updated = update.update(staff, created.id, null, data(name = "Maria da Silva Souza", whatsapp = "(11) 98765-4321", email = "maria@example.com"))
        assertThat(updated.fullName).isEqualTo("Maria da Silva Souza")
        assertThat(updated.cpf).isEqualTo(created.cpf)
        assertThat(store.rows.getValue(created.id)).isEqualTo(updated)
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("UPDATE_PATIENT")
            assertThat(it.entityId).isEqualTo(created.id.toString())
            assertThat(it.details).containsExactly("fullName")
        })
    }

    @Test
    fun `the audit of an update names exactly the fields that changed, one at a time`() {
        val created = registerPatient(staff, cpfA, data(landline = "(11) 3456-7890"))
        val base = data(landline = "(11) 3456-7890")
        val cases = mapOf(
            "fullName" to base.copy(fullName = "Maria da Silva Souza"),
            "birthDate" to base.copy(birthDate = LocalDate.of(1985, 3, 13)),
            "whatsapp" to base.copy(whatsapp = "(11) 91234-5678"),
            "email" to base.copy(email = "outro@example.com"),
            "landline" to base.copy(landline = "(11) 3999-0000"),
            "address" to base.copy(address = address.copy(city = "Campinas")),
        )
        cases.forEach { (field, changed) ->
            audit.events.clear()
            update.update(staff, created.id, null, changed)
            assertThat(audit.events.single().details).describedAs(field).containsExactly(field)
            update.update(staff, created.id, null, base) // volta ao original
        }
        audit.events.clear()
        update.update(staff, created.id, null, base)
        assertThat(audit.events.single().details).isEmpty()
        update.update(staff, created.id, null, base.copy(fullName = "Maria Souza", email = null, address = address.copy(number = "9")))
        assertThat(audit.events.last().details).containsExactlyInAnyOrder("fullName", "email", "address")
    }

    @Test
    fun `updating needs MANAGE_PATIENTS, an unknown patient is 404 and invalid data keeps the stored one`() {
        val created = registerPatient(staff, cpfA, data())
        audit.events.clear()
        assertThatThrownBy { update.update(viewer, created.id, null, data(name = "Outro Nome")) }.isInstanceOf(PermissionDeniedException::class.java)
        assertApi(ErrorKind.NOT_FOUND, "PATIENT_NOT_FOUND") { update.update(staff, UUID.randomUUID(), null, data()) }
        assertApi(ErrorKind.UNPROCESSABLE, "PATIENT_CONTACT_REQUIRED") { update.update(staff, created.id, null, data(whatsapp = null, email = null)) }
        assertThat(store.rows.getValue(created.id)).isEqualTo(created)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `the CPF only changes with CHANGE_PATIENT_CPF and the change is audited separately`() {
        val created = registerPatient(staff, cpfA, data())
        audit.events.clear()
        assertThatThrownBy { update.update(staff, created.id, cpfB, data()) }.isInstanceOfSatisfying(PermissionDeniedException::class.java) {
            assertThat(it.permission).isEqualTo("CHANGE_PATIENT_CPF")
        }
        assertThat(store.rows.getValue(created.id).cpf.value).isEqualTo("52998224725")
        assertThat(audit.events).isEmpty()

        val changed = update.update(admin, created.id, cpfB, data())
        assertThat(changed.cpf.value).isEqualTo("11144477735")
        assertThat(audit.events.map { it.action }).containsExactly("UPDATE_PATIENT", "CHANGE_PATIENT_CPF")
        assertThat(audit.events.last().details).containsExactly("cpf")
        assertThat(audit.events.last().entityId).isEqualTo(created.id.toString())
    }

    @Test
    fun `sending the same CPF, in any format, or none needs no extra permission`() {
        val created = registerPatient(staff, cpfA, data())
        assertThat(update.update(staff, created.id, "52998224725", data(name = "Maria Souza Silva")).cpf).isEqualTo(created.cpf)
        assertThat(update.update(staff, created.id, cpfA, data(name = "Maria Souza")).cpf).isEqualTo(created.cpf)
        assertThat(update.update(staff, created.id, null, data(name = "Maria Silva")).cpf).isEqualTo(created.cpf)
        assertThat(audit.events.map { it.action }).doesNotContain("CHANGE_PATIENT_CPF")
    }

    @Test
    fun `a CPF change to an invalid or already registered CPF is refused`() {
        val first = registerPatient(staff, cpfA, data())
        val second = registerPatient(staff, cpfB, data(name = "João Pereira"))
        audit.events.clear()
        assertApi(ErrorKind.UNPROCESSABLE, "CPF_INVALID") { update.update(admin, first.id, "123", data()) }
        assertApi(ErrorKind.CONFLICT, "CPF_ALREADY_REGISTERED") { update.update(admin, first.id, cpfB, data()) }
        assertThat(store.rows.getValue(first.id).cpf.value).isEqualTo("52998224725")
        assertThat(store.rows.getValue(second.id).cpf.value).isEqualTo("11144477735")
        assertThat(audit.events).isEmpty()
    }

    // --- leitura ---

    @Test
    fun `reading one patient needs VIEW_PATIENTS and audits one read per patient`() {
        val created = registerPatient(staff, cpfA, data())
        audit.events.clear()
        assertThat(get.get(viewer, created.id)).isEqualTo(created)
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("READ_PATIENT")
            assertThat(it.entityId).isEqualTo(created.id.toString())
            assertThat(it.details).isEmpty()
        })
        get.get(viewer, created.id)
        assertThat(audit.events).hasSize(2)
        assertThatThrownBy { get.get(nobody, created.id) }.isInstanceOf(PermissionDeniedException::class.java)
        assertApi(ErrorKind.NOT_FOUND, "PATIENT_NOT_FOUND") { get.get(viewer, UUID.randomUUID()) }
        assertThat(audit.events).hasSize(2)
    }

    @Test
    fun `listing audits a single event with the count, filters by name and pages`() {
        listOf("Ana Souza", "Beatriz Silva", "Carlos da Silva").forEachIndexed { index, name ->
            registerPatient(staff, listOf("529.982.247-25", "111.444.777-35", "000.000.006-04")[index], data(name = name))
        }
        audit.events.clear()
        val all = list.list(viewer, null, null, null)
        assertThat(all.items.map { it.fullName }).containsExactly("Ana Souza", "Beatriz Silva", "Carlos da Silva")
        assertThat(all.total).isEqualTo(3)
        assertThat(all.page).isZero()
        assertThat(all.size).isEqualTo(20)
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("LIST_PATIENTS")
            assertThat(it.entityId).isNull()
            assertThat(it.details).containsExactly("count.3")
        })

        audit.events.clear()
        val filtered = list.list(viewer, "  silva ", 0, 10)
        assertThat(filtered.items.map { it.fullName }).containsExactly("Beatriz Silva", "Carlos da Silva")
        assertThat(filtered.total).isEqualTo(2)
        assertThat(audit.events.single().details).containsExactly("count.2")

        val page = list.list(viewer, null, 1, 2)
        assertThat(page.items.map { it.fullName }).containsExactly("Carlos da Silva")
        assertThat(page.total).isEqualTo(3)
        assertThat(list.list(viewer, "   ", null, null).total).isEqualTo(3)
    }

    @Test
    fun `listing refuses bad paging and a long filter, and needs VIEW_PATIENTS`() {
        listOf(Triple(null, -1, 10), Triple(null, 0, 0), Triple(null, 0, 101), Triple("x".repeat(151), 0, 10)).forEach { (name, page, size) ->
            assertApi(ErrorKind.BAD_REQUEST, "VALIDATION_FAILED") { list.list(viewer, name, page, size) }
        }
        assertThat(list.list(viewer, null, 0, 100).size).isEqualTo(100)
        assertThatThrownBy { list.list(nobody, null, null, null) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThat(audit.events).hasSize(1)
    }

    @Test
    fun `searching by CPF finds the patient in any format and audits the search and the read without the CPF`() {
        val created = registerPatient(staff, cpfA, data())
        audit.events.clear()
        assertThat(search.search(viewer, "52998224725")).isEqualTo(created)
        assertThat(search.search(viewer, "529.982.247-25")).isEqualTo(created)
        assertThat(audit.events.map { it.action }).containsExactly("SEARCH_PATIENT_BY_CPF", "READ_PATIENT", "SEARCH_PATIENT_BY_CPF", "READ_PATIENT")
        assertThat(audit.events[0].details).containsExactly("count.1")
        assertThat(audit.events[0].entityId).isNull()
        assertThat(audit.events[1].entityId).isEqualTo(created.id.toString())
        audit.events.forEach { assertThat(it.toString()).doesNotContain("52998224725").doesNotContain("529.982") }
    }

    @Test
    fun `searching an unknown or invalid CPF finds nothing, and needs VIEW_PATIENTS`() {
        registerPatient(staff, cpfA, data())
        audit.events.clear()
        assertThat(search.search(viewer, cpfB)).isNull()
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("SEARCH_PATIENT_BY_CPF")
            assertThat(it.details).containsExactly("count.0")
        })
        assertApi(ErrorKind.UNPROCESSABLE, "CPF_INVALID") { search.search(viewer, "123") }
        assertThatThrownBy { search.search(nobody, cpfA) }.isInstanceOf(PermissionDeniedException::class.java)
    }
}
