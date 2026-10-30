package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.storage.S3FileStorage
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
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** Adaptador S3 contra o LocalStack 4.14.0 (ADR-0012): grava, lê por URL assinada, a URL expira (T3.3). */
class S3FileStorageIT {

    companion object {
        private const val BUCKET = "clinic-assets-test"
        private val image = DockerImageName.parse("localstack/localstack:4.14.0@sha256:3ebc37595918b8accb852f8048fef2aff047d465167edd655528065b07bc364a")
            .asCompatibleSubstituteFor("localstack/localstack")

        private val localstack = LocalStackContainer(image)
            .withServices("s3")
            .withEnv("S3_SKIP_SIGNATURE_VALIDATION", "0") // faz o LocalStack validar assinatura e validade das URLs

        private lateinit var admin: S3Client
        private lateinit var storage: S3FileStorage
        private val http = HttpClient.newHttpClient()

        @JvmStatic
        @BeforeAll
        fun start() {
            localstack.start()
            val endpoint = localstack.endpoint.toString()
            admin = S3Client.builder()
                .region(Region.SA_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()
            admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build())
            storage = S3FileStorage.create(BUCKET, "sa-east-1", endpoint)
        }

        @JvmStatic
        @AfterAll
        fun stop() = localstack.stop()
    }

    private fun get(url: String): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    private fun key() = "branding/${UUID.randomUUID()}/logo.png"

    @Test
    fun `stores the file encrypted and serves it through a signed url`() {
        val key = key()
        val content = byteArrayOf(1, 2, 3, 4, 5)

        storage.put(key, content, "image/png")

        val head = admin.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build())
        assertThat(head.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256)
        assertThat(head.contentType()).isEqualTo("image/png")
        val response = get(storage.signedUrl(key, Duration.ofMinutes(5)))
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo(content)
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying { assertThat(it).startsWith("image/png") }
    }

    @Test
    fun `the signed url stops working after it expires`() {
        val key = key()
        storage.put(key, byteArrayOf(9), "image/png")
        val url = storage.signedUrl(key, Duration.ofSeconds(1))
        assertThat(get(url).statusCode()).isEqualTo(200)

        Thread.sleep(2500)

        assertThat(get(url).statusCode()).isEqualTo(403)
    }

    @Test
    fun `a tampered signed url is refused`() {
        val key = key()
        storage.put(key, byteArrayOf(9), "image/png")
        val other = key()
        storage.put(other, byteArrayOf(8), "image/png")
        val url = storage.signedUrl(key, Duration.ofMinutes(5))

        assertThat(get(url.replace(key, other)).statusCode()).isEqualTo(403)
    }

    @Test
    fun `delete removes the file and is harmless when repeated`() {
        val key = key()
        storage.put(key, byteArrayOf(1), "image/png")

        storage.delete(key)
        storage.delete(key)

        assertThat(get(storage.signedUrl(key, Duration.ofMinutes(1))).statusCode()).isEqualTo(404)
    }

    @Test
    fun `invalid keys and validities never reach the bucket`() {
        assertThatThrownBy { storage.put("../x", byteArrayOf(1), "image/png") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { storage.signedUrl("a/b", Duration.ofHours(1)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { storage.delete("/abs") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
