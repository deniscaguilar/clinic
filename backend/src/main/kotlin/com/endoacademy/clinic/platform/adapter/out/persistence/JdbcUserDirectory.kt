package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.platform.application.ClinicSummary
import com.endoacademy.clinic.platform.application.UserDirectory
import com.endoacademy.clinic.platform.application.UserProfile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Consultas de identidade sob RLS (ADR-0010). Cada método abre a sua transação no contexto certo. */
class JdbcUserDirectory(private val jdbc: JdbcTemplate, transactionManager: PlatformTransactionManager) : UserDirectory {

    private val transaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    override fun findProfile(sub: String): UserProfile? = TenantScope.runAsUser(sub) {
        transaction.execute {
            jdbc.query(
                "select id, email, name from platform.app_user where sub = ?",
                { rs, _ -> UserProfile(rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("name")) },
                sub,
            ).firstOrNull()
        }
    }

    override fun listActiveClinics(sub: String): List<ClinicSummary> = TenantScope.runAsUser(sub) {
        transaction.execute {
            jdbc.query(
                """select c.id, c.name
                   from platform.membership m
                   join platform.app_user u on u.id = m.user_id
                   join platform.clinic c on c.id = m.tenant_id
                   where u.sub = ? and m.status = 'ACTIVE' and c.status = 'ACTIVE'
                   order by c.name, c.id""",
                { rs, _ -> ClinicSummary(rs.getObject("id", UUID::class.java), rs.getString("name")) },
                sub,
            )
        }
    }.orEmpty()

    override fun findClinic(clinicId: UUID): ClinicSummary? = TenantScope.runAs(clinicId) {
        transaction.execute {
            jdbc.query(
                "select id, name from platform.clinic where id = ?",
                { rs, _ -> ClinicSummary(rs.getObject("id", UUID::class.java), rs.getString("name")) },
                clinicId,
            ).firstOrNull()
        }
    }

    override fun findRoleNames(clinicId: UUID, userId: UUID): List<String> = TenantScope.runAs(clinicId) {
        transaction.execute {
            jdbc.queryForList(
                """select r.name
                   from platform.membership m
                   join platform.membership_role mr on mr.membership_id = m.id
                   join platform.role r on r.id = mr.role_id
                   where m.user_id = ? and m.tenant_id = ?
                   order by r.name""",
                String::class.java,
                userId,
                clinicId,
            ).filterNotNull()
        }
    }.orEmpty()
}
