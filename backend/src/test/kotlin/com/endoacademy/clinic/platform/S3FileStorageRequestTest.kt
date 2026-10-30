package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.storage.S3FileStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import software.amazon.awssdk.services.s3.presigner.S3Presigner

/**
 * O S3 já criptografa por padrão, então o teste contra o LocalStack não distingue se pedimos a criptografia.
 * Este confere o pedido que sai: SSE-S3 explícito, bucket, chave e tipo do conteúdo.
 */
class S3FileStorageRequestTest {

    @Test
    fun `every put asks for server side encryption with the bucket, key and content type`() {
        val client = mockk<S3Client>()
        val request = slot<PutObjectRequest>()
        every { client.putObject(capture(request), any<RequestBody>()) } returns PutObjectResponse.builder().build()

        S3FileStorage(client, mockk<S3Presigner>(), "clinic-assets").put("branding/x/logo.png", byteArrayOf(1), "image/png")

        assertThat(request.captured.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256)
        assertThat(request.captured.bucket()).isEqualTo("clinic-assets")
        assertThat(request.captured.key()).isEqualTo("branding/x/logo.png")
        assertThat(request.captured.contentType()).isEqualTo("image/png")
    }
}
