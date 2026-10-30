package com.endoacademy.clinic.platform.adapter.out.storage

import com.endoacademy.clinic.platform.api.FileKeys
import com.endoacademy.clinic.platform.api.FileStorage
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Adaptador em memória para os testes e para quem não precisa de S3. A "URL assinada" é só um texto com a validade. */
class InMemoryFileStorage(private val clock: Clock = Clock.systemUTC()) : FileStorage {

    private class Stored(val content: ByteArray, val contentType: String)

    private val files = ConcurrentHashMap<String, Stored>()

    override fun put(key: String, content: ByteArray, contentType: String) {
        FileKeys.require(key)
        files[key] = Stored(content.copyOf(), contentType)
    }

    override fun signedUrl(key: String, validFor: Duration): String {
        FileKeys.require(key)
        FileKeys.requireValidity(validFor)
        if (!files.containsKey(key)) throw NoSuchElementException("Arquivo inexistente")
        return "memory://$key?expires=${clock.instant().plus(validFor).epochSecond}"
    }

    override fun delete(key: String) {
        FileKeys.require(key)
        files.remove(key)
    }

    fun read(key: String): ByteArray? = files[key]?.content?.copyOf()

    fun contentType(key: String): String? = files[key]?.contentType

    fun keys(): Set<String> = files.keys.toSet()
}
