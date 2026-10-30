package com.endoacademy.clinic.platform.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.ErrorKind
import java.util.UUID

private fun clinicNotFound() = ApiException(ErrorKind.NOT_FOUND, "CLINIC_NOT_FOUND")

/** `ListClinics` (`PLATFORM_ADMIN`): as clínicas da plataforma com a situação de cada uma. Leitura de cadastro da clínica, sem dado de paciente: não audita. */
class ListClinics(
    private val guard: PlatformGuard,
    private val store: ClinicStore,
    private val unitOfWork: UnitOfWork,
) {
    fun list(actorSub: String): List<ClinicOverview> {
        guard.require(actorSub)
        return unitOfWork.inPlatformScope(null) { store.listClinics() }
    }
}

data class ClinicDetail(val clinic: ClinicOverview, val admins: List<ClinicAdmin>)

/** `GetClinic` (`PLATFORM_ADMIN`): a clínica e seus administradores (para reenviar convite pendente). */
class GetClinic(
    private val guard: PlatformGuard,
    private val store: ClinicStore,
    private val unitOfWork: UnitOfWork,
) {
    fun get(actorSub: String, clinicId: UUID): ClinicDetail {
        guard.require(actorSub)
        return unitOfWork.inPlatformScope(clinicId) {
            val clinic = store.findClinic(clinicId) ?: throw clinicNotFound()
            ClinicDetail(clinic, store.listClinicAdmins(clinicId))
        }
    }
}

/** `UpdateClinic` (`PLATFORM_ADMIN`): por ora só o nome; o branding tem caso de uso próprio (S3). */
class UpdateClinic(
    private val guard: PlatformGuard,
    private val store: ClinicStore,
    private val unitOfWork: UnitOfWork,
    private val auditTrail: AuditTrail,
) {
    fun update(actorSub: String, clinicId: UUID, name: String) {
        val actor = guard.require(actorSub)
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME) throw ApiException(ErrorKind.BAD_REQUEST, "VALIDATION_FAILED")
        unitOfWork.inPlatformScope(clinicId) {
            if (!store.updateClinicName(clinicId, trimmed)) throw clinicNotFound()
            auditTrail.recordPlatform(actor.userId, clinicId, AuditEvent("UPDATE_CLINIC", "CLINIC", clinicId.toString(), details = setOf("name")))
        }
    }

    private companion object {
        const val MAX_NAME = 120
    }
}

/**
 * `DeactivateClinic` e `ActivateClinic` (`PLATFORM_ADMIN`). Clínica `INACTIVE` bloqueia todos os vínculos (o filtro de
 * `X-Clinic-Id` recusa com 403) e **nada é apagado**. Repetir a operação não faz nada e não audita.
 */
class SetClinicStatus(
    private val guard: PlatformGuard,
    private val store: ClinicStore,
    private val unitOfWork: UnitOfWork,
    private val auditTrail: AuditTrail,
) {
    fun deactivate(actorSub: String, clinicId: UUID) = change(actorSub, clinicId, ClinicStatus.INACTIVE, "DEACTIVATE_CLINIC")

    fun activate(actorSub: String, clinicId: UUID) = change(actorSub, clinicId, ClinicStatus.ACTIVE, "ACTIVATE_CLINIC")

    private fun change(actorSub: String, clinicId: UUID, target: ClinicStatus, action: String) {
        val actor = guard.require(actorSub)
        unitOfWork.inPlatformScope(clinicId) {
            val previous = store.setClinicStatus(clinicId, target) ?: throw clinicNotFound()
            if (previous != target) auditTrail.recordPlatform(actor.userId, clinicId, AuditEvent(action, "CLINIC", clinicId.toString()))
        }
    }
}

/**
 * `ResendInvitation` do administrador de uma clínica (`PLATFORM_ADMIN`): só para vínculo `PENDING`. Sem login no
 * provedor, cria o login; com login, reenvia o convite. A chamada ao provedor fica fora de transação (plano, seção 2).
 */
class ResendClinicAdminInvitation(
    private val guard: PlatformGuard,
    private val store: ClinicStore,
    private val unitOfWork: UnitOfWork,
    private val identityProvider: IdentityProvider,
    private val auditTrail: AuditTrail,
) {
    fun resend(actorSub: String, clinicId: UUID, membershipId: UUID): MembershipStatus {
        val actor = guard.require(actorSub)
        val membership = unitOfWork.inPlatformScope(clinicId) { store.findMembership(clinicId, membershipId) }
            ?: throw ApiException(ErrorKind.NOT_FOUND, "MEMBERSHIP_NOT_FOUND")
        if (membership.status != MembershipStatus.PENDING) throw ApiException(ErrorKind.CONFLICT, "INVITATION_NOT_PENDING")

        val sub = try {
            membership.sub?.also { identityProvider.resendInvitation(membership.email) } ?: identityProvider.createUser(membership.email, membership.name)
        } catch (_: IdentityProviderException) {
            unitOfWork.inPlatformScope(clinicId) {
                auditTrail.recordPlatform(actor.userId, clinicId, AuditEvent("RESEND_INVITATION", "MEMBERSHIP", membershipId.toString(), AuditOutcome.FAILURE))
            }
            throw ApiException(ErrorKind.BAD_GATEWAY, "IDENTITY_PROVIDER_UNAVAILABLE")
        }
        unitOfWork.inPlatformScope(clinicId) {
            if (membership.sub == null) store.setUserSub(membership.userId, sub)
            store.activateMembership(clinicId, membershipId, sub)
            auditTrail.recordPlatform(actor.userId, clinicId, AuditEvent("RESEND_INVITATION", "MEMBERSHIP", membershipId.toString()))
        }
        return MembershipStatus.ACTIVE
    }
}
