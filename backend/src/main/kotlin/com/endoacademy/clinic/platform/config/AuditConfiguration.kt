package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.adapter.out.persistence.JdbcAuditTrail
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.application.PermissionAuthorizer
import com.endoacademy.clinic.platform.application.PermissionCatalog
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class AuditConfiguration {

    /** Porta do relógio (CLAUDE.md, seção 3); os testes trocam por um fixo. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun auditTrail(jdbc: JdbcTemplate, transactionManager: PlatformTransactionManager, clock: Clock, json: JsonMapper): AuditTrail =
        JdbcAuditTrail(jdbc, transactionManager, clock, json)

    @Bean
    fun authorizer(catalog: PermissionCatalog, auditTrail: AuditTrail): Authorizer = PermissionAuthorizer(catalog, auditTrail)
}
