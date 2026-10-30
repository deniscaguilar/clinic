package com.endoacademy.clinic.platform.application

import com.endoacademy.clinic.platform.api.CurrentUser
import java.util.UUID

sealed interface ClinicAccessResult {
    data class Granted(val user: CurrentUser) : ClinicAccessResult

    /** `reason` é só para auditoria e log; a resposta ao cliente é sempre a mesma 403, sem distinguir os casos. */
    data class Denied(val reason: DenialReason) : ClinicAccessResult
}

enum class DenialReason { NO_MEMBERSHIP, INACTIVE_MEMBERSHIP, INACTIVE_CLINIC }

/** Valida a clínica ativa pedida contra o vínculo do usuário (ADR-0010: cabeçalho `X-Clinic-Id`, sem sessão). */
class ResolveClinicAccess(private val reader: MembershipAccessReader) {

    fun resolve(userSub: String, clinicId: UUID): ClinicAccessResult {
        val access = reader.find(userSub, clinicId) ?: return ClinicAccessResult.Denied(DenialReason.NO_MEMBERSHIP)
        if (!access.membershipActive) return ClinicAccessResult.Denied(DenialReason.INACTIVE_MEMBERSHIP)
        if (!access.clinicActive) return ClinicAccessResult.Denied(DenialReason.INACTIVE_CLINIC)
        return ClinicAccessResult.Granted(CurrentUser(access.userId, userSub, clinicId, access.permissions))
    }
}
