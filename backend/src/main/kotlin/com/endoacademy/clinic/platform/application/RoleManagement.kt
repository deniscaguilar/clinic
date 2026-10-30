package com.endoacademy.clinic.platform.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionDefinition
import java.util.UUID

/** Transação no tenant da requisição (o filtro de `X-Clinic-Id` já definiu o contexto). */
interface Transactions {
    fun <T> inTransaction(block: () -> T): T
}

data class RoleView(val id: UUID, val name: String, val permissions: Set<String>, val members: Int)

/** Perfis da clínica ativa. Roda na transação do [Transactions]; a RLS limita tudo ao tenant. */
interface RoleStore {
    fun list(): List<RoleView>

    fun find(id: UUID): RoleView?

    fun insert(id: UUID, clinicId: UUID, name: String, permissions: Set<String>)

    /** Compara ignorando maiúsculas e minúsculas. */
    fun nameTaken(name: String, exceptId: UUID?): Boolean

    /** Troca o nome e substitui o conjunto de permissões. */
    fun update(id: UUID, name: String, permissions: Set<String>)

    fun delete(id: UUID)
}

private const val MANAGE_ROLES = "MANAGE_ROLES"
private const val MANAGE_USERS = "MANAGE_USERS"
private const val MAX_ROLE_NAME = 60

private fun roleNotFound() = ApiException(ErrorKind.NOT_FOUND, "ROLE_NOT_FOUND")

private fun validatedName(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_ROLE_NAME) throw ApiException(ErrorKind.BAD_REQUEST, "VALIDATION_FAILED")
    return trimmed
}

private fun validatedPermissions(catalog: PermissionCatalog, permissions: Set<String>): Set<String> {
    try {
        catalog.validateAssignable(permissions)
    } catch (_: IllegalArgumentException) {
        throw ApiException(ErrorKind.BAD_REQUEST, "PERMISSION_NOT_ASSIGNABLE")
    }
    return permissions
}

/** Ver perfis e permissões serve a quem gere perfis ou usuários (precisa deles para convidar). */
private fun requireRoleVisibility(authorizer: Authorizer, user: CurrentUser) {
    if (user.has(MANAGE_USERS)) return
    authorizer.require(user, MANAGE_ROLES)
}

class ListRoles(private val authorizer: Authorizer, private val store: RoleStore, private val transactions: Transactions) {
    fun list(user: CurrentUser): List<RoleView> {
        requireRoleVisibility(authorizer, user)
        return transactions.inTransaction { store.list() }
    }
}

class ListPermissions(private val authorizer: Authorizer, private val catalog: PermissionCatalog) {
    /** Só as permissões atribuíveis a perfis de clínica. */
    fun list(user: CurrentUser): List<PermissionDefinition> {
        requireRoleVisibility(authorizer, user)
        val assignable = catalog.assignable()
        return catalog.all().filter { it.code in assignable }
    }
}

class CreateRole(
    private val authorizer: Authorizer,
    private val store: RoleStore,
    private val catalog: PermissionCatalog,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun create(user: CurrentUser, name: String, permissions: Set<String>): RoleView {
        authorizer.require(user, MANAGE_ROLES)
        val trimmed = validatedName(name)
        val valid = validatedPermissions(catalog, permissions)
        return transactions.inTransaction {
            if (store.nameTaken(trimmed, null)) throw ApiException(ErrorKind.CONFLICT, "ROLE_NAME_TAKEN")
            val id = newId()
            store.insert(id, user.clinicId, trimmed, valid)
            auditTrail.record(user, AuditEvent("CREATE_ROLE", "ROLE", id.toString(), details = setOf("name", "permissions")))
            RoleView(id, trimmed, valid, 0)
        }
    }
}

class UpdateRole(
    private val authorizer: Authorizer,
    private val store: RoleStore,
    private val catalog: PermissionCatalog,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun update(user: CurrentUser, roleId: UUID, name: String, permissions: Set<String>): RoleView {
        authorizer.require(user, MANAGE_ROLES)
        val trimmed = validatedName(name)
        val valid = validatedPermissions(catalog, permissions)
        return transactions.inTransaction {
            val role = store.find(roleId) ?: throw roleNotFound()
            if (role.name == PermissionCatalog.CLINIC_ADMIN) throw ApiException(ErrorKind.CONFLICT, "ROLE_PROTECTED")
            if (store.nameTaken(trimmed, roleId)) throw ApiException(ErrorKind.CONFLICT, "ROLE_NAME_TAKEN")
            store.update(roleId, trimmed, valid)
            auditTrail.record(user, AuditEvent("UPDATE_ROLE", "ROLE", roleId.toString(), details = setOf("name", "permissions")))
            role.copy(name = trimmed, permissions = valid)
        }
    }
}

class DeleteRole(
    private val authorizer: Authorizer,
    private val store: RoleStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun delete(user: CurrentUser, roleId: UUID) {
        authorizer.require(user, MANAGE_ROLES)
        transactions.inTransaction {
            val role = store.find(roleId) ?: throw roleNotFound()
            if (role.name == PermissionCatalog.CLINIC_ADMIN) throw ApiException(ErrorKind.CONFLICT, "ROLE_PROTECTED")
            if (role.members > 0) throw ApiException(ErrorKind.CONFLICT, "ROLE_IN_USE")
            store.delete(roleId)
            auditTrail.record(user, AuditEvent("DELETE_ROLE", "ROLE", roleId.toString()))
        }
    }
}
