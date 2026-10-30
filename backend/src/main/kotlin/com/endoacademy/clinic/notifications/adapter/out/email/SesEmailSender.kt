package com.endoacademy.clinic.notifications.adapter.out.email

import com.endoacademy.clinic.notifications.application.EmailSender
import com.endoacademy.clinic.notifications.application.NotificationSendException
import com.endoacademy.clinic.notifications.application.OutgoingEmail
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.DefaultRetryStrategy
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.SesException
import java.net.URI
import java.time.Duration

/**
 * Envia e-mail pelo Amazon SES (no LocalStack localmente; ADR-0012). O SDK **não tenta de novo** sozinho: a retentativa
 * é da política do domínio (backoff exponencial, `RetryPolicy`). Todo erro vira um [NotificationSendException] com código
 * estável e **sem causa encadeada**, porque a mensagem do provedor pode citar o destinatário.
 */
class SesEmailSender(private val client: SesClient, private val from: String) : EmailSender {

    override fun send(email: OutgoingEmail) {
        try {
            client.sendEmail { request ->
                request.source(from)
                    .destination { it.toAddresses(email.to) }
                    .message { message ->
                        message.subject { it.data(email.subject).charset("UTF-8") }
                            .body { body -> body.text { it.data(email.body).charset("UTF-8") } }
                    }
            }
        } catch (_: SdkClientException) {
            throw NotificationSendException("EMAIL_UNAVAILABLE", retryable = true)
        } catch (exception: SesException) {
            throw classify(exception)
        } catch (exception: SdkServiceException) {
            throw classify(exception)
        }
    }

    private fun classify(exception: SdkServiceException): NotificationSendException = when {
        exception.statusCode() >= 500 || exception.isThrottlingException -> NotificationSendException("EMAIL_UNAVAILABLE", retryable = true)
        else -> NotificationSendException("EMAIL_REJECTED", retryable = false)
    }

    companion object {
        /** Com `endpoint` (LocalStack), usa credenciais fixas de teste; sem ele, a cadeia padrão de credenciais da AWS. */
        fun create(from: String, region: String, endpoint: String?): SesEmailSender {
            require(from.isNotBlank()) { "clinic.notifications.email.from é obrigatório com o provedor ses" }
            val client = SesClient.builder()
                .region(Region.of(region))
                .overrideConfiguration {
                    it.retryStrategy(DefaultRetryStrategy.doNotRetry())
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))
                        .apiCallTimeout(Duration.ofSeconds(15))
                }
                .apply {
                    if (endpoint != null) {
                        endpointOverride(URI.create(endpoint))
                        credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    }
                }
                .build()
            return SesEmailSender(client, from)
        }
    }
}
