package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.api.PermissionDefinition
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.platform.application.CreateRole
import com.endoacademy.clinic.platform.application.DeleteRole
import com.endoacademy.clinic.platform.application.ListPermissions
import com.endoacademy.clinic.platform.application.ListRoles
import com.endoacademy.clinic.platform.application.PermissionAuthorizer
import com.endoacademy.clinic.platform.application.PermissionCatalog
import com.endoacademy.clinic.platform.application.RoleStore
import com.endoacademy.clinic.platform.application.RoleView
import com.endoacademy.clinic.platform.application.Transactions
import com.endoacademy.clinic.platform.application.UpdateRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class RoleUseCasesTest {

    private class FakeRoleStore : RoleStore {
        val roles = linkedMapOf<UUID, RoleView>()
        override fun list() = roles.values.sortedBy { it.name }
        override fun find(id: UUID) = roles[id]
        override fun insert(id: UUID, clinicId: UUID, name: String, permissions: Set<String>) {
            roles[id] = RoleView(id, name, permissions, 0)
        }

        override fun nameTaken(name: String, exceptId: UUID?) = roles.values.any { it.name.equals(name, ignoreCase = true) && it.id != exceptId }
        override fun update(id: UUID, name: String, permissions: Set<String>) {
            roles[id] = roles.getValue(id).copy(name = name, permissions = permissions)
        }

        override fun delete(id: UUID) {
            roles.remove(id)
        }
    }

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<Pair<CurrentUser?, AuditEvent>>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += user to event
        }
    }

    private val clinic = UUID.randomUUID()
    private val manager = CurrentUser(UUID.randomUUID(), "sub-1", clinic, setOf("MANAGE_ROLES"))
    private val catalog = PermissionCatalog(
        listOf(
            object : PermissionContributor {
                override fun permissions() = listOf(
                    PermissionDefinition("MANAGE_ROLES", "perfis"),
                    PermissionDefinition("MANAGE_USERS", "usuários"),
                    PermissionDefinition("VIEW_PATIENTS", "ver pacientes"),
                    PermissionDefinition("MANAGE_CLINICS", "clínicas", platformOnly = true),
                )
            },
        ),
    )
    private val store = FakeRoleStore()
    private val audit = RecordingAudit()
    private val authorizer = PermissionAuthorizer(catalog, audit)
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private val create = CreateRole(authorizer, store, catalog, audit, transactions)
    private val update = UpdateRole(authorizer, store, catalog, audit, transactions)
    private val delete = DeleteRole(authorizer, store, audit, transactions)
    private val list = ListRoles(authorizer, store, transactions)
    private val permissions = ListPermissions(authorizer, catalog)

    private fun api(block: () -> Unit) = assertThatThrownBy(block).isInstanceOf(ApiException::class.java).extracting { it as ApiException }

    @Test
    fun `create stores the role with the chosen permissions and audits the field names only`() {
        val role = create.create(manager, "Recepção", setOf("VIEW_PATIENTS", "MANAGE_USERS"))

        assertThat(store.roles.getValue(role.id).permissions).containsExactlyInAnyOrder("VIEW_PATIENTS", "MANAGE_USERS")
        val (who, event) = audit.events.single()
        assertThat(who).isEqualTo(manager)
        assertThat(event.action).isEqualTo("CREATE_ROLE")
        assertThat(event.entityId).isEqualTo(role.id.toString())
        assertThat(event.details).containsExactlyInAnyOrder("name", "permissions")
    }

    @Test
    fun `only permissions from the catalog are accepted, and never the platform only ones`() {
        api { create.create(manager, "X", setOf("NOPE")) }.satisfies({ assertThat(it.code).isEqualTo("PERMISSION_NOT_ASSIGNABLE") })
        api { create.create(manager, "X", setOf("MANAGE_CLINICS")) }.satisfies({ assertThat(it.code).isEqualTo("PERMISSION_NOT_ASSIGNABLE") })
        api { update.update(manager, create.create(manager, "Y", setOf("VIEW_PATIENTS")).id, "Y", setOf("MANAGE_CLINICS")) }
            .satisfies({ assertThat(it.code).isEqualTo("PERMISSION_NOT_ASSIGNABLE") })
        assertThat(store.roles.values.map { it.name }).containsExactly("Y")
    }

    @Test
    fun `name must be present, short and unique in the clinic`() {
        create.create(manager, "Recepção", emptySet())
        api { create.create(manager, "  ", emptySet()) }.satisfies({ assertThat(it.code).isEqualTo("VALIDATION_FAILED") })
        api { create.create(manager, "x".repeat(61), emptySet()) }.satisfies({ assertThat(it.code).isEqualTo("VALIDATION_FAILED") })
        api { create.create(manager, " recepção ", emptySet()) }.satisfies({
            assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
            assertThat(it.code).isEqualTo("ROLE_NAME_TAKEN")
        })
    }

    @Test
    fun `update renames and replaces the permissions, keeping the name unique`() {
        val a = create.create(manager, "A", setOf("VIEW_PATIENTS"))
        create.create(manager, "B", emptySet())

        update.update(manager, a.id, "A2", setOf("MANAGE_USERS"))

        assertThat(store.roles.getValue(a.id)).satisfies({
            assertThat(it.name).isEqualTo("A2")
            assertThat(it.permissions).containsExactly("MANAGE_USERS")
        })
        assertThat(audit.events.last().second.action).isEqualTo("UPDATE_ROLE")
        api { update.update(manager, a.id, "b", emptySet()) }.satisfies({ assertThat(it.code).isEqualTo("ROLE_NAME_TAKEN") })
        update.update(manager, a.id, "A2", setOf("MANAGE_USERS")) // manter o próprio nome não é conflito
    }

    @Test
    fun `a role that is in use cannot be deleted, an unused one can`() {
        val used = create.create(manager, "Usado", emptySet())
        store.roles[used.id] = store.roles.getValue(used.id).copy(members = 2)
        val free = create.create(manager, "Livre", emptySet())

        api { delete.delete(manager, used.id) }.satisfies({
            assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
            assertThat(it.code).isEqualTo("ROLE_IN_USE")
        })
        delete.delete(manager, free.id)

        assertThat(store.roles).containsKey(used.id).doesNotContainKey(free.id)
        assertThat(audit.events.last().second.action).isEqualTo("DELETE_ROLE")
    }

    @Test
    fun `the CLINIC_ADMIN role cannot be renamed, changed or deleted`() {
        val admin = UUID.randomUUID()
        store.roles[admin] = RoleView(admin, "CLINIC_ADMIN", setOf("MANAGE_ROLES", "MANAGE_USERS", "VIEW_PATIENTS"), 0)

        api { update.update(manager, admin, "CLINIC_ADMIN", setOf("MANAGE_ROLES")) }.satisfies({ assertThat(it.code).isEqualTo("ROLE_PROTECTED") })
        api { update.update(manager, admin, "Outro", setOf("MANAGE_ROLES", "MANAGE_USERS", "VIEW_PATIENTS")) }.satisfies({ assertThat(it.code).isEqualTo("ROLE_PROTECTED") })
        api { delete.delete(manager, admin) }.satisfies({ assertThat(it.code).isEqualTo("ROLE_PROTECTED") })
        api { create.create(manager, "clinic_admin", emptySet()) }.satisfies({ assertThat(it.code).isEqualTo("ROLE_NAME_TAKEN") })
    }

    @Test
    fun `missing roles answer not found`() {
        val unknown = UUID.randomUUID()
        api { update.update(manager, unknown, "N", emptySet()) }.satisfies({ assertThat(it.kind).isEqualTo(ErrorKind.NOT_FOUND) })
        api { delete.delete(manager, unknown) }.satisfies({ assertThat(it.code).isEqualTo("ROLE_NOT_FOUND") })
    }

    @Test
    fun `without MANAGE_ROLES every write is refused and audited as DENIED`() {
        val restricted = manager.copy(permissions = setOf("VIEW_PATIENTS"))
        val role = create.create(manager, "Alvo", emptySet())
        audit.events.clear()

        listOf<() -> Unit>(
            { create.create(restricted, "N", emptySet()) },
            { update.update(restricted, role.id, "N", emptySet()) },
            { delete.delete(restricted, role.id) },
        ).forEach { assertThatThrownBy(it).isInstanceOf(PermissionDeniedException::class.java) }

        assertThat(store.roles.getValue(role.id).name).isEqualTo("Alvo")
        assertThat(audit.events).hasSize(3).allSatisfy { assertThat(it.second.outcome).isEqualTo(AuditOutcome.DENIED) }
    }

    @Test
    fun `listing roles and permissions needs MANAGE_ROLES or MANAGE_USERS`() {
        create.create(manager, "Recepção", setOf("VIEW_PATIENTS"))
        val users = manager.copy(permissions = setOf("MANAGE_USERS"))
        val none = manager.copy(permissions = setOf("VIEW_PATIENTS"))

        assertThat(list.list(manager).map { it.name }).containsExactly("Recepção")
        assertThat(list.list(users)).hasSize(1)
        assertThat(permissions.list(users).map { it.code }).containsExactlyInAnyOrder("MANAGE_ROLES", "MANAGE_USERS", "VIEW_PATIENTS") // sem a exclusiva da plataforma
        assertThatThrownBy { list.list(none) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { permissions.list(none) }.isInstanceOf(PermissionDeniedException::class.java)
    }
}
