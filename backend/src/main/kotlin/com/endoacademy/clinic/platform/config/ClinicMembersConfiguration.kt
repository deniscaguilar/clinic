package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.adapter.out.persistence.JdbcActiveClinics
import com.endoacademy.clinic.platform.adapter.out.persistence.JdbcClinicMembers
import com.endoacademy.clinic.platform.api.ActiveClinics
import com.endoacademy.clinic.platform.api.ClinicMembers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
class ClinicMembersConfiguration {

    @Bean
    fun clinicMembers(jdbc: JdbcTemplate): ClinicMembers = JdbcClinicMembers(jdbc)

    @Bean
    fun activeClinics(jdbc: JdbcTemplate, transactionManager: PlatformTransactionManager): ActiveClinics = JdbcActiveClinics(jdbc, transactionManager)
}
