package com.endoacademy.clinic.platform.application

import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.PermissionDeniedException

class PermissionAuthorizer(private val catalog: PermissionCatalog, private val auditTrail: AuditTrail) : Authorizer {

    override fun require(user: CurrentUser, permission: String) {
        require(catalog.contains(permission)) { "Permissão desconhecida exigida pelo caso de uso: $permission" }
        if (user.has(permission)) return
        auditTrail.record(
            user,
            AuditEvent(action = "ACCESS_DENIED", entityType = "PERMISSION", entityId = permission, outcome = AuditOutcome.DENIED),
        )
        throw PermissionDeniedException(permission)
    }
}
