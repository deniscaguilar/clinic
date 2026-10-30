package com.endoacademy.clinic.notifications.config

import com.endoacademy.clinic.notifications.adapter.`in`.NotificationWorker
import com.endoacademy.clinic.notifications.adapter.`in`.event.ConsentEventListeners
import com.endoacademy.clinic.notifications.adapter.out.PlatformClinicDirectory
import com.endoacademy.clinic.notifications.adapter.out.email.InMemoryEmailSender
import com.endoacademy.clinic.notifications.adapter.out.email.SesEmailSender
import com.endoacademy.clinic.notifications.adapter.out.persistence.JdbcNotificationStore
import com.endoacademy.clinic.notifications.adapter.out.persistence.SpringTransactions
import com.endoacademy.clinic.notifications.adapter.out.registry.RegistryConsentInfoLookup
import com.endoacademy.clinic.notifications.adapter.out.registry.RegistryMessagingConsentLookup
import com.endoacademy.clinic.notifications.adapter.out.registry.RegistryPatientContactLookup
import com.endoacademy.clinic.notifications.adapter.out.whatsapp.FakeWhatsAppSender
import com.endoacademy.clinic.notifications.application.ClinicDirectory
import com.endoacademy.clinic.notifications.application.ConsentInfoLookup
import com.endoacademy.clinic.notifications.application.DispatchPending
import com.endoacademy.clinic.notifications.application.EmailSender
import com.endoacademy.clinic.notifications.application.MessagingConsentLookup
import com.endoacademy.clinic.notifications.application.ListNotifications
import com.endoacademy.clinic.notifications.application.NotificationQueryStore
import com.endoacademy.clinic.notifications.application.NotificationStore
import com.endoacademy.clinic.notifications.application.OnConsentAccepted
import com.endoacademy.clinic.notifications.application.OnConsentRevoked
import com.endoacademy.clinic.notifications.application.PatientContactLookup
import com.endoacademy.clinic.notifications.application.Transactions
import com.endoacademy.clinic.notifications.application.WhatsAppSender
import com.endoacademy.clinic.notifications.domain.RetryPolicy
import com.endoacademy.clinic.platform.api.ActiveClinics
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.registry.api.RegistryApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration

/**
 * Os ouvintes de evento são `@Async`: o aceite ou a revogação nunca esperam pela fila de notificações. Quem liga o
 * `@Async` é o Spring Modulith (`EventPublicationAutoConfiguration.AsyncEnablingConfiguration`), por isso não há
 * `@EnableAsync` aqui; o `NotificationFlowIT` ("o aceite não espera o tratador") prova o comportamento.
 */
@Configuration(proxyBeanMethods = false)
class NotificationsConfiguration {

    @Bean
    fun notificationsTransactions(transactionManager: PlatformTransactionManager): Transactions = SpringTransactions(transactionManager)

    /** Um só bean para as duas portas (`NotificationStore` e `NotificationQueryStore`): a fila e a leitura da fila. */
    @Bean
    fun notificationStore(jdbc: JdbcTemplate): JdbcNotificationStore = JdbcNotificationStore(jdbc)

    @Bean
    fun listNotifications(authorizer: Authorizer, store: NotificationQueryStore, auditTrail: AuditTrail, transactions: Transactions) =
        ListNotifications(authorizer, store, auditTrail, transactions)

    @Bean
    fun onConsentAccepted(contacts: PatientContactLookup, store: NotificationStore, transactions: Transactions, clock: Clock) =
        OnConsentAccepted(contacts, store, transactions, clock)

    @Bean
    fun onConsentRevoked(store: NotificationStore, transactions: Transactions) = OnConsentRevoked(store, transactions)

    @Bean
    fun consentEventListeners(onAccepted: OnConsentAccepted, onRevoked: OnConsentRevoked) = ConsentEventListeners(onAccepted, onRevoked)

    @Bean
    fun patientContactLookup(registry: RegistryApi): PatientContactLookup = RegistryPatientContactLookup(registry)

    @Bean
    fun messagingConsentLookup(registry: RegistryApi): MessagingConsentLookup = RegistryMessagingConsentLookup(registry)

    /** Sem provedor de WhatsApp definido (pergunta em aberto do M0), só o adaptador falso, que nunca registra o destinatário. */
    @Bean
    fun whatsAppSender(): FakeWhatsAppSender = FakeWhatsAppSender()

    /**
     * Escolhe o adaptador de e-mail por `clinic.notifications.email.provider` (`memory` ou `ses`). Sem valor a aplicação
     * não sobe (falha fechada): produção nunca guarda e-mail em memória por esquecimento.
     */
    @Bean
    fun emailProviderCheck(@Value("\${clinic.notifications.email.provider}") provider: String): EmailProvider = EmailProvider.parse(provider)

    @Bean
    @ConditionalOnProperty("clinic.notifications.email.provider", havingValue = "memory")
    fun inMemoryEmailSender(): InMemoryEmailSender = InMemoryEmailSender()

    @Bean
    @ConditionalOnProperty("clinic.notifications.email.provider", havingValue = "ses")
    fun sesEmailSender(
        @Value("\${clinic.notifications.email.from}") from: String,
        @Value("\${clinic.notifications.email.ses.region}") region: String,
        @Value("\${clinic.notifications.email.ses.endpoint}") endpoint: String,
    ): EmailSender {
        return SesEmailSender.create(from, region, endpoint.ifBlank { null })
    }

    @Bean
    fun consentInfoLookup(registry: RegistryApi): ConsentInfoLookup = RegistryConsentInfoLookup(registry)

    @Bean
    fun clinicDirectory(activeClinics: ActiveClinics): ClinicDirectory = PlatformClinicDirectory(activeClinics)

    /** A política de retentativa vem da configuração (`clinic.notifications.retry.*`). */
    @Bean
    fun retryPolicy(
        @Value("\${clinic.notifications.retry.max-attempts}") maxAttempts: Int,
        @Value("\${clinic.notifications.retry.base-delay}") baseDelay: Duration,
        @Value("\${clinic.notifications.retry.max-delay}") maxDelay: Duration,
    ) = RetryPolicy(maxAttempts, baseDelay, maxDelay)

    @Bean
    fun dispatchPending(
        clinics: ClinicDirectory,
        store: NotificationStore,
        contacts: PatientContactLookup,
        consent: MessagingConsentLookup,
        consents: ConsentInfoLookup,
        email: EmailSender,
        whatsApp: WhatsAppSender,
        audit: AuditTrail,
        transactions: Transactions,
        clock: Clock,
        policy: RetryPolicy,
        @Value("\${clinic.notifications.worker.batch-per-clinic}") batchPerClinic: Int,
    ) = DispatchPending(clinics, store, contacts, consent, consents, email, whatsApp, audit, transactions, clock, policy, batchPerClinic)
}

/** O agendador do worker. Desligado com `clinic.notifications.worker.enabled=false` (testes, ou uma instância só de API). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty("clinic.notifications.worker.enabled", havingValue = "true")
class NotificationWorkerConfiguration {

    @Bean
    fun notificationWorker(dispatch: DispatchPending) = NotificationWorker(dispatch)
}
