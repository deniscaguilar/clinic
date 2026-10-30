package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.platform.application.PlatformActor
import com.endoacademy.clinic.platform.application.PlatformAdmins
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** O administrador da plataforma enxerga a si mesmo pelo `sub` (RLS de `platform_admin`, ADR-0010). */
class JdbcPlatformAdmins(private val jdbc: JdbcTemplate, transactionManager: PlatformTransactionManager) : PlatformAdmins {

    private val transaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    override fun find(sub: String): PlatformActor? = TenantScope.runAsUser(sub) {
        transaction.execute {
            jdbc.query(
                """select u.id from platform.platform_admin a join platform.app_user u on u.id = a.user_id where u.sub = ?""",
                { rs, _ -> PlatformActor(rs.getObject("id", UUID::class.java), sub) },
                sub,
            ).firstOrNull()
        }
    }
}
