package com.endoacademy.clinic.platform.adapter.`in`.web

import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.application.CreateRole
import com.endoacademy.clinic.platform.application.DeleteRole
import com.endoacademy.clinic.platform.application.ListPermissions
import com.endoacademy.clinic.platform.application.ListRoles
import com.endoacademy.clinic.platform.application.RoleView
import com.endoacademy.clinic.platform.application.UpdateRole
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Perfis e permissões da clínica ativa (`X-Clinic-Id`). A autorização fica nos casos de uso. */
@RestController
@RequestMapping("/api/v1")
class RoleController(
    private val listRoles: ListRoles,
    private val createRole: CreateRole,
    private val updateRole: UpdateRole,
    private val deleteRole: DeleteRole,
    private val listPermissions: ListPermissions,
) {
    data class RoleRequest(@field:NotBlank @field:Size(max = 60) val name: String?, val permissions: List<String>?)

    data class RoleResponse(val id: UUID, val name: String, val permissions: List<String>, val members: Int)

    data class PermissionResponse(val code: String, val description: String)

    private fun user(request: HttpServletRequest) = request.getAttribute(ClinicContextFilter.CURRENT_USER) as CurrentUser

    private fun RoleView.toResponse() = RoleResponse(id, name, permissions.sorted(), members)

    @GetMapping("/roles")
    fun list(request: HttpServletRequest): List<RoleResponse> = listRoles.list(user(request)).map { it.toResponse() }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(request: HttpServletRequest, @Valid @RequestBody body: RoleRequest): RoleResponse =
        createRole.create(user(request), body.name.orEmpty(), body.permissions.orEmpty().toSet()).toResponse()

    @PutMapping("/roles/{roleId}")
    fun update(request: HttpServletRequest, @PathVariable roleId: UUID, @Valid @RequestBody body: RoleRequest): RoleResponse =
        updateRole.update(user(request), roleId, body.name.orEmpty(), body.permissions.orEmpty().toSet()).toResponse()

    @DeleteMapping("/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(request: HttpServletRequest, @PathVariable roleId: UUID) = deleteRole.delete(user(request), roleId)

    @GetMapping("/permissions")
    fun permissions(request: HttpServletRequest): List<PermissionResponse> =
        listPermissions.list(user(request)).map { PermissionResponse(it.code, it.description) }
}
