package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.api.TenantScope
import jakarta.persistence.EntityManagerFactory
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Aplica o contexto de tenant (ADR-0010) em cada transação nova, na mesma conexão que o Hibernate e o
 * JdbcTemplate vão usar. `set_config(..., true)` vale só até o fim da transação, então nada vaza no pool.
 * Os três valores são sempre definidos (vazios sem contexto): a política de RLS trata vazio como "sem tenant".
 */
class TenantAwareTransactionManager(entityManagerFactory: EntityManagerFactory) : JpaTransactionManager(entityManagerFactory) {

    override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        super.doBegin(transaction, definition)
        val dataSource = requireNotNull(dataSource) { "A DataSource da aplicação não foi detectada" }
        val connection = (TransactionSynchronizationManager.getResource(dataSource) as ConnectionHolder).connection
        val context = TenantScope.current()
        connection.prepareStatement(
            "select set_config('app.tenant_id', ?, true), set_config('app.user_sub', ?, true), set_config('app.scope', ?, true)",
        ).use { statement ->
            statement.setString(1, context?.tenantId?.toString() ?: "")
            statement.setString(2, context?.userSub ?: "")
            statement.setString(3, if (context?.platformScope == true) "platform" else "")
            statement.execute()
        }
    }
}
