package com.endoacademy.clinic.registry.adapter.`in`.web

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.application.GetPractitioner
import com.endoacademy.clinic.registry.application.ListPractitioners
import com.endoacademy.clinic.registry.application.PractitionerData
import com.endoacademy.clinic.registry.application.RegisterPractitioner
import com.endoacademy.clinic.registry.application.UpdatePractitioner
import com.endoacademy.clinic.registry.domain.Practitioner
import com.endoacademy.clinic.registry.domain.PractitionerType
import com.endoacademy.clinic.registry.domain.WorkInterval
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
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Profissionais da clínica ativa (`X-Clinic-Id`). O `userId` é de um usuário que já é membro ativo da clínica
 * (plano, seção 14); esta rota não cria login. A autorização fica nos casos de uso.
 */
@RestController
@RequestMapping("/api/v1/practitioners")
class PractitionerController(
    private val listPractitioners: ListPractitioners,
    private val getPractitioner: GetPractitioner,
    private val registerPractitioner: RegisterPractitioner,
    private val updatePractitioner: UpdatePractitioner,
) {
    data class IntervalBody(val dayOfWeek: String?, val start: String?, val end: String?)

    data class PractitionerRegisterRequest(
        val name: String?,
        @field:NotNull val type: String?,
        val registrationNumber: String?,
        val userId: UUID?,
        val workSchedule: List<IntervalBody>?,
    )

    data class PractitionerUpdateRequest(
        val name: String?,
        @field:NotNull val type: String?,
        val registrationNumber: String?,
        val userId: UUID?,
        @field:NotNull val active: Boolean?,
        val workSchedule: List<IntervalBody>?,
    )

    data class IntervalResponse(val dayOfWeek: String, val start: String, val end: String)

    data class PractitionerResponse(
        val id: UUID,
        val name: String,
        val type: String,
        val registrationNumber: String?,
        val userId: UUID?,
        val active: Boolean,
        val workSchedule: List<IntervalResponse>,
    )

    private fun user(request: HttpServletRequest) = request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE) as CurrentUser

    private fun Practitioner.toResponse() = PractitionerResponse(
        id,
        name,
        type.name,
        registrationNumber,
        userId,
        active,
        schedule.intervals.map { IntervalResponse(it.dayOfWeek.name, it.start.toString(), it.end.toString()) },
    )

    private fun type(text: String?): PractitionerType =
        PractitionerType.entries.firstOrNull { it.name == text } ?: throw ApiException(ErrorKind.BAD_REQUEST, "PRACTITIONER_TYPE_INVALID")

    private fun intervals(body: List<IntervalBody>?): List<WorkInterval> = body.orEmpty().map {
        try {
            WorkInterval(DayOfWeek.valueOf(it.dayOfWeek.orEmpty().uppercase()), LocalTime.parse(it.start.orEmpty()), LocalTime.parse(it.end.orEmpty()))
        } catch (_: IllegalArgumentException) {
            throw ApiException(ErrorKind.BAD_REQUEST, "WORK_INTERVAL_INVALID")
        } catch (_: DateTimeParseException) {
            throw ApiException(ErrorKind.BAD_REQUEST, "WORK_INTERVAL_INVALID")
        }
    }

    @GetMapping
    fun list(request: HttpServletRequest, @RequestParam(required = false) active: Boolean?): List<PractitionerResponse> =
        listPractitioners.list(user(request), active).map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(request: HttpServletRequest, @PathVariable id: UUID): PractitionerResponse = getPractitioner.get(user(request), id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(request: HttpServletRequest, @Valid @RequestBody body: PractitionerRegisterRequest): PractitionerResponse =
        registerPractitioner.register(
            user(request),
            PractitionerData(body.name.orEmpty(), type(body.type), body.registrationNumber, body.userId, intervals(body.workSchedule)),
        ).toResponse()

    @PutMapping("/{id}")
    fun update(request: HttpServletRequest, @PathVariable id: UUID, @Valid @RequestBody body: PractitionerUpdateRequest): PractitionerResponse =
        updatePractitioner.update(
            user(request),
            id,
            PractitionerData(body.name.orEmpty(), type(body.type), body.registrationNumber, body.userId, intervals(body.workSchedule), body.active == true),
        ).toResponse()
}
