package com.endoacademy.clinic.platform.adapter.`in`.web

import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.application.BrandAssetKind
import com.endoacademy.clinic.platform.application.BrandingView
import com.endoacademy.clinic.platform.application.GetBranding
import com.endoacademy.clinic.platform.application.UpdateBranding
import com.endoacademy.clinic.platform.application.UploadBrandAsset
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** Marca da clínica ativa (`X-Clinic-Id`). Ler: qualquer membro. Gravar: `MANAGE_BRANDING`. */
@RestController
@RequestMapping("/api/v1/branding")
class BrandingController(
    private val getBranding: GetBranding,
    private val updateBranding: UpdateBranding,
    private val uploadBrandAsset: UploadBrandAsset,
) {

    data class BrandingRequest(
        @field:NotBlank @field:Size(max = 120) val displayName: String?,
        @field:NotBlank @field:Size(max = 16) val primaryColor: String?,
        @field:NotBlank @field:Size(max = 16) val secondaryColor: String?,
    )

    data class BrandingResponse(
        val displayName: String,
        val primaryColor: String,
        val secondaryColor: String,
        val onPrimary: String,
        val logoUrl: String?,
        val faviconUrl: String?,
    ) {
        companion object {
            fun of(view: BrandingView) = BrandingResponse(view.displayName, view.primaryColor, view.secondaryColor, view.onPrimary, view.logoUrl, view.faviconUrl)
        }
    }

    private fun user(request: HttpServletRequest) = request.getAttribute(ClinicContextFilter.CURRENT_USER) as CurrentUser

    @GetMapping
    fun get(request: HttpServletRequest): BrandingResponse = BrandingResponse.of(getBranding.get(user(request).clinicId))

    /** O tipo e o nome informados pelo cliente são ignorados: o formato vem do conteúdo. */
    @PutMapping("/logo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun logo(request: HttpServletRequest, @RequestParam("file") file: MultipartFile): BrandingResponse =
        BrandingResponse.of(uploadBrandAsset.upload(user(request), BrandAssetKind.LOGO, file.bytes))

    @PutMapping("/favicon", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun favicon(request: HttpServletRequest, @RequestParam("file") file: MultipartFile): BrandingResponse =
        BrandingResponse.of(uploadBrandAsset.upload(user(request), BrandAssetKind.FAVICON, file.bytes))

    @PutMapping
    fun put(request: HttpServletRequest, @Valid @RequestBody body: BrandingRequest): BrandingResponse =
        BrandingResponse.of(updateBranding.update(user(request), body.displayName.orEmpty(), body.primaryColor.orEmpty(), body.secondaryColor.orEmpty()))
}
