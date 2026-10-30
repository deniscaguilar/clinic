package com.endoacademy.clinic.platform.api

import java.time.Duration

/**
 * Armazenamento de arquivos (port; plano, seção 4). S3 com criptografia no servidor em produção e no LocalStack;
 * em memória nos testes que não precisam de S3. Arquivos nunca são servidos pela aplicação: o cliente recebe uma URL
 * assinada de leitura, curta.
 */
interface FileStorage {
    /** Grava (ou substitui) o conteúdo em `key`. */
    fun put(key: String, content: ByteArray, contentType: String)

    /** URL de leitura válida por `validFor` (de 1 segundo a [FileKeys.MAX_URL_VALIDITY]). */
    fun signedUrl(key: String, validFor: Duration): String

    /** Apaga `key`; não falha se ela não existe. */
    fun delete(key: String)
}

object FileKeys {
    val MAX_URL_VALIDITY: Duration = Duration.ofMinutes(15)
    private const val MAX_LENGTH = 512
    private val SEGMENT = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")

    /** Chave só com segmentos simples separados por `/`: sem `..`, sem barra no início ou duplicada, sem caracteres especiais. */
    fun require(key: String) {
        require(key.isNotEmpty() && key.length <= MAX_LENGTH) { "Chave de arquivo inválida" }
        key.split('/').forEach { segment ->
            require(SEGMENT.matches(segment) && segment != "..") { "Chave de arquivo inválida" }
        }
    }

    fun requireValidity(validFor: Duration) {
        require(!validFor.isNegative && validFor >= Duration.ofSeconds(1) && validFor <= MAX_URL_VALIDITY) {
            "A validade da URL assinada vai de 1 segundo a ${MAX_URL_VALIDITY.toMinutes()} minutos"
        }
    }
}
