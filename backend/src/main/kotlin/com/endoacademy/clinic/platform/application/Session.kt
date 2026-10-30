package com.endoacademy.clinic.platform.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import java.util.UUID

data class UserProfile(val id: UUID, val email: String, val name: String)

data class ClinicSummary(val id: UUID, val name: String)

/** Sessão de uma clínica: quem é, onde está, com quais perfis e permissões, e a marca a aplicar. */
data class Session(
    val user: UserProfile,
    val clinic: ClinicSummary,
    val roles: List<String>,
    val permissions: Set<String>,
    val branding: BrandingView,
)

/**
 * Port de saída: consultas de identidade que o `platform` faz sobre o próprio banco. O adaptador define o contexto
 * de tenant de cada consulta (ADR-0010): o que é do usuário roda com o `sub`, o que é da clínica roda no tenant.
 */
interface UserDirectory {
    fun findProfile(sub: String): UserProfile?

    /** Clínicas `ACTIVE` em que o usuário tem vínculo `ACTIVE`. */
    fun listActiveClinics(sub: String): List<ClinicSummary>

    fun findClinic(clinicId: UUID): ClinicSummary?

    fun findRoleNames(clinicId: UUID, userId: UUID): List<String>
}

/** Falha única para usuário que tem token válido mas nunca foi cadastrado/convidado. */
private fun notRegistered() = ApiException(ErrorKind.FORBIDDEN, "USER_NOT_REGISTERED")

class GetMyProfile(private val directory: UserDirectory) {
    fun get(sub: String): UserProfile = directory.findProfile(sub) ?: throw notRegistered()
}

/** Tela de escolha de clínica: não exige clínica ativa (plano, seção 2). */
class ListMyClinics(private val directory: UserDirectory) {
    fun list(sub: String): List<ClinicSummary> = directory.listActiveClinics(sub)
}

/** O `CurrentUser` já foi validado contra vínculo e clínica ativos pelo filtro; aqui só se monta a sessão e audita `LOGIN`. */
class ResolveSession(private val directory: UserDirectory, private val auditTrail: AuditTrail, private val getBranding: GetBranding) {
    fun resolve(user: CurrentUser): Session {
        val profile = directory.findProfile(user.userSub) ?: throw notRegistered()
        val clinic = directory.findClinic(user.clinicId) ?: throw ApiException(ErrorKind.FORBIDDEN, "CLINIC_ACCESS_DENIED")
        val roles = directory.findRoleNames(user.clinicId, user.userId)
        auditTrail.record(user, AuditEvent(action = "LOGIN", entityType = "SESSION", entityId = clinic.id.toString()))
        return Session(profile, clinic, roles, user.permissions.toSortedSet(), getBranding.get(user.clinicId))
    }
}
