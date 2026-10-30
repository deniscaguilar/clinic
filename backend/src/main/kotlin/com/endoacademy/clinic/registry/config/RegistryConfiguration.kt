package com.endoacademy.clinic.registry.config

import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.ClinicMembers
import com.endoacademy.clinic.platform.api.PlatformAdminAccess
import com.endoacademy.clinic.registry.adapter.out.SpringEventPublisher
import com.endoacademy.clinic.registry.adapter.out.persistence.JdbcConsentGrantStore
import com.endoacademy.clinic.registry.adapter.out.persistence.JdbcConsentTemplateStore
import com.endoacademy.clinic.registry.adapter.out.persistence.JdbcConsentTypeStore
import com.endoacademy.clinic.registry.adapter.out.persistence.JdbcPatientStore
import com.endoacademy.clinic.registry.adapter.out.persistence.JdbcPractitionerStore
import com.endoacademy.clinic.registry.adapter.out.persistence.JdbcProcedureStore
import com.endoacademy.clinic.registry.adapter.out.persistence.SpringTransactions
import com.endoacademy.clinic.registry.api.RegistryApi
import com.endoacademy.clinic.registry.application.AcceptConsent
import com.endoacademy.clinic.registry.application.ConsentGrantStore
import com.endoacademy.clinic.registry.application.ConsentTemplateStore
import com.endoacademy.clinic.registry.application.ConsentTypeStore
import com.endoacademy.clinic.registry.application.CreateConsentTemplate
import com.endoacademy.clinic.registry.application.CreateConsentType
import com.endoacademy.clinic.registry.application.CreateProcedure
import com.endoacademy.clinic.registry.application.EventPublisher
import com.endoacademy.clinic.registry.application.GetConsentTemplate
import com.endoacademy.clinic.registry.application.GetConsentType
import com.endoacademy.clinic.registry.application.GetPatient
import com.endoacademy.clinic.registry.application.ImportConsentTemplate
import com.endoacademy.clinic.registry.application.ListAvailableConsentTemplates
import com.endoacademy.clinic.registry.application.ListConsentTemplates
import com.endoacademy.clinic.registry.application.ListConsentTypes
import com.endoacademy.clinic.registry.application.GetPractitioner
import com.endoacademy.clinic.registry.application.GetProcedure
import com.endoacademy.clinic.registry.application.ListPatientConsents
import com.endoacademy.clinic.registry.application.ListPatients
import com.endoacademy.clinic.registry.application.ListPractitioners
import com.endoacademy.clinic.registry.application.ListProcedures
import com.endoacademy.clinic.registry.application.PatientStore
import com.endoacademy.clinic.registry.application.PractitionerStore
import com.endoacademy.clinic.registry.application.ProcedureStore
import com.endoacademy.clinic.registry.application.RegisterPatient
import com.endoacademy.clinic.registry.application.RegistryFacade
import com.endoacademy.clinic.registry.application.ReviseConsentType
import com.endoacademy.clinic.registry.application.RegisterPractitioner
import com.endoacademy.clinic.registry.application.RevokeConsent
import com.endoacademy.clinic.registry.application.SearchPatientByCpf
import com.endoacademy.clinic.registry.application.Transactions
import com.endoacademy.clinic.registry.application.UpdateConsentTemplate
import com.endoacademy.clinic.registry.application.UpdatePatient
import com.endoacademy.clinic.registry.application.UpdatePractitioner
import com.endoacademy.clinic.registry.application.UpdateProcedure
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class RegistryConfiguration {

    @Bean
    fun registryTransactions(transactionManager: PlatformTransactionManager): Transactions = SpringTransactions(transactionManager)

    @Bean
    fun procedureStore(jdbc: JdbcTemplate): ProcedureStore = JdbcProcedureStore(jdbc)

    @Bean
    fun listProcedures(authorizer: Authorizer, store: ProcedureStore, transactions: Transactions) = ListProcedures(authorizer, store, transactions)

    @Bean
    fun getProcedure(authorizer: Authorizer, store: ProcedureStore, transactions: Transactions) = GetProcedure(authorizer, store, transactions)

    @Bean
    fun createProcedure(authorizer: Authorizer, store: ProcedureStore, auditTrail: AuditTrail, transactions: Transactions) =
        CreateProcedure(authorizer, store, auditTrail, transactions)

    @Bean
    fun updateProcedure(authorizer: Authorizer, store: ProcedureStore, auditTrail: AuditTrail, transactions: Transactions) =
        UpdateProcedure(authorizer, store, auditTrail, transactions)

    @Bean
    fun practitionerStore(jdbc: JdbcTemplate): PractitionerStore = JdbcPractitionerStore(jdbc)

    @Bean
    fun listPractitioners(authorizer: Authorizer, store: PractitionerStore, transactions: Transactions) = ListPractitioners(authorizer, store, transactions)

    @Bean
    fun getPractitioner(authorizer: Authorizer, store: PractitionerStore, transactions: Transactions) = GetPractitioner(authorizer, store, transactions)

    @Bean
    fun registerPractitioner(authorizer: Authorizer, store: PractitionerStore, members: ClinicMembers, auditTrail: AuditTrail, transactions: Transactions) =
        RegisterPractitioner(authorizer, store, members, auditTrail, transactions)

    @Bean
    fun updatePractitioner(authorizer: Authorizer, store: PractitionerStore, members: ClinicMembers, auditTrail: AuditTrail, transactions: Transactions) =
        UpdatePractitioner(authorizer, store, members, auditTrail, transactions)

    @Bean
    fun patientStore(jdbc: JdbcTemplate): PatientStore = JdbcPatientStore(jdbc)

    @Bean
    fun registerPatient(
        authorizer: Authorizer,
        store: PatientStore,
        types: ConsentTypeStore,
        grants: ConsentGrantStore,
        events: EventPublisher,
        auditTrail: AuditTrail,
        transactions: Transactions,
        clock: Clock,
    ) = RegisterPatient(authorizer, store, types, grants, events, auditTrail, transactions, clock)

    @Bean
    fun updatePatient(authorizer: Authorizer, store: PatientStore, auditTrail: AuditTrail, transactions: Transactions, clock: Clock) =
        UpdatePatient(authorizer, store, auditTrail, transactions, clock)

    @Bean
    fun getPatient(authorizer: Authorizer, store: PatientStore, auditTrail: AuditTrail, transactions: Transactions) =
        GetPatient(authorizer, store, auditTrail, transactions)

    @Bean
    fun listPatients(authorizer: Authorizer, store: PatientStore, auditTrail: AuditTrail, transactions: Transactions) =
        ListPatients(authorizer, store, auditTrail, transactions)

    @Bean
    fun searchPatientByCpf(authorizer: Authorizer, store: PatientStore, auditTrail: AuditTrail, transactions: Transactions) =
        SearchPatientByCpf(authorizer, store, auditTrail, transactions)

    @Bean
    fun consentTypeStore(jdbc: JdbcTemplate): ConsentTypeStore = JdbcConsentTypeStore(jdbc)

    @Bean
    fun consentTemplateStore(jdbc: JdbcTemplate): ConsentTemplateStore = JdbcConsentTemplateStore(jdbc)

    @Bean
    fun listConsentTypes(authorizer: Authorizer, store: ConsentTypeStore, transactions: Transactions) = ListConsentTypes(authorizer, store, transactions)

    @Bean
    fun getConsentType(authorizer: Authorizer, store: ConsentTypeStore, transactions: Transactions) = GetConsentType(authorizer, store, transactions)

    @Bean
    fun createConsentType(authorizer: Authorizer, store: ConsentTypeStore, auditTrail: AuditTrail, transactions: Transactions, clock: Clock) =
        CreateConsentType(authorizer, store, auditTrail, transactions, clock)

    @Bean
    fun reviseConsentType(authorizer: Authorizer, store: ConsentTypeStore, auditTrail: AuditTrail, transactions: Transactions, clock: Clock) =
        ReviseConsentType(authorizer, store, auditTrail, transactions, clock)

    @Bean
    fun listAvailableConsentTemplates(authorizer: Authorizer, templates: ConsentTemplateStore, transactions: Transactions) =
        ListAvailableConsentTemplates(authorizer, templates, transactions)

    @Bean
    fun importConsentTemplate(authorizer: Authorizer, store: ConsentTypeStore, templates: ConsentTemplateStore, auditTrail: AuditTrail, transactions: Transactions, clock: Clock) =
        ImportConsentTemplate(authorizer, store, templates, auditTrail, transactions, clock)

    @Bean
    fun listConsentTemplates(access: PlatformAdminAccess, store: ConsentTemplateStore, transactions: Transactions) = ListConsentTemplates(access, store, transactions)

    @Bean
    fun getConsentTemplate(access: PlatformAdminAccess, store: ConsentTemplateStore, transactions: Transactions) = GetConsentTemplate(access, store, transactions)

    @Bean
    fun createConsentTemplate(access: PlatformAdminAccess, store: ConsentTemplateStore, auditTrail: AuditTrail, transactions: Transactions) =
        CreateConsentTemplate(access, store, auditTrail, transactions)

    @Bean
    fun updateConsentTemplate(access: PlatformAdminAccess, store: ConsentTemplateStore, auditTrail: AuditTrail, transactions: Transactions) =
        UpdateConsentTemplate(access, store, auditTrail, transactions)

    @Bean
    fun consentGrantStore(jdbc: JdbcTemplate): ConsentGrantStore = JdbcConsentGrantStore(jdbc)

    @Bean
    fun registryEventPublisher(publisher: ApplicationEventPublisher): EventPublisher = SpringEventPublisher(publisher)

    @Bean
    fun acceptConsent(
        authorizer: Authorizer,
        patients: PatientStore,
        types: ConsentTypeStore,
        grants: ConsentGrantStore,
        events: EventPublisher,
        auditTrail: AuditTrail,
        transactions: Transactions,
        clock: Clock,
    ) = AcceptConsent(authorizer, patients, types, grants, events, auditTrail, transactions, clock)

    @Bean
    fun revokeConsent(authorizer: Authorizer, grants: ConsentGrantStore, events: EventPublisher, auditTrail: AuditTrail, transactions: Transactions, clock: Clock) =
        RevokeConsent(authorizer, grants, events, auditTrail, transactions, clock)

    @Bean
    fun listPatientConsents(
        authorizer: Authorizer,
        patients: PatientStore,
        types: ConsentTypeStore,
        grants: ConsentGrantStore,
        auditTrail: AuditTrail,
        transactions: Transactions,
    ) = ListPatientConsents(authorizer, patients, types, grants, auditTrail, transactions)

    @Bean
    fun registryApi(patients: PatientStore, types: ConsentTypeStore, grants: ConsentGrantStore, transactions: Transactions): RegistryApi =
        RegistryFacade(patients, types, grants, transactions)
}
