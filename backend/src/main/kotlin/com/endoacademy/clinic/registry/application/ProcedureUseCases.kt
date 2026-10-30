package com.endoacademy.clinic.registry.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.domain.Procedure
import com.endoacademy.clinic.registry.domain.RegistryException
import java.util.UUID

/** Transação no tenant da requisição (o filtro de `X-Clinic-Id` já definiu o contexto). */
interface Transactions {
    fun <T> inTransaction(block: () -> T): T
}

/** Procedimentos da clínica ativa. Roda na transação do [Transactions]; a RLS limita tudo ao tenant. */
interface ProcedureStore {
    /** Ordenados pelo nome, sem diferenciar maiúsculas; `active` nulo traz todos. */
    fun list(active: Boolean?): List<Procedure>

    fun find(id: UUID): Procedure?

    fun insert(clinicId: UUID, procedure: Procedure)

    fun update(procedure: Procedure)

    /** Compara ignorando maiúsculas e minúsculas. */
    fun nameTaken(name: String, exceptId: UUID?): Boolean
}

/** Violação de regra do domínio = 422 com o mesmo código; o tipo único de mensagens e a revogação repetida são conflitos (409). */
internal fun RegistryException.toApiException(): ApiException =
    ApiException(if (code in CONFLICT_CODES) ErrorKind.CONFLICT else ErrorKind.UNPROCESSABLE, code)

private val CONFLICT_CODES = setOf("CONSENT_MESSAGING_TYPE_EXISTS", "CONSENT_ALREADY_REVOKED")

private const val MANAGE_PROCEDURES = "MANAGE_PROCEDURES"

private fun procedureNotFound() = ApiException(ErrorKind.NOT_FOUND, "PROCEDURE_NOT_FOUND")

/** Traduz a violação de regra do domínio para o erro de API com o mesmo código. */
internal fun <T> domain(block: () -> T): T =
    try {
        block()
    } catch (exception: RegistryException) {
        throw exception.toApiException()
    }

class ListProcedures(private val authorizer: Authorizer, private val store: ProcedureStore, private val transactions: Transactions) {
    fun list(user: CurrentUser, active: Boolean?): List<Procedure> {
        authorizer.require(user, MANAGE_PROCEDURES)
        return transactions.inTransaction { store.list(active) }
    }
}

class GetProcedure(private val authorizer: Authorizer, private val store: ProcedureStore, private val transactions: Transactions) {
    fun get(user: CurrentUser, id: UUID): Procedure {
        authorizer.require(user, MANAGE_PROCEDURES)
        return transactions.inTransaction { store.find(id) } ?: throw procedureNotFound()
    }
}

class CreateProcedure(
    private val authorizer: Authorizer,
    private val store: ProcedureStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun create(user: CurrentUser, name: String, description: String?): Procedure {
        authorizer.require(user, MANAGE_PROCEDURES)
        val procedure = domain { Procedure.create(newId(), name, description) }
        return transactions.inTransaction {
            if (store.nameTaken(procedure.name, null)) throw ApiException(ErrorKind.CONFLICT, "PROCEDURE_NAME_TAKEN")
            store.insert(user.clinicId, procedure)
            auditTrail.record(user, AuditEvent("CREATE_PROCEDURE", "PROCEDURE", procedure.id.toString(), details = setOf("name", "description")))
            procedure
        }
    }
}

class UpdateProcedure(
    private val authorizer: Authorizer,
    private val store: ProcedureStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun update(user: CurrentUser, id: UUID, name: String, description: String?, active: Boolean): Procedure {
        authorizer.require(user, MANAGE_PROCEDURES)
        return transactions.inTransaction {
            val current = store.find(id) ?: throw procedureNotFound()
            val updated = domain { current.update(name, description, active) }
            if (store.nameTaken(updated.name, id)) throw ApiException(ErrorKind.CONFLICT, "PROCEDURE_NAME_TAKEN")
            store.update(updated)
            auditTrail.record(user, AuditEvent("UPDATE_PROCEDURE", "PROCEDURE", id.toString(), details = setOf("name", "description", "active")))
            updated
        }
    }
}
