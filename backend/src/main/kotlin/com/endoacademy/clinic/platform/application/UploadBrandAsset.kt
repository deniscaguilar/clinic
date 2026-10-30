package com.endoacademy.clinic.platform.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.FileStorage
import com.endoacademy.clinic.platform.domain.Branding
import com.endoacademy.clinic.platform.domain.ImageInspection
import com.endoacademy.clinic.platform.domain.ImageInspector
import java.util.UUID

/** Limites de cada arquivo de marca. O favicon é quadrado e pequeno. */
enum class BrandAssetKind(val label: String, val maxBytes: Int, val minSide: Int, val maxSide: Int, val square: Boolean) {
    LOGO("logo", maxBytes = 1024 * 1024, minSide = 16, maxSide = 2048, square = false),
    FAVICON("favicon", maxBytes = 256 * 1024, minSide = 16, maxSide = 512, square = true),
}

/**
 * `UploadBrandAsset` (`MANAGE_BRANDING`): logo ou favicon. Valida **pelo conteúdo** (PNG, JPEG ou WebP; SVG nunca),
 * o tamanho em bytes e as dimensões, guarda no [FileStorage] com chave nova por envio e só então troca a chave na
 * marca. O arquivo antigo é apagado depois; se a gravação da marca falhar, o novo é removido e o antigo permanece.
 */
class UploadBrandAsset(
    private val authorizer: Authorizer,
    private val store: BrandingStore,
    private val directory: UserDirectory,
    private val fileStorage: FileStorage,
    private val presenter: BrandingPresenter,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun upload(user: CurrentUser, kind: BrandAssetKind, content: ByteArray): BrandingView {
        authorizer.require(user, "MANAGE_BRANDING")
        if (content.isEmpty()) throw ApiException(ErrorKind.BAD_REQUEST, "IMAGE_INVALID")
        if (content.size > kind.maxBytes) throw ApiException(ErrorKind.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE")
        val info = when (val inspection = ImageInspector.inspect(content)) {
            is ImageInspection.Recognized -> inspection.info
            ImageInspection.Unsupported -> throw ApiException(ErrorKind.UNSUPPORTED_MEDIA_TYPE, "IMAGE_TYPE_UNSUPPORTED")
            ImageInspection.Malformed -> throw ApiException(ErrorKind.BAD_REQUEST, "IMAGE_INVALID")
        }
        val sides = listOf(info.width, info.height)
        val sizeOk = sides.all { it in kind.minSide..kind.maxSide } && (!kind.square || info.width == info.height)
        if (!sizeOk) throw ApiException(ErrorKind.UNPROCESSABLE, "IMAGE_DIMENSIONS_INVALID")

        val key = "branding/${user.clinicId}/${kind.label}-${newId()}.${info.format.extension}"
        fileStorage.put(key, content, info.format.mimeType)

        val clinicName = directory.findClinic(user.clinicId)?.name.orEmpty()
        var previousKey: String? = null
        val saved = try {
            transactions.inTransaction {
                val current = store.find() ?: Branding.default(clinicName)
                previousKey = if (kind == BrandAssetKind.LOGO) current.logoKey else current.faviconKey
                val updated = if (kind == BrandAssetKind.LOGO) current.withLogo(key) else current.withFavicon(key)
                store.save(user.clinicId, updated)
                auditTrail.record(user, AuditEvent("UPLOAD_BRAND_ASSET", "BRANDING", user.clinicId.toString(), details = setOf(kind.label)))
                updated
            }
        } catch (exception: RuntimeException) {
            runCatching { fileStorage.delete(key) }
            throw exception
        }
        previousKey?.let { runCatching { fileStorage.delete(it) } }
        return presenter.present(saved)
    }
}
