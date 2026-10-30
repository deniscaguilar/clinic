package com.endoacademy.clinic.notifications

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.endoacademy.clinic.notifications.adapter.out.email.InMemoryEmailSender
import com.endoacademy.clinic.notifications.adapter.out.whatsapp.FakeWhatsAppSender
import com.endoacademy.clinic.notifications.application.NotificationSendException
import com.endoacademy.clinic.notifications.application.OutgoingEmail
import com.endoacademy.clinic.notifications.application.OutgoingWhatsApp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** Os remetentes locais (T5.8): guardam o que foi enviado para os testes, nunca registram o destinatário em log. */
class SendersTest {

    private val appender = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeEach
    fun captureLogs() {
        appender.start()
        root.addAppender(appender)
        root.level = Level.ALL
    }

    @AfterEach
    fun releaseLogs() {
        root.detachAppender(appender)
        appender.stop()
    }

    private fun logged() = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }

    @Test
    fun `the in-memory e-mail sender keeps what was sent, including the recipient, for tests`() {
        val sender = InMemoryEmailSender()
        sender.send(OutgoingEmail("maria@example.com", "Confirmação", "Você aceitou 'Mensagens' em 30/10/2026."))
        sender.send(OutgoingEmail("joao@example.com", "Confirmação", "Texto"))
        assertThat(sender.sent.map { it.to }).containsExactly("maria@example.com", "joao@example.com")
        assertThat(sender.sent.first().subject).isEqualTo("Confirmação")
        sender.clear()
        assertThat(sender.sent).isEmpty()
    }

    @Test
    fun `the in-memory e-mail sender never logs the recipient`() {
        InMemoryEmailSender().send(OutgoingEmail("maria@example.com", "Confirmação", "Texto"))
        assertThat(logged()).doesNotContain("maria@example.com")
    }

    @Test
    fun `an e-mail can be made to fail with a stable code, once or until reset, without losing the state`() {
        val sender = InMemoryEmailSender()
        sender.failWith(NotificationSendException("EMAIL_UNAVAILABLE", retryable = true), times = 2)
        repeat(2) {
            assertThatThrownBy { sender.send(OutgoingEmail("maria@example.com", "A", "B")) }
                .isInstanceOfSatisfying(NotificationSendException::class.java) {
                    assertThat(it.code).isEqualTo("EMAIL_UNAVAILABLE")
                    assertThat(it.retryable).isTrue()
                    assertThat(it.message).doesNotContain("maria")
                }
        }
        assertThat(sender.sent).isEmpty()
        sender.send(OutgoingEmail("maria@example.com", "A", "B"))
        assertThat(sender.sent).hasSize(1)
    }

    @Test
    fun `clearing the senders also forgets a failure that was scheduled`() {
        val email = InMemoryEmailSender()
        email.failWith(NotificationSendException("EMAIL_UNAVAILABLE", retryable = true), times = 5)
        email.clear()
        email.send(OutgoingEmail("maria@example.com", "A", "B"))
        assertThat(email.sent).hasSize(1)

        val whatsApp = FakeWhatsAppSender()
        whatsApp.failWith(NotificationSendException("WHATSAPP_UNAVAILABLE", retryable = true), times = 5)
        whatsApp.clear()
        whatsApp.send(OutgoingWhatsApp("11987654321", "Texto"))
        assertThat(whatsApp.sent).hasSize(1)
    }

    @Test
    fun `the email provider setting accepts only the known values`() {
        assertThat(com.endoacademy.clinic.notifications.config.EmailProvider.parse("memory")).isEqualTo(com.endoacademy.clinic.notifications.config.EmailProvider.MEMORY)
        assertThat(com.endoacademy.clinic.notifications.config.EmailProvider.parse("ses")).isEqualTo(com.endoacademy.clinic.notifications.config.EmailProvider.SES)
        listOf("", " ", "SES", "smtp", "none", "memory ").forEach {
            assertThatThrownBy { com.endoacademy.clinic.notifications.config.EmailProvider.parse(it) }.describedAs("'$it'").isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `the SES sender needs a sender address`() {
        listOf("", "   ").forEach {
            assertThatThrownBy { com.endoacademy.clinic.notifications.adapter.out.email.SesEmailSender.create(it, "sa-east-1", "http://localhost:4566") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `the outgoing messages do not expose the recipient or the text through toString`() {
        assertThat(OutgoingEmail("maria@example.com", "Assunto sigiloso", "Corpo sigiloso").toString()).doesNotContain("maria").doesNotContain("sigiloso")
        assertThat(OutgoingWhatsApp("11987654321", "Corpo sigiloso").toString()).doesNotContain("98765").doesNotContain("sigiloso")
    }

    @Test
    fun `the fake WhatsApp sender records the text but never the recipient`() {
        val sender = FakeWhatsAppSender()
        sender.send(OutgoingWhatsApp("11987654321", "Você aceitou 'Mensagens' em 30/10/2026."))
        sender.send(OutgoingWhatsApp("11912345678", "Outro texto"))
        assertThat(sender.sent.map { it.body }).containsExactly("Você aceitou 'Mensagens' em 30/10/2026.", "Outro texto")
        assertThat(sender.sent.toString()).doesNotContain("98765").doesNotContain("12345678")
        assertThat(logged()).doesNotContain("11987654321").doesNotContain("11912345678")
        sender.clear()
        assertThat(sender.sent).isEmpty()
    }

    @Test
    fun `the fake WhatsApp sender can fail with a stable code too`() {
        val sender = FakeWhatsAppSender()
        sender.failWith(NotificationSendException("WHATSAPP_UNAVAILABLE", retryable = true), times = 1)
        assertThatThrownBy { sender.send(OutgoingWhatsApp("11987654321", "Texto")) }.isInstanceOfSatisfying(NotificationSendException::class.java) {
            assertThat(it.code).isEqualTo("WHATSAPP_UNAVAILABLE")
            assertThat(it.message).doesNotContain("98765")
        }
        assertThat(sender.sent).isEmpty()
        sender.send(OutgoingWhatsApp("11987654321", "Texto"))
        assertThat(sender.sent).hasSize(1)
    }

    @Test
    fun `a send failure carries only a code in its message`() {
        val failure = NotificationSendException("EMAIL_REJECTED", retryable = false)
        assertThat(failure.message).isEqualTo("EMAIL_REJECTED")
        assertThat(failure.retryable).isFalse()
    }
}
