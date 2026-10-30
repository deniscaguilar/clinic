package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.adapter.out.persistence.JdbcMembershipAccessReader
import com.endoacademy.clinic.platform.application.MembershipAccessReader
import com.endoacademy.clinic.platform.application.ResolveClinicAccess
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
class ClinicAccessConfiguration {

    @Bean
    fun membershipAccessReader(jdbc: JdbcTemplate, transactionManager: PlatformTransactionManager): MembershipAccessReader =
        JdbcMembershipAccessReader(jdbc, transactionManager)

    @Bean
    fun resolveClinicAccess(reader: MembershipAccessReader) = ResolveClinicAccess(reader)
}
