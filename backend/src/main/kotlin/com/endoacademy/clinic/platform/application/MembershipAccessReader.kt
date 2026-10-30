package com.endoacademy.clinic.platform.application

import java.util.UUID

/** Vínculo de um usuário com uma clínica, como o banco o vê. */
data class MembershipAccess(
    val userId: UUID,
    val membershipActive: Boolean,
    val clinicActive: Boolean,
    val permissions: Set<String>,
)

/**
 * Port de saída: lê o vínculo do usuário (pelo `sub` do token) com a clínica pedida.
 * O adaptador roda em `TenantScope.runAsUser(sub)`, pois ainda não existe clínica ativa. `null` = sem vínculo.
 */
fun interface MembershipAccessReader {
    fun find(userSub: String, clinicId: UUID): MembershipAccess?
}
