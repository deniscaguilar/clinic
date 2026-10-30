package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.domain.ImageFormat
import com.endoacademy.clinic.platform.domain.ImageInspection
import com.endoacademy.clinic.platform.domain.ImageInspector
import com.endoacademy.clinic.support.TestImages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ImageInspectorTest {

    private fun recognized(bytes: ByteArray) = (ImageInspector.inspect(bytes) as ImageInspection.Recognized).info

    @Test
    fun `reads format and dimensions from the content of PNG and JPEG`() {
        recognized(TestImages.png(64, 32)).let {
            assertThat(it.format).isEqualTo(ImageFormat.PNG)
            assertThat(it.width).isEqualTo(64)
            assertThat(it.height).isEqualTo(32)
        }
        recognized(TestImages.jpeg(100, 40)).let {
            assertThat(it.format).isEqualTo(ImageFormat.JPEG)
            assertThat(it.width).isEqualTo(100)
            assertThat(it.height).isEqualTo(40)
        }
    }

    @Test
    fun `reads the three WebP flavours`() {
        listOf(TestImages.webpLossy(120, 50), TestImages.webpLossless(120, 50), TestImages.webpExtended(120, 50)).forEach {
            val info = recognized(it)
            assertThat(info.format).isEqualTo(ImageFormat.WEBP)
            assertThat(info.width).isEqualTo(120)
            assertThat(info.height).isEqualTo(50)
        }
        assertThat(recognized(TestImages.webpExtended(2048, 1)).width).isEqualTo(2048)
        assertThat(recognized(TestImages.webpLossless(16383, 16383)).height).isEqualTo(16383)
    }

    @Test
    fun `the format comes from the bytes, mime types and extensions play no part`() {
        assertThat(ImageFormat.PNG.mimeType).isEqualTo("image/png")
        assertThat(ImageFormat.JPEG.extension).isEqualTo("jpg")
        assertThat(ImageFormat.WEBP.mimeType).isEqualTo("image/webp")
    }

    @Test
    fun `SVG, HTML, text and empty content are unsupported`() {
        listOf(TestImages.svg, TestImages.html, "GIF89a".toByteArray(), ByteArray(0), byteArrayOf(1, 2, 3), "<?xml version=\"1.0\"?><svg/>".toByteArray(), "RIFF\u0024\u0000\u0000\u0000WAVEfmt 0000000000000000".toByteArray()).forEach {
            assertThat(ImageInspector.inspect(it)).isEqualTo(ImageInspection.Unsupported)
        }
    }

    @Test
    fun `a known signature with a broken body is malformed, never an exception`() {
        val png = TestImages.png(64, 64)
        val jpeg = TestImages.jpeg(64, 64)
        listOf(
            png.copyOf(12), // corta antes do IHDR
            png.copyOf(8),
            jpeg.copyOf(4), // sem nenhum marcador SOF
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            TestImages.webpLossless(10, 10).copyOf(14),
            "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(),
            png.copyOf().also { it[12] = 'X'.code.toByte() }, // o primeiro bloco não é IHDR
        ).forEach { assertThat(ImageInspector.inspect(it)).describedAs("%d bytes", it.size).isEqualTo(ImageInspection.Malformed) }
    }

    @Test
    fun `zero dimensions are malformed`() {
        val png = TestImages.png(8, 8)
        for (i in 16..19) png[i] = 0 // largura 0
        assertThat(ImageInspector.inspect(png)).isEqualTo(ImageInspection.Malformed)
        assertThat(ImageInspector.inspect(TestImages.webpLossy(0, 10))).isEqualTo(ImageInspection.Malformed)
    }
}
