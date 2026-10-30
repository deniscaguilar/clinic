package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.adapter.out.storage.InMemoryFileStorage
import com.endoacademy.clinic.platform.adapter.out.storage.S3FileStorage
import com.endoacademy.clinic.platform.api.FileStorage
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Escolhe o adaptador do `FileStorage` por `clinic.storage.provider` (`memory` ou `s3`). Sem valor a aplicação não sobe
 * (falha fechada), para a produção nunca guardar arquivos em memória por esquecimento.
 */
@Configuration(proxyBeanMethods = false)
class FileStorageConfiguration {

    @Bean
    @ConditionalOnProperty("clinic.storage.provider", havingValue = "memory")
    fun inMemoryFileStorage(clock: Clock): InMemoryFileStorage = InMemoryFileStorage(clock)

    @Bean
    @ConditionalOnProperty("clinic.storage.provider", havingValue = "s3")
    fun s3FileStorage(
        @Value("\${clinic.storage.s3.bucket}") bucket: String,
        @Value("\${clinic.storage.s3.region}") region: String,
        @Value("\${clinic.storage.s3.endpoint}") endpoint: String,
    ): FileStorage {
        require(bucket.isNotBlank()) { "clinic.storage.s3.bucket é obrigatório com o provedor s3" }
        return S3FileStorage.create(bucket, region, endpoint.ifBlank { null })
    }
}
