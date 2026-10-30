package com.endoacademy.clinic.platform.adapter.`in`.web

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.application.CreateClinic
import com.endoacademy.clinic.platform.application.ClinicAdmin
import com.endoacademy.clinic.platform.application.ClinicDetail
import com.endoacademy.clinic.platform.application.ClinicOverview
import com.endoacademy.clinic.platform.application.CreateClinicCommand
import com.endoacademy.clinic.platform.application.GetClinic
import com.endoacademy.clinic.platform.application.ListClinics
import com.endoacademy.clinic.platform.application.ResendClinicAdminInvitation
import com.endoacademy.clinic.platform.application.SetClinicStatus
import com.endoacademy.clinic.platform.application.UpdateClinic
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Backoffice do `PLATFORM_ADMIN` (as rotas de administração não usam `X-Clinic-Id`). A checagem do papel fica no caso de uso. */
@RestController
@RequestMapping("/api/v1/admin/clinics")
class AdminClinicController(
    private val createClinic: CreateClinic,
    private val updateClinic: UpdateClinic,
    private val setClinicStatus: SetClinicStatus,
    private val resendInvitation: ResendClinicAdminInvitation,
    private val listClinics: ListClinics,
    private val getClinic: GetClinic,
) {

    data class CreateClinicRequest(
        @field:NotBlank @field:Size(max = 120) val name: String?,
        @field:NotBlank @field:Size(max = 254) val adminEmail: String?,
        @field:NotBlank @field:Size(max = 120) val adminName: String?,
    )

    data class FirstAdminResponse(val membershipId: UUID, val status: String)

    data class CreateClinicResponse(val id: UUID, val firstAdmin: FirstAdminResponse)

    data class UpdateClinicRequest(@field:NotBlank @field:Size(max = 120) val name: String?)

    data class ClinicInvitationResponse(val status: String)

    data class ClinicOverviewResponse(val id: UUID, val name: String, val status: String) {
        companion object {
            fun of(clinic: ClinicOverview) = ClinicOverviewResponse(clinic.id, clinic.name, clinic.status.name)
        }
    }

    data class ClinicAdminResponse(val membershipId: UUID, val email: String, val name: String, val status: String) {
        companion object {
            fun of(admin: ClinicAdmin) = ClinicAdminResponse(admin.membershipId, admin.email, admin.name, admin.status.name)
        }
    }

    data class ClinicDetailResponse(val id: UUID, val name: String, val status: String, val admins: List<ClinicAdminResponse>) {
        companion object {
            fun of(detail: ClinicDetail) =
                ClinicDetailResponse(detail.clinic.id, detail.clinic.name, detail.clinic.status.name, detail.admins.map { ClinicAdminResponse.of(it) })
        }
    }

    private fun subjectOf(jwt: Jwt): String = jwt.subject ?: throw ApiException(ErrorKind.UNAUTHENTICATED, "TOKEN_WITHOUT_SUBJECT")

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt): List<ClinicOverviewResponse> = listClinics.list(subjectOf(jwt)).map { ClinicOverviewResponse.of(it) }

    @GetMapping("/{clinicId}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable clinicId: UUID): ClinicDetailResponse =
        ClinicDetailResponse.of(getClinic.get(subjectOf(jwt), clinicId))

    @PatchMapping("/{clinicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun update(@AuthenticationPrincipal jwt: Jwt, @PathVariable clinicId: UUID, @Valid @RequestBody request: UpdateClinicRequest) =
        updateClinic.update(subjectOf(jwt), clinicId, request.name.orEmpty())

    @PostMapping("/{clinicId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivate(@AuthenticationPrincipal jwt: Jwt, @PathVariable clinicId: UUID) = setClinicStatus.deactivate(subjectOf(jwt), clinicId)

    @PostMapping("/{clinicId}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun activate(@AuthenticationPrincipal jwt: Jwt, @PathVariable clinicId: UUID) = setClinicStatus.activate(subjectOf(jwt), clinicId)

    @PostMapping("/{clinicId}/admins/{membershipId}/resend-invitation")
    fun resend(@AuthenticationPrincipal jwt: Jwt, @PathVariable clinicId: UUID, @PathVariable membershipId: UUID) =
        ClinicInvitationResponse(resendInvitation.resend(subjectOf(jwt), clinicId, membershipId).name)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: CreateClinicRequest): CreateClinicResponse {
        val result = createClinic.create(subjectOf(jwt), CreateClinicCommand(request.name.orEmpty(), request.adminEmail.orEmpty(), request.adminName.orEmpty()))
        return CreateClinicResponse(result.clinicId, FirstAdminResponse(result.adminMembershipId, result.adminStatus.name))
    }
}
