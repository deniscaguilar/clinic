package com.endoacademy.clinic.platform.domain

enum class ImageFormat(val mimeType: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
}

data class ImageInfo(val format: ImageFormat, val width: Int, val height: Int)

sealed interface ImageInspection {
    data class Recognized(val info: ImageInfo) : ImageInspection

    /** Não é PNG, JPEG nem WebP (inclui SVG, GIF, HTML e conteúdo vazio). */
    data object Unsupported : ImageInspection

    /** Tem a assinatura de PNG, JPEG ou WebP, mas o cabeçalho está truncado ou incoerente. */
    data object Malformed : ImageInspection
}

/**
 * Identifica o formato e lê as dimensões **pelo conteúdo**, só dos cabeçalhos, sem decodificar a imagem (nada de bomba
 * de descompressão). Nome de arquivo e tipo MIME informado pelo cliente nunca entram.
 */
object ImageInspector {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun inspect(bytes: ByteArray): ImageInspection {
        val info = when {
            bytes.startsWith(PNG_SIGNATURE) -> png(bytes) ?: return ImageInspection.Malformed
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> jpeg(bytes) ?: return ImageInspection.Malformed
            bytes.size >= 12 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP" -> webp(bytes) ?: return ImageInspection.Malformed
            else -> return ImageInspection.Unsupported
        }
        return if (info.width > 0 && info.height > 0) ImageInspection.Recognized(info) else ImageInspection.Malformed
    }

    private fun png(b: ByteArray): ImageInfo? {
        if (b.size < 24 || b.ascii(12, 4) != "IHDR") return null
        return ImageInfo(ImageFormat.PNG, b.u32be(16), b.u32be(20))
    }

    private fun jpeg(b: ByteArray): ImageInfo? {
        var i = 2
        while (i + 3 < b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { // preenchimento
                i++
                continue
            }
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { // marcadores sem corpo
                i += 2
                continue
            }
            val length = b.u16be(i + 2)
            val isStartOfFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isStartOfFrame) {
                if (i + 9 > b.size) return null
                return ImageInfo(ImageFormat.JPEG, b.u16be(i + 7), b.u16be(i + 5))
            }
            if (length < 2) return null
            i += 2 + length
        }
        return null
    }

    private fun webp(b: ByteArray): ImageInfo? {
        if (b.size < 20) return null
        val data = 20
        return when (b.ascii(12, 4)) {
            "VP8 " -> if (b.size >= data + 10 && b[data + 3] == 0x9D.toByte() && b[data + 4] == 0x01.toByte() && b[data + 5] == 0x2A.toByte()) {
                ImageInfo(ImageFormat.WEBP, b.u16le(data + 6) and 0x3FFF, b.u16le(data + 8) and 0x3FFF)
            } else {
                null
            }
            "VP8L" -> if (b.size >= data + 5 && b[data] == 0x2F.toByte()) {
                val bits = b.u32le(data + 1)
                ImageInfo(ImageFormat.WEBP, (bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1)
            } else {
                null
            }
            "VP8X" -> if (b.size >= data + 10) ImageInfo(ImageFormat.WEBP, b.u24le(data + 4) + 1, b.u24le(data + 7) + 1) else null
            else -> null
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.ascii(offset: Int, length: Int) = String(this, offset, length, Charsets.ISO_8859_1)

    private fun ByteArray.u16be(i: Int) = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)

    private fun ByteArray.u16le(i: Int) = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u24le(i: Int) = u16le(i) or ((this[i + 2].toInt() and 0xFF) shl 16)

    private fun ByteArray.u32le(i: Int) = u24le(i) or ((this[i + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.u32be(i: Int) = (u16be(i) shl 16) or u16be(i + 2)
}
