package com.endoacademy.clinic.support

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/** Imagens mínimas para os testes de upload. PNG e JPEG vêm do ImageIO; os WebP são montados byte a byte (o JDK não grava WebP). */
object TestImages {
    private fun encode(width: Int, height: Int, format: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(BufferedImage(width, height, type), format, out))
        return out.toByteArray()
    }

    fun png(width: Int, height: Int): ByteArray = encode(width, height, "png", BufferedImage.TYPE_INT_RGB)

    fun jpeg(width: Int, height: Int): ByteArray = encode(width, height, "jpg", BufferedImage.TYPE_INT_RGB)

    private fun le32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun riff(chunk: ByteArray): ByteArray {
        val body = "WEBP".toByteArray() + chunk
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    private fun chunk(fourCc: String, data: ByteArray): ByteArray = fourCc.toByteArray() + le32(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf()

    /** WebP com perdas (`VP8 `). */
    fun webpLossy(width: Int, height: Int): ByteArray {
        val data = byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A) + byteArrayOf((width and 0xFF).toByte(), (width shr 8).toByte(), (height and 0xFF).toByte(), (height shr 8).toByte())
        return riff(chunk("VP8 ", data))
    }

    /** WebP sem perdas (`VP8L`). */
    fun webpLossless(width: Int, height: Int): ByteArray {
        val bits = ((width - 1) and 0x3FFF) or (((height - 1) and 0x3FFF) shl 14)
        return riff(chunk("VP8L", byteArrayOf(0x2F) + le32(bits)))
    }

    /** WebP estendido (`VP8X`). */
    fun webpExtended(width: Int, height: Int): ByteArray {
        fun u24(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte())
        return riff(chunk("VP8X", byteArrayOf(0, 0, 0, 0) + u24(width - 1) + u24(height - 1)))
    }

    val svg: ByteArray = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><script>alert(1)</script></svg>""".toByteArray()

    val html: ByteArray = "<html><body>oi</body></html>".toByteArray()
}
