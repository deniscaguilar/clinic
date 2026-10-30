package com.endoacademy.clinic.registry.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.ClinicMembers
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.domain.Practitioner
import com.endoacademy.clinic.registry.domain.PractitionerType
import com.endoacademy.clinic.registry.domain.WorkInterval
import com.endoacademy.clinic.registry.domain.WorkSchedule
import java.util.UUID

/** Profissionais da clínica ativa. Roda na transação do [Transactions]; a RLS limita tudo ao tenant. */
interface PractitionerStore {
    /** Ordenados pelo nome, sem diferenciar maiúsculas; `active` nulo traz todos. */
    fun list(active: Boolean?): List<Practitioner>

    fun find(id: UUID): Practitioner?

    fun insert(clinicId: UUID, practitioner: Practitioner)

    /** Grava os campos e substitui o horário inteiro. */
    fun update(practitioner: Practitioner)

    /** Já existe, na clínica, outro profissional ligado a este usuário? */
    fun userLinked(userId: UUID, exceptId: UUID?): Boolean
}

/** Dados de entrada de cadastro e atualização. `active` só vale na atualização. */
data class PractitionerData(
    val name: String,
    val type: PractitionerType,
    val registrationNumber: String?,
    val userId: UUID?,
    val intervals: List<WorkInterval>,
    val active: Boolean = true,
)

private const val MANAGE_PRACTITIONERS = "MANAGE_PRACTITIONERS"

private fun practitionerNotFound() = ApiException(ErrorKind.NOT_FOUND, "PRACTITIONER_NOT_FOUND")

/** O usuário precisa ser membro ativo da clínica e não estar ligado a outro profissional dela. */
private fun checkUserLink(members: ClinicMembers, store: PractitionerStore, clinicId: UUID, userId: UUID?, exceptId: UUID?) {
    if (userId == null) return
    if (!members.isActiveMember(clinicId, userId)) throw ApiException(ErrorKind.UNPROCESSABLE, "PRACTITIONER_USER_NOT_MEMBER")
    if (store.userLinked(userId, exceptId)) throw ApiException(ErrorKind.CONFLICT, "PRACTITIONER_USER_ALREADY_LINKED")
}

class ListPractitioners(private val authorizer: Authorizer, private val store: PractitionerStore, private val transactions: Transactions) {
    fun list(user: CurrentUser, active: Boolean?): List<Practitioner> {
        authorizer.require(user, MANAGE_PRACTITIONERS)
        return transactions.inTransaction { store.list(active) }
    }
}

class GetPractitioner(private val authorizer: Authorizer, private val store: PractitionerStore, private val transactions: Transactions) {
    fun get(user: CurrentUser, id: UUID): Practitioner {
        authorizer.require(user, MANAGE_PRACTITIONERS)
        return transactions.inTransaction { store.find(id) } ?: throw practitionerNotFound()
    }
}

class RegisterPractitioner(
    private val authorizer: Authorizer,
    private val store: PractitionerStore,
    private val members: ClinicMembers,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun register(user: CurrentUser, data: PractitionerData): Practitioner {
        authorizer.require(user, MANAGE_PRACTITIONERS)
        val practitioner = domain {
            Practitioner.register(newId(), data.name, data.type, data.registrationNumber, data.userId, WorkSchedule.of(data.intervals))
        }
        return transactions.inTransaction {
            checkUserLink(members, store, user.clinicId, practitioner.userId, null)
            store.insert(user.clinicId, practitioner)
            auditTrail.record(
                user,
                AuditEvent("REGISTER_PRACTITIONER", "PRACTITIONER", practitioner.id.toString(), details = setOf("name", "type", "registrationNumber", "userId", "workSchedule")),
            )
            practitioner
        }
    }
}

class UpdatePractitioner(
    private val authorizer: Authorizer,
    private val store: PractitionerStore,
    private val members: ClinicMembers,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun update(user: CurrentUser, id: UUID, data: PractitionerData): Practitioner {
        authorizer.require(user, MANAGE_PRACTITIONERS)
        return transactions.inTransaction {
            val current = store.find(id) ?: throw practitionerNotFound()
            val updated = domain {
                current.update(data.name, data.type, data.registrationNumber, data.userId, data.active, WorkSchedule.of(data.intervals))
            }
            checkUserLink(members, store, user.clinicId, updated.userId, id)
            store.update(updated)
            auditTrail.record(
                user,
                AuditEvent("UPDATE_PRACTITIONER", "PRACTITIONER", id.toString(), details = setOf("name", "type", "registrationNumber", "userId", "active", "workSchedule")),
            )
            updated
        }
    }
}
