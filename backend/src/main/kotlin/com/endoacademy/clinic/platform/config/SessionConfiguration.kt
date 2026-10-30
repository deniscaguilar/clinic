package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.adapter.out.persistence.JdbcUserDirectory
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.application.GetBranding
import com.endoacademy.clinic.platform.application.GetMyProfile
import com.endoacademy.clinic.platform.application.ListMyClinics
import com.endoacademy.clinic.platform.application.ResolveSession
import com.endoacademy.clinic.platform.application.UserDirectory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
class SessionConfiguration {

    @Bean
    fun userDirectory(jdbc: JdbcTemplate, transactionManager: PlatformTransactionManager): UserDirectory = JdbcUserDirectory(jdbc, transactionManager)

    @Bean
    fun getMyProfile(directory: UserDirectory) = GetMyProfile(directory)

    @Bean
    fun listMyClinics(directory: UserDirectory) = ListMyClinics(directory)

    @Bean
    fun resolveSession(directory: UserDirectory, auditTrail: AuditTrail, getBranding: GetBranding) = ResolveSession(directory, auditTrail, getBranding)
}
