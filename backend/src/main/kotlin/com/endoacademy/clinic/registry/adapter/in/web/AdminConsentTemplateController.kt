package com.endoacademy.clinic.registry.adapter.`in`.web

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.application.ConsentTemplateData
import com.endoacademy.clinic.registry.application.CreateConsentTemplate
import com.endoacademy.clinic.registry.application.GetConsentTemplate
import com.endoacademy.clinic.registry.application.ListConsentTemplates
import com.endoacademy.clinic.registry.application.UpdateConsentTemplate
import com.endoacademy.clinic.registry.domain.ConsentTemplate
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Modelos de consentimento da plataforma, para o `PLATFORM_ADMIN` (sem `X-Clinic-Id`). O papel é conferido no caso de uso. */
@RestController
@RequestMapping("/api/v1/admin/consent-templates")
class AdminConsentTemplateController(
    private val listTemplates: ListConsentTemplates,
    private val getTemplate: GetConsentTemplate,
    private val createTemplate: CreateConsentTemplate,
    private val updateTemplate: UpdateConsentTemplate,
) {
    data class TemplateCreateRequest(
        val name: String?,
        val text: String?,
        @field:NotNull val requiredOnSignup: Boolean?,
        @field:NotNull val controlsMessaging: Boolean?,
    )

    data class TemplateUpdateRequest(
        val name: String?,
        val text: String?,
        @field:NotNull val requiredOnSignup: Boolean?,
        @field:NotNull val controlsMessaging: Boolean?,
        @field:NotNull val active: Boolean?,
    )

    data class TemplateResponse(
        val id: UUID,
        val name: String,
        val text: String,
        val requiredOnSignup: Boolean,
        val controlsMessaging: Boolean,
        val active: Boolean,
    )

    private fun subjectOf(jwt: Jwt): String = jwt.subject ?: throw ApiException(ErrorKind.UNAUTHENTICATED, "TOKEN_WITHOUT_SUBJECT")

    private fun ConsentTemplate.toResponse() = TemplateResponse(id, name, text, requiredOnSignup, controlsMessaging, active)

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) active: Boolean?): List<TemplateResponse> =
        listTemplates.list(subjectOf(jwt), active).map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): TemplateResponse = getTemplate.get(subjectOf(jwt), id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody body: TemplateCreateRequest): TemplateResponse =
        createTemplate.create(subjectOf(jwt), ConsentTemplateData(body.name.orEmpty(), body.text.orEmpty(), body.requiredOnSignup == true, body.controlsMessaging == true)).toResponse()

    @PutMapping("/{id}")
    fun update(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @Valid @RequestBody body: TemplateUpdateRequest): TemplateResponse =
        updateTemplate.update(
            subjectOf(jwt),
            id,
            ConsentTemplateData(body.name.orEmpty(), body.text.orEmpty(), body.requiredOnSignup == true, body.controlsMessaging == true, body.active == true),
        ).toResponse()
}
