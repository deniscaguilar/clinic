package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.storage.InMemoryFileStorage
import com.endoacademy.clinic.platform.api.FileKeys
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class FileStorageTest {

    private val now = Instant.parse("2026-10-29T12:00:00Z")
    private val storage = InMemoryFileStorage(Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `valid keys are accepted`() {
        listOf("branding/3f2a/logo-1.png", "a", "a/b_c-d.e/f").forEach { FileKeys.require(it) }
    }

    @Test
    fun `keys that could escape the prefix or hide the content are rejected`() {
        listOf("", "/abs", "a//b", "../x", "a/../b", "a/./b", "a\\b", "a b", "a?b", "a#b", "ação.png", "x".repeat(513), "a/", ".hidden").forEach {
            assertThatThrownBy { FileKeys.require(it) }.describedAs(it).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `stores the content with its type and returns it`() {
        storage.put("branding/x/logo.png", byteArrayOf(1, 2, 3), "image/png")
        assertThat(storage.read("branding/x/logo.png")).containsExactly(1, 2, 3)
        assertThat(storage.contentType("branding/x/logo.png")).isEqualTo("image/png")
        assertThat(storage.read("branding/x/outro.png")).isNull()
    }

    @Test
    fun `delete removes the content and tolerates a missing key`() {
        storage.put("a/b", byteArrayOf(1), "image/png")
        storage.delete("a/b")
        storage.delete("a/b")
        assertThat(storage.read("a/b")).isNull()
    }

    @Test
    fun `signed urls carry the expiry and are short lived`() {
        storage.put("a/b", byteArrayOf(1), "image/png")
        val url = storage.signedUrl("a/b", Duration.ofMinutes(5))
        assertThat(url).contains("a/b").contains("expires=${now.plusSeconds(300).epochSecond}")

        assertThatThrownBy { storage.signedUrl("a/b", Duration.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { storage.signedUrl("a/b", Duration.ofMinutes(16)) }.isInstanceOf(IllegalArgumentException::class.java)
        storage.signedUrl("a/b", Duration.ofMinutes(15))
    }

    @Test
    fun `a signed url for a missing key is refused`() {
        assertThatThrownBy { storage.signedUrl("nada/aqui", Duration.ofMinutes(1)) }.isInstanceOf(NoSuchElementException::class.java)
    }
}
