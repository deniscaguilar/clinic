package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.notifications.adapter.out.email.SesEmailSender
import com.endoacademy.clinic.notifications.application.NotificationSendException
import com.endoacademy.clinic.notifications.application.OutgoingEmail
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** O adaptador SES contra o LocalStack 4.14.0 (ADR-0012): nenhum e-mail real sai (T5.8; CA16 na origem). */
class SesEmailSenderIT {

    companion object {
        private const val FROM = "no-reply@clinica.test"
        private val image = DockerImageName.parse("localstack/localstack:4.14.0@sha256:3ebc37595918b8accb852f8048fef2aff047d465167edd655528065b07bc364a")
            .asCompatibleSubstituteFor("localstack/localstack")
        private val localstack = LocalStackContainer(image).withServices("ses")
        private lateinit var endpoint: String
        private lateinit var sender: SesEmailSender
        private val http = HttpClient.newHttpClient()
        private val json = tools.jackson.databind.json.JsonMapper.builder().build()

        @JvmStatic
        @BeforeAll
        fun start() {
            localstack.start()
            endpoint = localstack.endpoint.toString()
            val admin = SesClient.builder().region(Region.SA_EAST_1).endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build()
            admin.verifyEmailIdentity { it.emailAddress(FROM) }
            sender = SesEmailSender.create(FROM, "sa-east-1", endpoint)
        }

        @JvmStatic
        @AfterAll
        fun stop() = localstack.stop()
    }

    /** O LocalStack guarda o que o SES "enviou" e o expõe em `/_aws/ses`. */
    private fun mailbox(): String =
        http.send(HttpRequest.newBuilder(URI.create("$endpoint/_aws/ses")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()

    @Test
    fun `sends the message through SES with the sender, the recipient, the subject and the text`() {
        val marker = "Confirmação ${System.nanoTime()}"
        sender.send(OutgoingEmail("maria@example.com", marker, "Você aceitou o consentimento 'Mensagens' em 30/10/2026."))

        val message = json.readTree(mailbox()).get("messages").first { it.get("Subject").asString() == marker }
        assertThat(message.get("Source").asString()).isEqualTo(FROM)
        val recipients = message.get("Destination").get("ToAddresses")
        assertThat(recipients.size()).isEqualTo(1)
        assertThat(recipients.get(0).asString()).isEqualTo("maria@example.com")
        assertThat(message.get("Body").get("text_part").asString()).isEqualTo("Você aceitou o consentimento 'Mensagens' em 30/10/2026.")
    }

    @Test
    fun `an unverified sender is rejected with a stable, non retryable code that does not carry the recipient`() {
        val unverified = SesEmailSender.create("quem-quer@naoverificado.test", "sa-east-1", endpoint)
        assertThatThrownBy { unverified.send(OutgoingEmail("maria@example.com", "Assunto", "Corpo")) }
            .isInstanceOfSatisfying(NotificationSendException::class.java) {
                assertThat(it.code).isEqualTo("EMAIL_REJECTED")
                assertThat(it.retryable).isFalse()
                assertThat(it.message).doesNotContain("maria").doesNotContain("example.com").doesNotContain("naoverificado")
                assertThat(it.cause).describedAs("sem causa que traga o endereço").isNull()
            }
    }

    @Test
    fun `the SDK does not retry by itself, so each send is one attempt and the retry policy is ours`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.readAllBytes()
            val body = "<ErrorResponse><Error><Type>Receiver</Type><Code>ServiceUnavailable</Code><Message>indisponível</Message></Error></ErrorResponse>".toByteArray()
            exchange.sendResponseHeaders(503, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val flaky = SesEmailSender.create(FROM, "sa-east-1", "http://localhost:${server.address.port}")
            assertThatThrownBy { flaky.send(OutgoingEmail("maria@example.com", "Assunto", "Corpo")) }
                .isInstanceOfSatisfying(NotificationSendException::class.java) {
                    assertThat(it.code).isEqualTo("EMAIL_UNAVAILABLE")
                    assertThat(it.retryable).isTrue()
                }
            assertThat(requests.get()).describedAs("pedidos recebidos pelo provedor").isEqualTo(1)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an unreachable provider is a retryable failure with a stable code`() {
        val unreachable = SesEmailSender.create(FROM, "sa-east-1", "http://localhost:1")
        assertThatThrownBy { unreachable.send(OutgoingEmail("maria@example.com", "Assunto", "Corpo")) }
            .isInstanceOfSatisfying(NotificationSendException::class.java) {
                assertThat(it.code).isEqualTo("EMAIL_UNAVAILABLE")
                assertThat(it.retryable).isTrue()
                assertThat(it.message).doesNotContain("maria")
                assertThat(it.cause).isNull()
            }
    }
}
