package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.notifications.adapter.out.email.SesEmailSender
import com.endoacademy.clinic.notifications.application.NotificationSendException
import com.endoacademy.clinic.notifications.application.OutgoingEmail
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.MessageRejectedException
import software.amazon.awssdk.services.ses.model.SendEmailRequest
import software.amazon.awssdk.services.ses.model.SendEmailResponse
import software.amazon.awssdk.services.ses.model.SesException
import java.util.function.Consumer

/** O pedido que o adaptador monta e como cada falha do SES vira um código estável (T5.8). */
class SesEmailSenderTest {

    private val client = mockk<SesClient>()
    private val sender = SesEmailSender(client, "no-reply@clinica.test")
    private val email = OutgoingEmail("maria@example.com", "Confirmação", "Você aceitou 'Mensagens' em 30/10/2026.")

    private fun failWith(exception: Throwable) {
        every { client.sendEmail(any<Consumer<SendEmailRequest.Builder>>()) } throws exception
    }

    private fun sesError(status: Int, code: String? = null, message: String = "falha do provedor para maria@example.com"): SesException =
        SesException.builder().statusCode(status).message(message).apply { if (code != null) awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(message).build()) }.build() as SesException

    private fun assertFailure(code: String, retryable: Boolean) {
        assertThatThrownBy { sender.send(email) }.isInstanceOfSatisfying(NotificationSendException::class.java) {
            assertThat(it.code).isEqualTo(code)
            assertThat(it.retryable).isEqualTo(retryable)
            assertThat(it.message).isEqualTo(code)
            assertThat(it.cause).describedAs("sem causa, que poderia citar o destinatário").isNull()
        }
    }

    @Test
    fun `builds the request with the sender, the single recipient, the subject and the text in UTF-8`() {
        val consumer = slot<Consumer<SendEmailRequest.Builder>>()
        every { client.sendEmail(capture(consumer)) } returns SendEmailResponse.builder().messageId("id").build()
        sender.send(email)

        val request = SendEmailRequest.builder().also { consumer.captured.accept(it) }.build()
        assertThat(request.source()).isEqualTo("no-reply@clinica.test")
        assertThat(request.destination().toAddresses()).containsExactly("maria@example.com")
        assertThat(request.destination().ccAddresses()).isEmpty()
        assertThat(request.destination().bccAddresses()).isEmpty()
        assertThat(request.message().subject().data()).isEqualTo("Confirmação")
        assertThat(request.message().subject().charset()).isEqualTo("UTF-8")
        assertThat(request.message().body().text().data()).isEqualTo("Você aceitou 'Mensagens' em 30/10/2026.")
        assertThat(request.message().body().text().charset()).isEqualTo("UTF-8")
        assertThat(request.message().body().html()).isNull()
    }

    @Test
    fun `a refused message or a client error is rejected for good`() {
        failWith(MessageRejectedException.builder().statusCode(400).message("Email address is not verified: maria@example.com").build())
        assertFailure("EMAIL_REJECTED", retryable = false)
        failWith(sesError(400, "InvalidParameterValue"))
        assertFailure("EMAIL_REJECTED", retryable = false)
        failWith(sesError(403, "AccessDenied"))
        assertFailure("EMAIL_REJECTED", retryable = false)
    }

    @Test
    fun `a server error, a throttling answer or a network failure is worth another attempt`() {
        failWith(sesError(500))
        assertFailure("EMAIL_UNAVAILABLE", retryable = true)
        failWith(sesError(503))
        assertFailure("EMAIL_UNAVAILABLE", retryable = true)
        failWith(sesError(400, "Throttling"))
        assertFailure("EMAIL_UNAVAILABLE", retryable = true)
        failWith(SdkClientException.create("Unable to execute HTTP request: connect to maria@example.com failed"))
        assertFailure("EMAIL_UNAVAILABLE", retryable = true)
    }

    @Test
    fun `nothing the provider says reaches the message of the failure`() {
        failWith(sesError(400, "InvalidParameterValue", "o endereço maria@example.com é inválido"))
        assertThatThrownBy { sender.send(email) }.isInstanceOfSatisfying(NotificationSendException::class.java) {
            assertThat(it.message).doesNotContain("maria").doesNotContain("inválido")
            assertThat(it.toString()).doesNotContain("maria").doesNotContain("example.com")
        }
    }
}
