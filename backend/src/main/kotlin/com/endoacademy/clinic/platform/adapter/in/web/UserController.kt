package com.endoacademy.clinic.platform.adapter.`in`.web

import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.application.DeactivateMembership
import com.endoacademy.clinic.platform.application.InviteUser
import com.endoacademy.clinic.platform.application.ListUsers
import com.endoacademy.clinic.platform.application.ResendInvitation
import com.endoacademy.clinic.platform.application.UpdateUserRoles
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Usuários da clínica ativa (`X-Clinic-Id`). A autorização (`MANAGE_USERS`) fica nos casos de uso. */
@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val listUsers: ListUsers,
    private val inviteUser: InviteUser,
    private val updateUserRoles: UpdateUserRoles,
    private val deactivateMembership: DeactivateMembership,
    private val resendInvitation: ResendInvitation,
) {
    data class InviteRequest(
        @field:NotBlank @field:Size(max = 254) val email: String?,
        @field:NotBlank @field:Size(max = 120) val name: String?,
        val roleIds: List<UUID>?,
    )

    data class RolesRequest(val roleIds: List<UUID>?)

    data class UserRoleResponse(val id: UUID, val name: String)

    data class MemberResponse(val membershipId: UUID, val userId: UUID, val email: String, val name: String, val status: String, val roles: List<UserRoleResponse>)

    data class InviteResponse(val membershipId: UUID, val status: String)

    data class UserInvitationResponse(val status: String)

    private fun user(request: HttpServletRequest) = request.getAttribute(ClinicContextFilter.CURRENT_USER) as CurrentUser

    @GetMapping
    fun list(request: HttpServletRequest): List<MemberResponse> = listUsers.list(user(request)).map { member ->
        MemberResponse(member.membershipId, member.userId, member.email, member.name, member.status.name, member.roles.map { UserRoleResponse(it.id, it.name) })
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun invite(request: HttpServletRequest, @Valid @RequestBody body: InviteRequest): InviteResponse =
        inviteUser.invite(user(request), body.email.orEmpty(), body.name.orEmpty(), body.roleIds.orEmpty().toSet())
            .let { InviteResponse(it.membershipId, it.status.name) }

    @PatchMapping("/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateRoles(request: HttpServletRequest, @PathVariable membershipId: UUID, @RequestBody body: RolesRequest) =
        updateUserRoles.update(user(request), membershipId, body.roleIds.orEmpty().toSet())

    @PostMapping("/{membershipId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivate(request: HttpServletRequest, @PathVariable membershipId: UUID) = deactivateMembership.deactivate(user(request), membershipId)

    @PostMapping("/{membershipId}/resend-invitation")
    fun resend(request: HttpServletRequest, @PathVariable membershipId: UUID): UserInvitationResponse =
        UserInvitationResponse(resendInvitation.resend(user(request), membershipId).name)
}
