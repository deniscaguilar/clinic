package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.adapter.out.SpringEventPublisher
import com.endoacademy.clinic.platform.adapter.out.persistence.JdbcClinicStore
import com.endoacademy.clinic.platform.adapter.out.persistence.JdbcPlatformAdmins
import com.endoacademy.clinic.platform.adapter.out.persistence.PlatformUnitOfWork
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.PlatformAdminAccess
import com.endoacademy.clinic.platform.application.ClinicStore
import com.endoacademy.clinic.platform.application.CreateClinic
import com.endoacademy.clinic.platform.application.EventPublisher
import com.endoacademy.clinic.platform.application.IdentityProvider
import com.endoacademy.clinic.platform.application.PermissionCatalog
import com.endoacademy.clinic.platform.application.PlatformAdmins
import com.endoacademy.clinic.platform.application.GuardedPlatformAdminAccess
import com.endoacademy.clinic.platform.application.PlatformGuard
import com.endoacademy.clinic.platform.application.ResendClinicAdminInvitation
import com.endoacademy.clinic.platform.application.SetClinicStatus
import com.endoacademy.clinic.platform.application.UnitOfWork
import com.endoacademy.clinic.platform.application.UpdateClinic
import com.endoacademy.clinic.platform.application.GetClinic
import com.endoacademy.clinic.platform.application.ListClinics
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
class ClinicAdministrationConfiguration {

    @Bean
    fun platformAdmins(jdbc: JdbcTemplate, transactionManager: PlatformTransactionManager): PlatformAdmins = JdbcPlatformAdmins(jdbc, transactionManager)

    @Bean
    fun clinicStore(jdbc: JdbcTemplate): ClinicStore = JdbcClinicStore(jdbc)

    @Bean
    fun unitOfWork(transactionManager: PlatformTransactionManager): UnitOfWork = PlatformUnitOfWork(transactionManager)

    @Bean
    fun eventPublisher(publisher: ApplicationEventPublisher): EventPublisher = SpringEventPublisher(publisher)

    @Bean
    fun platformGuard(admins: PlatformAdmins, unitOfWork: UnitOfWork, auditTrail: AuditTrail) = PlatformGuard(admins, unitOfWork, auditTrail)

    @Bean
    fun platformAdminAccess(guard: PlatformGuard): PlatformAdminAccess = GuardedPlatformAdminAccess(guard)

    @Bean
    fun createClinic(
        guard: PlatformGuard,
        store: ClinicStore,
        unitOfWork: UnitOfWork,
        identityProvider: IdentityProvider,
        catalog: PermissionCatalog,
        auditTrail: AuditTrail,
        events: EventPublisher,
    ) = CreateClinic(guard, store, unitOfWork, identityProvider, catalog, auditTrail, events)

    @Bean
    fun listClinics(guard: PlatformGuard, store: ClinicStore, unitOfWork: UnitOfWork) = ListClinics(guard, store, unitOfWork)

    @Bean
    fun getClinic(guard: PlatformGuard, store: ClinicStore, unitOfWork: UnitOfWork) = GetClinic(guard, store, unitOfWork)

    @Bean
    fun updateClinic(guard: PlatformGuard, store: ClinicStore, unitOfWork: UnitOfWork, auditTrail: AuditTrail) =
        UpdateClinic(guard, store, unitOfWork, auditTrail)

    @Bean
    fun setClinicStatus(guard: PlatformGuard, store: ClinicStore, unitOfWork: UnitOfWork, auditTrail: AuditTrail) =
        SetClinicStatus(guard, store, unitOfWork, auditTrail)

    @Bean
    fun resendClinicAdminInvitation(
        guard: PlatformGuard,
        store: ClinicStore,
        unitOfWork: UnitOfWork,
        identityProvider: IdentityProvider,
        auditTrail: AuditTrail,
    ) = ResendClinicAdminInvitation(guard, store, unitOfWork, identityProvider, auditTrail)
}
