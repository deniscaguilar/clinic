package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.storage.InMemoryFileStorage
import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.api.PermissionDefinition
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.platform.application.BrandAssetKind
import com.endoacademy.clinic.platform.application.BrandingPresenter
import com.endoacademy.clinic.platform.application.BrandingStore
import com.endoacademy.clinic.platform.application.ClinicSummary
import com.endoacademy.clinic.platform.application.PermissionAuthorizer
import com.endoacademy.clinic.platform.application.PermissionCatalog
import com.endoacademy.clinic.platform.application.Transactions
import com.endoacademy.clinic.platform.application.UploadBrandAsset
import com.endoacademy.clinic.platform.application.UserDirectory
import com.endoacademy.clinic.platform.application.UserProfile
import com.endoacademy.clinic.platform.domain.Branding
import com.endoacademy.clinic.support.TestImages
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class UploadBrandAssetTest {

    private class FakeStore : BrandingStore {
        var stored: Branding? = null
        var failSave = false
        override fun find() = stored
        override fun save(clinicId: UUID, branding: Branding) {
            if (failSave) error("banco fora")
            stored = branding
        }
    }

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<AuditEvent>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += event
        }
    }

    private val clinicId = UUID.randomUUID()
    private val directory = object : UserDirectory {
        override fun findProfile(sub: String): UserProfile? = null
        override fun listActiveClinics(sub: String) = emptyList<ClinicSummary>()
        override fun findClinic(clinicId: UUID) = ClinicSummary(clinicId, "Clínica Alfa")
        override fun findRoleNames(clinicId: UUID, userId: UUID) = emptyList<String>()
    }
    private val catalog = PermissionCatalog(listOf(object : PermissionContributor {
        override fun permissions() = listOf(PermissionDefinition("MANAGE_BRANDING", "marca"))
    }))
    private val files = InMemoryFileStorage()
    private val store = FakeStore()
    private val audit = RecordingAudit()
    private val designer = CurrentUser(UUID.randomUUID(), "sub", clinicId, setOf("MANAGE_BRANDING"))
    private val upload = UploadBrandAsset(
        PermissionAuthorizer(catalog, audit),
        store,
        directory,
        files,
        BrandingPresenter(files),
        audit,
        object : Transactions {
            override fun <T> inTransaction(block: () -> T): T = block()
        },
    )

    private fun api(block: () -> Unit) = assertThatThrownBy(block).isInstanceOf(ApiException::class.java).extracting { it as ApiException }

    @Test
    fun `a valid logo is stored under the clinic prefix with the type detected from the bytes`() {
        val view = upload.upload(designer, BrandAssetKind.LOGO, TestImages.png(200, 80))

        val key = store.stored!!.logoKey!!
        assertThat(key).matches("branding/$clinicId/logo-[0-9a-f-]{36}\\.png")
        assertThat(files.contentType(key)).isEqualTo("image/png")
        assertThat(view.logoUrl).contains(key)
        assertThat(audit.events.single().action).isEqualTo("UPLOAD_BRAND_ASSET")
        assertThat(audit.events.single().details).containsExactly("logo")
    }

    @Test
    fun `JPEG and the three WebP flavours are accepted and keep their own extension`() {
        assertThat(upload.upload(designer, BrandAssetKind.LOGO, TestImages.jpeg(100, 100)).logoUrl).contains(".jpg")
        assertThat(upload.upload(designer, BrandAssetKind.LOGO, TestImages.webpLossy(100, 100)).logoUrl).contains(".webp")
        assertThat(upload.upload(designer, BrandAssetKind.LOGO, TestImages.webpLossless(100, 100)).logoUrl).contains(".webp")
        assertThat(upload.upload(designer, BrandAssetKind.LOGO, TestImages.webpExtended(100, 100)).logoUrl).contains(".webp")
    }

    @Test
    fun `SVG, HTML and anything that is not PNG, JPEG or WebP is refused with 415 and nothing is stored`() {
        listOf(TestImages.svg, TestImages.html, "GIF89a....".toByteArray()).forEach {
            api { upload.upload(designer, BrandAssetKind.LOGO, it) }.satisfies({
                assertThat(it.kind).isEqualTo(ErrorKind.UNSUPPORTED_MEDIA_TYPE)
                assertThat(it.code).isEqualTo("IMAGE_TYPE_UNSUPPORTED")
            })
        }
        assertThat(files.keys()).isEmpty()
        assertThat(store.stored).isNull()
    }

    @Test
    fun `a truncated image or empty content is a 400`() {
        api { upload.upload(designer, BrandAssetKind.LOGO, TestImages.png(64, 64).copyOf(12)) }.satisfies({ assertThat(it.code).isEqualTo("IMAGE_INVALID") })
        api { upload.upload(designer, BrandAssetKind.LOGO, ByteArray(0)) }.satisfies({ assertThat(it.kind).isEqualTo(ErrorKind.BAD_REQUEST) })
        assertThat(files.keys()).isEmpty()
    }

    @Test
    fun `files above the limit are refused with 413 before any parsing`() {
        val big = TestImages.png(64, 64) + ByteArray(BrandAssetKind.LOGO.maxBytes)
        api { upload.upload(designer, BrandAssetKind.LOGO, big) }.satisfies({
            assertThat(it.kind).isEqualTo(ErrorKind.PAYLOAD_TOO_LARGE)
            assertThat(it.code).isEqualTo("FILE_TOO_LARGE")
        })
        val bigFavicon = TestImages.png(32, 32) + ByteArray(BrandAssetKind.FAVICON.maxBytes)
        api { upload.upload(designer, BrandAssetKind.FAVICON, bigFavicon) }.satisfies({ assertThat(it.code).isEqualTo("FILE_TOO_LARGE") })
        assertThat(files.keys()).isEmpty()
    }

    @Test
    fun `dimensions outside the limits are a 422`() {
        listOf(TestImages.png(8, 8), TestImages.png(4000, 100), TestImages.png(100, 4000)).forEach {
            api { upload.upload(designer, BrandAssetKind.LOGO, it) }.satisfies({
                assertThat(it.kind).isEqualTo(ErrorKind.UNPROCESSABLE)
                assertThat(it.code).isEqualTo("IMAGE_DIMENSIONS_INVALID")
            })
        }
        upload.upload(designer, BrandAssetKind.LOGO, TestImages.png(16, 16))
        upload.upload(designer, BrandAssetKind.LOGO, TestImages.png(2048, 100))
    }

    @Test
    fun `the favicon must be square and small`() {
        upload.upload(designer, BrandAssetKind.FAVICON, TestImages.png(64, 64))
        api { upload.upload(designer, BrandAssetKind.FAVICON, TestImages.png(64, 32)) }.satisfies({ assertThat(it.code).isEqualTo("IMAGE_DIMENSIONS_INVALID") })
        api { upload.upload(designer, BrandAssetKind.FAVICON, TestImages.png(600, 600)) }.satisfies({ assertThat(it.code).isEqualTo("IMAGE_DIMENSIONS_INVALID") })
        assertThat(store.stored!!.faviconKey).isNotNull()
        assertThat(store.stored!!.logoKey).isNull()
    }

    @Test
    fun `a new file replaces the old one and the old object is deleted`() {
        upload.upload(designer, BrandAssetKind.LOGO, TestImages.png(100, 100))
        val first = store.stored!!.logoKey!!

        upload.upload(designer, BrandAssetKind.LOGO, TestImages.jpeg(100, 100))

        assertThat(store.stored!!.logoKey).isNotEqualTo(first)
        assertThat(files.read(first)).isNull()
        assertThat(files.keys()).hasSize(1)
    }

    @Test
    fun `logo and favicon are independent`() {
        upload.upload(designer, BrandAssetKind.LOGO, TestImages.png(100, 100))
        upload.upload(designer, BrandAssetKind.FAVICON, TestImages.png(32, 32))
        assertThat(store.stored!!.logoKey).isNotNull()
        assertThat(store.stored!!.faviconKey).isNotNull()
        assertThat(files.keys()).hasSize(2)
    }

    @Test
    fun `if saving fails the uploaded file is removed and the old one stays`() {
        upload.upload(designer, BrandAssetKind.LOGO, TestImages.png(100, 100))
        val first = store.stored!!.logoKey!!
        store.failSave = true

        assertThatThrownBy { upload.upload(designer, BrandAssetKind.LOGO, TestImages.png(120, 120)) }.isInstanceOf(IllegalStateException::class.java)

        assertThat(files.keys()).containsExactly(first)
        assertThat(store.stored!!.logoKey).isEqualTo(first)
    }

    @Test
    fun `without MANAGE_BRANDING the upload is refused, audited as DENIED and nothing is stored`() {
        assertThatThrownBy { upload.upload(designer.copy(permissions = emptySet()), BrandAssetKind.LOGO, TestImages.png(100, 100)) }
            .isInstanceOf(PermissionDeniedException::class.java)
        assertThat(files.keys()).isEmpty()
        assertThat(audit.events.single().outcome).isEqualTo(AuditOutcome.DENIED)
    }
}
