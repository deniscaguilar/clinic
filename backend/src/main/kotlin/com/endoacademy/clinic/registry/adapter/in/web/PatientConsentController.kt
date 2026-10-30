package com.endoacademy.clinic.registry.adapter.`in`.web

import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.registry.application.AcceptConsent
import com.endoacademy.clinic.registry.application.ConsentGrantView
import com.endoacademy.clinic.registry.application.ListPatientConsents
import com.endoacademy.clinic.registry.application.RevokeConsent
import com.endoacademy.clinic.registry.domain.ConsentGrant
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Aceite e revogação de consentimento de um paciente da clínica ativa. A autorização fica nos casos de uso. */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/consents")
class PatientConsentController(
    private val acceptConsent: AcceptConsent,
    private val revokeConsent: RevokeConsent,
    private val listPatientConsents: ListPatientConsents,
) {
    data class AcceptRequest(@field:NotNull val typeId: UUID?)

    data class GrantResponse(
        val id: UUID,
        val typeId: UUID,
        val typeName: String?,
        val versionNumber: Int,
        val acceptedAt: Instant,
        val channel: String,
        val acceptedBy: UUID,
        val revokedAt: Instant?,
        val revokedBy: UUID?,
        val active: Boolean,
    )

    private fun user(request: HttpServletRequest) = request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE) as CurrentUser

    private fun ConsentGrant.toResponse(typeName: String? = null) =
        GrantResponse(id, typeId, typeName, versionNumber, acceptedAt, channel.name, acceptedBy, revokedAt, revokedBy, active)

    private fun ConsentGrantView.toResponse() = grant.toResponse(typeName)

    @GetMapping
    fun list(request: HttpServletRequest, @PathVariable patientId: UUID): List<GrantResponse> =
        listPatientConsents.list(user(request), patientId).map { it.toResponse() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun accept(request: HttpServletRequest, @PathVariable patientId: UUID, @Valid @RequestBody body: AcceptRequest): GrantResponse =
        acceptConsent.accept(user(request), patientId, body.typeId!!).toResponse()

    @PostMapping("/{grantId}/revoke")
    fun revoke(request: HttpServletRequest, @PathVariable patientId: UUID, @PathVariable grantId: UUID): GrantResponse =
        revokeConsent.revoke(user(request), patientId, grantId).toResponse()
}
