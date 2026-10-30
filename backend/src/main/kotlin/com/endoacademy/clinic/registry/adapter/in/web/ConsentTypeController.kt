package com.endoacademy.clinic.registry.adapter.`in`.web

import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.registry.application.ConsentTypeChange
import com.endoacademy.clinic.registry.application.ConsentTypeData
import com.endoacademy.clinic.registry.application.CreateConsentType
import com.endoacademy.clinic.registry.application.GetConsentType
import com.endoacademy.clinic.registry.application.ImportConsentTemplate
import com.endoacademy.clinic.registry.application.ListAvailableConsentTemplates
import com.endoacademy.clinic.registry.application.ListConsentTypes
import com.endoacademy.clinic.registry.application.ReviseConsentType
import com.endoacademy.clinic.registry.domain.ConsentType
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Tipos de consentimento da clínica ativa (`X-Clinic-Id`) e os modelos que ela pode importar. `PUT` cria a versão
 * seguinte quando o texto muda. A autorização fica nos casos de uso.
 */
@RestController
@RequestMapping("/api/v1")
class ConsentTypeController(
    private val listConsentTypes: ListConsentTypes,
    private val getConsentType: GetConsentType,
    private val createConsentType: CreateConsentType,
    private val reviseConsentType: ReviseConsentType,
    private val importConsentTemplate: ImportConsentTemplate,
    private val listAvailableTemplates: ListAvailableConsentTemplates,
) {
    data class ConsentTypeCreateRequest(
        val name: String?,
        val text: String?,
        @field:NotNull val requiredOnSignup: Boolean?,
        @field:NotNull val controlsMessaging: Boolean?,
    )

    /** `text` ausente ou igual ao vigente não cria versão. */
    data class ConsentTypeUpdateRequest(
        val name: String?,
        val text: String?,
        @field:NotNull val requiredOnSignup: Boolean?,
        @field:NotNull val controlsMessaging: Boolean?,
        @field:NotNull val active: Boolean?,
    )

    data class ImportRequest(@field:NotNull val templateId: UUID?)

    data class VersionResponse(val number: Int, val text: String, val createdAt: Instant)

    data class CurrentVersionResponse(val number: Int, val createdAt: Instant)

    data class SummaryResponse(
        val id: UUID,
        val name: String,
        val requiredOnSignup: Boolean,
        val controlsMessaging: Boolean,
        val active: Boolean,
        val currentVersion: CurrentVersionResponse,
    )

    data class DetailResponse(
        val id: UUID,
        val name: String,
        val requiredOnSignup: Boolean,
        val controlsMessaging: Boolean,
        val active: Boolean,
        val currentVersion: VersionResponse,
        val versions: List<VersionResponse>,
    )

    data class TemplateOfferResponse(val id: UUID, val name: String, val text: String, val requiredOnSignup: Boolean, val controlsMessaging: Boolean)

    private fun user(request: HttpServletRequest) = request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE) as CurrentUser

    private fun ConsentType.toSummary() = SummaryResponse(id, name, requiredOnSignup, controlsMessaging, active, CurrentVersionResponse(current.number, current.createdAt))

    private fun ConsentType.toDetail() = DetailResponse(
        id,
        name,
        requiredOnSignup,
        controlsMessaging,
        active,
        VersionResponse(current.number, current.text, current.createdAt),
        versions.map { VersionResponse(it.number, it.text, it.createdAt) },
    )

    @GetMapping("/consent-types")
    fun list(request: HttpServletRequest, @RequestParam(required = false) active: Boolean?): List<SummaryResponse> =
        listConsentTypes.list(user(request), active).map { it.toSummary() }

    @GetMapping("/consent-types/{id}")
    fun get(request: HttpServletRequest, @PathVariable id: UUID): DetailResponse = getConsentType.get(user(request), id).toDetail()

    @PostMapping("/consent-types")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(request: HttpServletRequest, @Valid @RequestBody body: ConsentTypeCreateRequest): DetailResponse =
        createConsentType.create(user(request), ConsentTypeData(body.name.orEmpty(), body.text.orEmpty(), body.requiredOnSignup == true, body.controlsMessaging == true)).toDetail()

    @PutMapping("/consent-types/{id}")
    fun revise(request: HttpServletRequest, @PathVariable id: UUID, @Valid @RequestBody body: ConsentTypeUpdateRequest): DetailResponse =
        reviseConsentType.revise(
            user(request),
            id,
            ConsentTypeChange(body.name.orEmpty(), body.text, body.requiredOnSignup == true, body.controlsMessaging == true, body.active == true),
        ).toDetail()

    @PostMapping("/consent-types/import-template")
    @ResponseStatus(HttpStatus.CREATED)
    fun import(request: HttpServletRequest, @Valid @RequestBody body: ImportRequest): DetailResponse =
        importConsentTemplate.import(user(request), body.templateId!!).toDetail()

    @GetMapping("/consent-templates")
    fun templates(request: HttpServletRequest): List<TemplateOfferResponse> =
        listAvailableTemplates.list(user(request)).map { TemplateOfferResponse(it.id, it.name, it.text, it.requiredOnSignup, it.controlsMessaging) }
}
