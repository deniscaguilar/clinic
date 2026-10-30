package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.api.ActiveClinics
import com.endoacademy.clinic.platform.api.TenantScope
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Lê `platform.clinic` no escopo `platform`, que a ADR-0010 já libera para essa tabela (e para nenhuma de paciente).
 * Abre a própria transação; o chamador não pode estar dentro de uma com outro contexto de tenant.
 */
class JdbcActiveClinics(private val jdbc: JdbcTemplate, transactionManager: PlatformTransactionManager) : ActiveClinics {

    private val template = TransactionTemplate(transactionManager)

    override fun ids(): List<UUID> = TenantScope.runAsPlatform {
        template.execute { jdbc.query("select id from platform.clinic where status = 'ACTIVE' order by id", { rs, _ -> rs.getObject("id", UUID::class.java) }) }.orEmpty()
    }
}
