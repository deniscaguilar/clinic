package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.adapter.out.persistence.JdbcAuditQueryStore
import com.endoacademy.clinic.platform.adapter.out.persistence.JdbcRoleStore
import com.endoacademy.clinic.platform.adapter.out.persistence.SpringTransactions
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.application.AuditQueryStore
import com.endoacademy.clinic.platform.application.CreateRole
import com.endoacademy.clinic.platform.application.DeleteRole
import com.endoacademy.clinic.platform.application.ListPermissions
import com.endoacademy.clinic.platform.application.ListRoles
import com.endoacademy.clinic.platform.application.PermissionCatalog
import com.endoacademy.clinic.platform.application.QueryAuditTrail
import com.endoacademy.clinic.platform.application.RoleStore
import com.endoacademy.clinic.platform.application.Transactions
import com.endoacademy.clinic.platform.application.UpdateRole
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper

@Configuration(proxyBeanMethods = false)
class RoleConfiguration {

    @Bean
    fun transactions(transactionManager: PlatformTransactionManager): Transactions = SpringTransactions(transactionManager)

    @Bean
    fun roleStore(jdbc: JdbcTemplate): RoleStore = JdbcRoleStore(jdbc)

    @Bean
    fun listRoles(authorizer: Authorizer, store: RoleStore, transactions: Transactions) = ListRoles(authorizer, store, transactions)

    @Bean
    fun listPermissions(authorizer: Authorizer, catalog: PermissionCatalog) = ListPermissions(authorizer, catalog)

    @Bean
    fun createRole(authorizer: Authorizer, store: RoleStore, catalog: PermissionCatalog, auditTrail: AuditTrail, transactions: Transactions) =
        CreateRole(authorizer, store, catalog, auditTrail, transactions)

    @Bean
    fun updateRole(authorizer: Authorizer, store: RoleStore, catalog: PermissionCatalog, auditTrail: AuditTrail, transactions: Transactions) =
        UpdateRole(authorizer, store, catalog, auditTrail, transactions)

    @Bean
    fun deleteRole(authorizer: Authorizer, store: RoleStore, auditTrail: AuditTrail, transactions: Transactions) =
        DeleteRole(authorizer, store, auditTrail, transactions)

    @Bean
    fun auditQueryStore(jdbc: JdbcTemplate, json: JsonMapper): AuditQueryStore = JdbcAuditQueryStore(jdbc, json)

    @Bean
    fun queryAuditTrail(authorizer: Authorizer, store: AuditQueryStore, transactions: Transactions) = QueryAuditTrail(authorizer, store, transactions)
}
