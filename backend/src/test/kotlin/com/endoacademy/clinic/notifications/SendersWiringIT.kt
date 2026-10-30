package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.notifications.adapter.out.email.InMemoryEmailSender
import com.endoacademy.clinic.notifications.adapter.out.registry.RegistryMessagingConsentLookup
import com.endoacademy.clinic.notifications.adapter.out.registry.RegistryPatientContactLookup
import com.endoacademy.clinic.notifications.adapter.out.whatsapp.FakeWhatsAppSender
import com.endoacademy.clinic.notifications.application.EmailSender
import com.endoacademy.clinic.notifications.application.MessagingConsentLookup
import com.endoacademy.clinic.notifications.application.PatientContactLookup
import com.endoacademy.clinic.notifications.application.WhatsAppSender
import com.endoacademy.clinic.notifications.domain.RetryPolicy
import com.endoacademy.clinic.support.DatabaseIT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import java.time.Duration

/** Quais adaptadores o contexto liga (T5.8): nos testes, o e-mail em memória, o WhatsApp falso e as consultas ao `registry`. */
class SendersWiringIT : DatabaseIT() {

    @Autowired
    lateinit var email: EmailSender

    @Autowired
    lateinit var whatsApp: WhatsAppSender

    @Autowired
    lateinit var contacts: PatientContactLookup

    @Autowired
    lateinit var consent: MessagingConsentLookup

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `the memory provider wires the in-memory e-mail sender`() {
        assertThat(email).isInstanceOf(InMemoryEmailSender::class.java)
    }

    @Test
    fun `the retry policy comes from the configuration, with the base delay below the cap`() {
        assertThat(context.getBean(RetryPolicy::class.java)).isEqualTo(RetryPolicy(maxAttempts = 5, baseDelay = Duration.ofMinutes(1), maxDelay = Duration.ofHours(1)))
    }

    @Test
    fun `the scheduled worker does not exist when it is switched off, which is how every test starts`() {
        assertThat(context.containsBean("notificationWorker")).isFalse()
        assertThat(context.containsBean("dispatchPending")).describedAs("o caso de uso existe mesmo sem o agendador").isTrue()
    }

    @Test
    fun `WhatsApp is the fake one and the lookups go through the registry api`() {
        assertThat(whatsApp).isInstanceOf(FakeWhatsAppSender::class.java)
        assertThat(contacts).isInstanceOf(RegistryPatientContactLookup::class.java)
        assertThat(consent).isInstanceOf(RegistryMessagingConsentLookup::class.java)
    }
}
