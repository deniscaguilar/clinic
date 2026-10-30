package com.endoacademy.clinic.platform.adapter.out.storage

import com.endoacademy.clinic.platform.api.FileKeys
import com.endoacademy.clinic.platform.api.FileStorage
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.net.URI
import java.time.Duration

/**
 * Adaptador do S3: criptografia no servidor (SSE-S3) em toda gravação e URL assinada de leitura, curta. O bucket é
 * criado pela infraestrutura (OpenTofu em produção, script de init no LocalStack), nunca pela aplicação.
 */
class S3FileStorage(
    private val client: S3Client,
    private val presigner: S3Presigner,
    private val bucket: String,
) : FileStorage {

    override fun put(key: String, content: ByteArray, contentType: String) {
        FileKeys.require(key)
        client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .build(),
            RequestBody.fromBytes(content),
        )
    }

    override fun signedUrl(key: String, validFor: Duration): String {
        FileKeys.require(key)
        FileKeys.requireValidity(validFor)
        val request = GetObjectPresignRequest.builder()
            .signatureDuration(validFor)
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .build()
        return presigner.presignGetObject(request).url().toString()
    }

    override fun delete(key: String) {
        FileKeys.require(key)
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
    }

    companion object {
        /**
         * `endpoint` só para o LocalStack: com ele, usa endereçamento por caminho e credenciais fixas de teste. Sem ele,
         * usa a cadeia padrão de credenciais da AWS (perfil da instância em produção).
         */
        fun create(bucket: String, region: String, endpoint: String?): S3FileStorage {
            val configuration = S3Configuration.builder().pathStyleAccessEnabled(endpoint != null).build()
            val client = S3Client.builder().region(Region.of(region)).serviceConfiguration(configuration).apply {
                if (endpoint != null) {
                    endpointOverride(URI.create(endpoint))
                    credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                }
            }.build()
            val presigner = S3Presigner.builder().region(Region.of(region)).serviceConfiguration(configuration).apply {
                if (endpoint != null) {
                    endpointOverride(URI.create(endpoint))
                    credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                }
            }.build()
            return S3FileStorage(client, presigner, bucket)
        }
    }
}
