package com.endoacademy.clinic.registry.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.api.ConsentAccepted
import com.endoacademy.clinic.registry.domain.Address
import com.endoacademy.clinic.registry.domain.ConsentGrant
import com.endoacademy.clinic.registry.domain.Cpf
import com.endoacademy.clinic.registry.domain.Email
import com.endoacademy.clinic.registry.domain.Patient
import com.endoacademy.clinic.registry.domain.Phone
import com.endoacademy.clinic.registry.domain.RegistryException
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Endereço como chega da borda, antes de virar [Address]. */
data class AddressData(
    val zipCode: String?,
    val street: String?,
    val number: String?,
    val complement: String?,
    val district: String?,
    val city: String?,
    val state: String?,
)

/** Dados cadastrais como chegam da borda (texto cru); os value objects validam. Campo opcional em branco = ausente. */
data class PatientData(
    val fullName: String?,
    val birthDate: LocalDate?,
    val whatsapp: String?,
    val email: String?,
    val landline: String?,
    val address: AddressData?,
)

data class PageOf<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)

private const val VIEW_PATIENTS = "VIEW_PATIENTS"
private const val MANAGE_PATIENTS = "MANAGE_PATIENTS"
private const val RECORD_CONSENT = "RECORD_CONSENT"
private const val CHANGE_PATIENT_CPF = "CHANGE_PATIENT_CPF"
private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100
private const val MAX_NAME_FILTER = 150

private fun patientNotFound() = ApiException(ErrorKind.NOT_FOUND, "PATIENT_NOT_FOUND")

private fun cpfAlreadyRegistered() = ApiException(ErrorKind.CONFLICT, "CPF_ALREADY_REGISTERED")

private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

private fun AddressData?.toAddress(): Address {
    if (this == null) throw RegistryException("PATIENT_ADDRESS_REQUIRED")
    return Address.of(zipCode.orEmpty(), street.orEmpty(), number.orEmpty(), complement, district.orEmpty(), city.orEmpty(), state.orEmpty())
}

/** Campos que mudaram, só pelos nomes (a auditoria nunca guarda valor). */
private fun changedFields(before: Patient, after: Patient): Set<String> = buildSet {
    if (before.fullName != after.fullName) add("fullName")
    if (before.birthDate != after.birthDate) add("birthDate")
    if (before.whatsapp != after.whatsapp) add("whatsapp")
    if (before.email != after.email) add("email")
    if (before.landline != after.landline) add("landline")
    if (before.address != after.address) add("address")
}

/** O paciente cadastrado e os aceites de consentimento gravados junto com ele. */
data class PatientRegistration(val patient: Patient, val consents: List<ConsentGrant>)

/**
 * Cadastra o paciente (RF5) junto com o aceite dos consentimentos (RF5a): **todos** os tipos ativos obrigatórios no
 * cadastro precisam estar na lista, senão nada é gravado (`CONSENT_REQUIRED`, CA13). Tudo é validado antes da primeira
 * gravação; paciente, aceites, eventos e auditoria saem na mesma transação. Registrar aceite exige `RECORD_CONSENT`
 * além de `MANAGE_PATIENTS`, sempre que a clínica tem tipo obrigatório ou a lista traz algum tipo.
 */
class RegisterPatient(
    private val authorizer: Authorizer,
    private val store: PatientStore,
    private val types: ConsentTypeStore,
    private val grants: ConsentGrantStore,
    private val events: EventPublisher,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun register(user: CurrentUser, cpf: String?, data: PatientData, consentTypeIds: List<UUID>): PatientRegistration {
        authorizer.require(user, MANAGE_PATIENTS)
        val patient = domain {
            Patient.register(
                newId(),
                data.fullName.orEmpty(),
                Cpf.parse(cpf.orEmpty()),
                data.birthDate ?: throw RegistryException("PATIENT_BIRTH_DATE_INVALID"),
                data.whatsapp.blankToNull()?.let { Phone.parse(it) },
                data.email.blankToNull()?.let { Email.parse(it) },
                data.landline.blankToNull()?.let { Phone.parse(it) },
                data.address.toAddress(),
                clock,
            )
        }
        return transactions.inTransaction {
            if (store.cpfTaken(patient.cpf, null)) throw cpfAlreadyRegistered()
            val accepted = consentTypeIds.distinct().map { types.find(it) ?: throw ApiException(ErrorKind.NOT_FOUND, "CONSENT_TYPE_NOT_FOUND") }
            val required = types.list(true).filter { it.requiredOnSignup }
            if (accepted.isNotEmpty() || required.isNotEmpty()) authorizer.require(user, RECORD_CONSENT)
            val acceptedIds = accepted.mapTo(hashSetOf()) { it.id }
            if (required.any { it.id !in acceptedIds }) throw ApiException(ErrorKind.UNPROCESSABLE, "CONSENT_REQUIRED")
            val newGrants = accepted.map { type -> domain { ConsentGrant.accept(newId(), patient.id, type, user.userId, clock.instant()) } }

            store.insert(user.clinicId, patient)
            auditTrail.record(
                user,
                AuditEvent("REGISTER_PATIENT", "PATIENT", patient.id.toString(), details = setOf("fullName", "cpf", "birthDate", "whatsapp", "email", "landline", "address")),
            )
            newGrants.forEach { grant ->
                grants.insert(user.clinicId, grant)
                events.publish(ConsentAccepted(user.clinicId, patient.id, grant.id))
                auditTrail.record(user, AuditEvent("ACCEPT_CONSENT", "CONSENT_GRANT", grant.id.toString()))
            }
            PatientRegistration(patient, newGrants)
        }
    }
}

class UpdatePatient(
    private val authorizer: Authorizer,
    private val store: PatientStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val clock: Clock,
) {
    /** `cpf` nulo ou igual ao gravado mantém o CPF; um CPF diferente exige `CHANGE_PATIENT_CPF`. */
    fun update(user: CurrentUser, id: UUID, cpf: String?, data: PatientData): Patient {
        authorizer.require(user, MANAGE_PATIENTS)
        return transactions.inTransaction {
            val current = store.find(id) ?: throw patientNotFound()
            var updated = domain {
                current.update(
                    data.fullName.orEmpty(),
                    data.birthDate ?: throw RegistryException("PATIENT_BIRTH_DATE_INVALID"),
                    data.whatsapp.blankToNull()?.let { Phone.parse(it) },
                    data.email.blankToNull()?.let { Email.parse(it) },
                    data.landline.blankToNull()?.let { Phone.parse(it) },
                    data.address.toAddress(),
                    clock,
                )
            }
            val newCpf = cpf.blankToNull()?.let { domain { Cpf.parse(it) } }
            val cpfChanged = newCpf != null && newCpf != current.cpf
            if (cpfChanged) {
                authorizer.require(user, CHANGE_PATIENT_CPF)
                if (store.cpfTaken(newCpf!!, id)) throw cpfAlreadyRegistered()
                updated = updated.changeCpf(newCpf)
            }
            store.update(updated)
            auditTrail.record(user, AuditEvent("UPDATE_PATIENT", "PATIENT", id.toString(), details = changedFields(current, updated)))
            if (cpfChanged) auditTrail.record(user, AuditEvent("CHANGE_PATIENT_CPF", "PATIENT", id.toString(), details = setOf("cpf")))
            updated
        }
    }
}

class GetPatient(
    private val authorizer: Authorizer,
    private val store: PatientStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun get(user: CurrentUser, id: UUID): Patient {
        authorizer.require(user, VIEW_PATIENTS)
        return transactions.inTransaction {
            val patient = store.find(id) ?: throw patientNotFound()
            auditTrail.record(user, AuditEvent("READ_PATIENT", "PATIENT", id.toString()))
            patient
        }
    }
}

class ListPatients(
    private val authorizer: Authorizer,
    private val store: PatientStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    /** Um único evento de auditoria por listagem, com a contagem de itens devolvidos. */
    fun list(user: CurrentUser, nameContains: String?, page: Int?, size: Int?): PageOf<Patient> {
        authorizer.require(user, VIEW_PATIENTS)
        val pageNumber = page ?: 0
        val pageSize = size ?: DEFAULT_PAGE_SIZE
        val filter = nameContains.blankToNull()?.trim()
        if (pageNumber < 0 || pageSize !in 1..MAX_PAGE_SIZE || (filter != null && filter.length > MAX_NAME_FILTER)) {
            throw ApiException(ErrorKind.BAD_REQUEST, "VALIDATION_FAILED")
        }
        return transactions.inTransaction {
            val items = store.list(filter, pageNumber * pageSize, pageSize)
            val total = store.count(filter)
            auditTrail.record(user, AuditEvent("LIST_PATIENTS", "PATIENT", details = setOf("count.${items.size}")))
            PageOf(items, pageNumber, pageSize, total)
        }
    }
}

class SearchPatientByCpf(
    private val authorizer: Authorizer,
    private val store: PatientStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    /** Audita a busca (sem o CPF) e, se achou, a leitura do paciente. */
    fun search(user: CurrentUser, cpf: String?): Patient? {
        authorizer.require(user, VIEW_PATIENTS)
        val parsed = domain { Cpf.parse(cpf.orEmpty()) }
        return transactions.inTransaction {
            val patient = store.findByCpf(parsed)
            auditTrail.record(user, AuditEvent("SEARCH_PATIENT_BY_CPF", "PATIENT", details = setOf("count.${if (patient == null) 0 else 1}")))
            if (patient != null) auditTrail.record(user, AuditEvent("READ_PATIENT", "PATIENT", patient.id.toString()))
            patient
        }
    }
}
