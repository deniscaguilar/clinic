package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.platform.application.MembershipAccess
import com.endoacademy.clinic.platform.application.MembershipAccessReader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Lê o vínculo do usuário com a clínica pedida, sob RLS (ADR-0010), em duas transações curtas:
 * 1. `runAsUser(sub)`: ainda não existe clínica ativa, então o banco só mostra os vínculos do próprio usuário
 *    (e as clínicas deles). Aqui se descobre o vínculo e os status.
 * 2. `runAs(clínica)`: só se o vínculo e a clínica estão ativos, lê as permissões dos perfis. Perfis e permissões
 *    ficam atrás da política padrão por tenant, que o escopo do usuário não abre.
 */
class JdbcMembershipAccessReader(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : MembershipAccessReader {

    private val transaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    private data class Link(val membershipId: UUID, val userId: UUID, val membershipActive: Boolean, val clinicActive: Boolean)

    override fun find(userSub: String, clinicId: UUID): MembershipAccess? {
        val link = TenantScope.runAsUser(userSub) { transaction.execute { findLink(userSub, clinicId) } } ?: return null
        val permissions = if (link.membershipActive && link.clinicActive) {
            TenantScope.runAs(clinicId) { transaction.execute { findPermissions(link.membershipId) } }.orEmpty()
        } else {
            emptySet()
        }
        return MembershipAccess(link.userId, link.membershipActive, link.clinicActive, permissions)
    }

    private fun findLink(userSub: String, clinicId: UUID): Link? = jdbc.query(
        """select m.id, m.user_id, m.status, c.status as clinic_status
           from platform.membership m
           join platform.app_user u on u.id = m.user_id
           join platform.clinic c on c.id = m.tenant_id
           where u.sub = ? and m.tenant_id = ?""",
        { rs, _ ->
            Link(
                membershipId = rs.getObject("id", UUID::class.java),
                userId = rs.getObject("user_id", UUID::class.java),
                membershipActive = rs.getString("status") == "ACTIVE",
                clinicActive = rs.getString("clinic_status") == "ACTIVE",
            )
        },
        userSub,
        clinicId,
    ).firstOrNull()

    private fun findPermissions(membershipId: UUID): Set<String> = jdbc.queryForList(
        """select distinct rp.permission
           from platform.membership_role mr
           join platform.role_permission rp on rp.role_id = mr.role_id
           where mr.membership_id = ?""",
        String::class.java,
        membershipId,
    ).filterNotNull().toSet()
}
