package com.endoacademy.clinic.registry.adapter.`in`.web

import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.registry.application.CreateProcedure
import com.endoacademy.clinic.registry.application.GetProcedure
import com.endoacademy.clinic.registry.application.ListProcedures
import com.endoacademy.clinic.registry.application.UpdateProcedure
import com.endoacademy.clinic.registry.domain.Procedure
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
import java.util.UUID

/** Procedimentos da clínica ativa (`X-Clinic-Id`). A autorização fica nos casos de uso. */
@RestController
@RequestMapping("/api/v1/procedures")
class ProcedureController(
    private val listProcedures: ListProcedures,
    private val getProcedure: GetProcedure,
    private val createProcedure: CreateProcedure,
    private val updateProcedure: UpdateProcedure,
) {
    data class ProcedureCreateRequest(val name: String?, val description: String?)

    data class ProcedureUpdateRequest(val name: String?, val description: String?, @field:NotNull val active: Boolean?)

    data class ProcedureResponse(val id: UUID, val name: String, val description: String?, val active: Boolean)

    private fun user(request: HttpServletRequest) = request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE) as CurrentUser

    private fun Procedure.toResponse() = ProcedureResponse(id, name, description, active)

    @GetMapping
    fun list(request: HttpServletRequest, @RequestParam(required = false) active: Boolean?): List<ProcedureResponse> =
        listProcedures.list(user(request), active).map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(request: HttpServletRequest, @PathVariable id: UUID): ProcedureResponse = getProcedure.get(user(request), id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(request: HttpServletRequest, @Valid @RequestBody body: ProcedureCreateRequest): ProcedureResponse =
        createProcedure.create(user(request), body.name.orEmpty(), body.description).toResponse()

    @PutMapping("/{id}")
    fun update(request: HttpServletRequest, @PathVariable id: UUID, @Valid @RequestBody body: ProcedureUpdateRequest): ProcedureResponse =
        updateProcedure.update(user(request), id, body.name.orEmpty(), body.description, body.active == true).toResponse()
}
