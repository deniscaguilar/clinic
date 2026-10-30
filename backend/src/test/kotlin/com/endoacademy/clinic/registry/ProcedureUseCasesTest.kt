package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.registry.application.CreateProcedure
import com.endoacademy.clinic.registry.application.GetProcedure
import com.endoacademy.clinic.registry.application.ListProcedures
import com.endoacademy.clinic.registry.application.ProcedureStore
import com.endoacademy.clinic.registry.application.Transactions
import com.endoacademy.clinic.registry.application.UpdateProcedure
import com.endoacademy.clinic.registry.domain.Procedure
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ProcedureUseCasesTest {

    private class FakeProcedureStore : ProcedureStore {
        val rows = linkedMapOf<UUID, Procedure>()
        override fun list(active: Boolean?) = rows.values.filter { active == null || it.active == active }.sortedBy { it.name.lowercase() }
        override fun find(id: UUID) = rows[id]
        override fun insert(clinicId: UUID, procedure: Procedure) {
            rows[procedure.id] = procedure
        }

        override fun update(procedure: Procedure) {
            rows[procedure.id] = procedure
        }

        override fun nameTaken(name: String, exceptId: UUID?) = rows.values.any { it.name.equals(name, ignoreCase = true) && it.id != exceptId }
    }

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<AuditEvent>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += event
        }
    }

    private val clinic = UUID.randomUUID()
    private val manager = CurrentUser(UUID.randomUUID(), "sub-1", clinic, setOf("MANAGE_PROCEDURES"))
    private val outsider = CurrentUser(UUID.randomUUID(), "sub-2", clinic, setOf("VIEW_AUDIT_TRAIL"))
    private val store = FakeProcedureStore()
    private val audit = RecordingAudit()
    private val authorizer = object : Authorizer {
        override fun require(user: CurrentUser, permission: String) {
            if (!user.has(permission)) throw PermissionDeniedException(permission)
        }
    }
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private var nextId = 0
    private val create = CreateProcedure(authorizer, store, audit, transactions) { UUID(0, (++nextId).toLong()) }
    private val update = UpdateProcedure(authorizer, store, audit, transactions)
    private val list = ListProcedures(authorizer, store, transactions)
    private val get = GetProcedure(authorizer, store, transactions)

    private fun assertApi(kind: ErrorKind, code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(kind)
            assertThat(it.code).isEqualTo(code)
        }
    }

    @Test
    fun `creating a procedure stores it active and audits the field names only`() {
        val created = create.create(manager, "  Consulta  ", "Primeira consulta")
        assertThat(created.name).isEqualTo("Consulta")
        assertThat(created.active).isTrue()
        assertThat(store.rows).containsKey(created.id)
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("CREATE_PROCEDURE")
            assertThat(it.entityType).isEqualTo("PROCEDURE")
            assertThat(it.entityId).isEqualTo(created.id.toString())
            assertThat(it.outcome).isEqualTo(AuditOutcome.SUCCESS)
            assertThat(it.details).containsExactlyInAnyOrder("name", "description")
        })
    }

    @Test
    fun `without MANAGE_PROCEDURES nothing is created, listed, read or updated`() {
        val existing = create.create(manager, "Consulta", null)
        audit.events.clear()
        assertThatThrownBy { create.create(outsider, "Outra", null) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { update.update(outsider, existing.id, "Renomeada", null, true) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { list.list(outsider, null) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { get.get(outsider, existing.id) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThat(store.rows).hasSize(1)
        assertThat(store.rows.getValue(existing.id).name).isEqualTo("Consulta")
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `the name is unique per clinic ignoring case and a failed create audits nothing`() {
        create.create(manager, "Consulta", null)
        audit.events.clear()
        assertApi(ErrorKind.CONFLICT, "PROCEDURE_NAME_TAKEN") { create.create(manager, "  consulta ", null) }
        assertThat(store.rows).hasSize(1)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `domain violations become 422 with the stable code`() {
        assertApi(ErrorKind.UNPROCESSABLE, "PROCEDURE_NAME_REQUIRED") { create.create(manager, "  ", null) }
        assertApi(ErrorKind.UNPROCESSABLE, "PROCEDURE_NAME_TOO_LONG") { create.create(manager, "x".repeat(121), null) }
        assertApi(ErrorKind.UNPROCESSABLE, "PROCEDURE_DESCRIPTION_TOO_LONG") { create.create(manager, "Consulta", "x".repeat(501)) }
        assertThat(store.rows).isEmpty()
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `updating changes the fields and audits`() {
        val created = create.create(manager, "Consulta", null)
        audit.events.clear()
        val updated = update.update(manager, created.id, "Consulta de retorno", "30 dias", false)
        assertThat(updated.name).isEqualTo("Consulta de retorno")
        assertThat(updated.active).isFalse()
        assertThat(store.rows.getValue(created.id)).isEqualTo(updated)
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("UPDATE_PROCEDURE")
            assertThat(it.entityId).isEqualTo(created.id.toString())
            assertThat(it.details).containsExactlyInAnyOrder("name", "description", "active")
        })
    }

    @Test
    fun `an update may keep its own name but not take another procedure's name`() {
        val first = create.create(manager, "Consulta", null)
        val second = create.create(manager, "Retorno", null)
        assertThat(update.update(manager, first.id, "CONSULTA", "novo texto", true).name).isEqualTo("CONSULTA")
        audit.events.clear()
        assertApi(ErrorKind.CONFLICT, "PROCEDURE_NAME_TAKEN") { update.update(manager, second.id, "consulta", null, true) }
        assertThat(store.rows.getValue(second.id).name).isEqualTo("Retorno")
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `an unknown procedure answers 404 on read and update`() {
        val unknown = UUID.randomUUID()
        assertApi(ErrorKind.NOT_FOUND, "PROCEDURE_NOT_FOUND") { get.get(manager, unknown) }
        assertApi(ErrorKind.NOT_FOUND, "PROCEDURE_NOT_FOUND") { update.update(manager, unknown, "Qualquer", null, true) }
    }

    @Test
    fun `an update with invalid data keeps the stored procedure`() {
        val created = create.create(manager, "Consulta", "texto")
        audit.events.clear()
        assertApi(ErrorKind.UNPROCESSABLE, "PROCEDURE_NAME_REQUIRED") { update.update(manager, created.id, " ", null, false) }
        assertThat(store.rows.getValue(created.id)).isEqualTo(created)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `listing filters by active and orders by name`() {
        val b = create.create(manager, "b Retorno", null)
        create.create(manager, "A Consulta", null)
        update.update(manager, b.id, "b Retorno", null, false)
        assertThat(list.list(manager, null).map { it.name }).containsExactly("A Consulta", "b Retorno")
        assertThat(list.list(manager, true).map { it.name }).containsExactly("A Consulta")
        assertThat(list.list(manager, false).map { it.name }).containsExactly("b Retorno")
    }
}
