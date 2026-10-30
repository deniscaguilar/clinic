package com.endoacademy.clinic.registry.adapter.`in`.web

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.application.AddressData
import com.endoacademy.clinic.registry.application.GetPatient
import com.endoacademy.clinic.registry.application.ListPatients
import com.endoacademy.clinic.registry.application.PatientData
import com.endoacademy.clinic.registry.application.RegisterPatient
import com.endoacademy.clinic.registry.application.SearchPatientByCpf
import com.endoacademy.clinic.registry.application.UpdatePatient
import com.endoacademy.clinic.registry.domain.Patient
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
import java.time.LocalDate
import java.util.UUID

/**
 * Pacientes da clínica ativa (`X-Clinic-Id`): dado pessoal. A autorização e a auditoria de leitura ficam nos casos de
 * uso. O CPF nunca vai em URL nem em query: a busca é um `POST`. Nenhum valor recebido é copiado para erro nem log.
 */
@RestController
@RequestMapping("/api/v1/patients")
class PatientController(
    private val registerPatient: RegisterPatient,
    private val updatePatient: UpdatePatient,
    private val getPatient: GetPatient,
    private val listPatients: ListPatients,
    private val searchPatientByCpf: SearchPatientByCpf,
) {
    data class AddressBody(
        val zipCode: String?,
        val street: String?,
        val number: String?,
        val complement: String?,
        val district: String?,
        val city: String?,
        val state: String?,
    )

    /** Cadastro e atualização. Na atualização, `cpf` ausente ou igual ao gravado mantém o CPF. */
    data class PatientRequest(
        val cpf: String?,
        val fullName: String?,
        val birthDate: LocalDate?,
        val whatsapp: String?,
        val email: String?,
        val landline: String?,
        val address: AddressBody?,
    )

    /** Aceite de um tipo de consentimento no cadastro. */
    data class ConsentRef(@field:NotNull val typeId: UUID?)

    /** Cadastro: os dados do paciente e os tipos de consentimento aceitos (todos os obrigatórios precisam estar). */
    data class PatientRegisterRequest(
        val cpf: String?,
        val fullName: String?,
        val birthDate: LocalDate?,
        val whatsapp: String?,
        val email: String?,
        val landline: String?,
        val address: AddressBody?,
        @field:Valid val consents: List<ConsentRef>?,
    )

    data class ConsentGrantResponse(val id: UUID, val typeId: UUID, val versionNumber: Int, val acceptedAt: Instant)

    data class RegisteredPatientResponse(
        val id: UUID,
        val fullName: String,
        val cpf: String,
        val birthDate: LocalDate,
        val whatsapp: String?,
        val email: String?,
        val landline: String?,
        val address: AddressResponse,
        val consents: List<ConsentGrantResponse>,
    )

    data class SearchRequest(val cpf: String?)

    data class AddressResponse(
        val zipCode: String,
        val street: String,
        val number: String,
        val complement: String?,
        val district: String,
        val city: String,
        val state: String,
    )

    data class PatientResponse(
        val id: UUID,
        val fullName: String,
        val cpf: String,
        val birthDate: LocalDate,
        val whatsapp: String?,
        val email: String?,
        val landline: String?,
        val address: AddressResponse,
    )

    data class PatientSummary(val id: UUID, val fullName: String, val cpf: String, val birthDate: LocalDate)

    data class PatientPageResponse(val items: List<PatientSummary>, val page: Int, val size: Int, val total: Long)

    data class SearchResponse(val items: List<PatientResponse>)

    private fun user(request: HttpServletRequest) = request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE) as CurrentUser

    private fun PatientRequest.toData() = PatientData(
        fullName,
        birthDate,
        whatsapp,
        email,
        landline,
        address?.let { AddressData(it.zipCode, it.street, it.number, it.complement, it.district, it.city, it.state) },
    )

    private fun Patient.toResponse() = PatientResponse(
        id,
        fullName,
        cpf.value,
        birthDate,
        whatsapp?.value,
        email?.value,
        landline?.value,
        AddressResponse(address.zipCode, address.street, address.number, address.complement, address.district, address.city, address.state),
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(request: HttpServletRequest, @Valid @RequestBody body: PatientRegisterRequest): RegisteredPatientResponse {
        val data = PatientRequest(body.cpf, body.fullName, body.birthDate, body.whatsapp, body.email, body.landline, body.address).toData()
        val registration = registerPatient.register(user(request), body.cpf, data, body.consents.orEmpty().map { it.typeId!! })
        val patient = registration.patient.toResponse()
        return RegisteredPatientResponse(
            patient.id, patient.fullName, patient.cpf, patient.birthDate, patient.whatsapp, patient.email, patient.landline, patient.address,
            registration.consents.map { ConsentGrantResponse(it.id, it.typeId, it.versionNumber, it.acceptedAt) },
        )
    }

    @GetMapping
    fun list(
        request: HttpServletRequest,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PatientPageResponse {
        val result = listPatients.list(user(request), name, page, size)
        return PatientPageResponse(result.items.map { PatientSummary(it.id, it.fullName, it.cpf.value, it.birthDate) }, result.page, result.size, result.total)
    }

    @GetMapping("/{id}")
    fun get(request: HttpServletRequest, @PathVariable id: UUID): PatientResponse = getPatient.get(user(request), id).toResponse()

    @PutMapping("/{id}")
    fun update(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: PatientRequest): PatientResponse =
        updatePatient.update(user(request), id, body.cpf, body.toData()).toResponse()

    @PostMapping("/search")
    fun search(request: HttpServletRequest, @RequestBody body: SearchRequest): SearchResponse {
        val patient = searchPatientByCpf.search(user(request), body.cpf)
        return SearchResponse(listOfNotNull(patient?.toResponse()))
    }
}
