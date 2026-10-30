package com.endoacademy.clinic.platform.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.FileStorage
import com.endoacademy.clinic.platform.domain.Branding
import com.endoacademy.clinic.platform.domain.BrandingException
import java.time.Duration
import java.util.UUID

/** Marca da clínica ativa. Roda na transação do [Transactions]; a RLS limita ao tenant. */
interface BrandingStore {
    fun find(): Branding?

    /** Cria ou substitui a marca da clínica. */
    fun save(clinicId: UUID, branding: Branding)
}

/** O que a API entrega: cores, texto calculado e URLs assinadas curtas dos arquivos (nunca as chaves). */
data class BrandingView(
    val displayName: String,
    val primaryColor: String,
    val secondaryColor: String,
    val onPrimary: String,
    val logoUrl: String?,
    val faviconUrl: String?,
)

/** Converte a marca em [BrandingView], assinando as URLs dos arquivos. */
class BrandingPresenter(private val fileStorage: FileStorage, private val urlValidity: Duration = Duration.ofMinutes(10)) {
    fun present(branding: Branding) = BrandingView(
        displayName = branding.displayName,
        primaryColor = branding.primary.value,
        secondaryColor = branding.secondary.value,
        onPrimary = branding.onPrimary.value,
        logoUrl = branding.logoKey?.let { fileStorage.signedUrl(it, urlValidity) },
        faviconUrl = branding.faviconKey?.let { fileStorage.signedUrl(it, urlValidity) },
    )
}

/** Erro de regra de marca -> erro da API: contraste é 422, o resto é 400, sempre com o `code` da regra. */
internal fun BrandingException.toApiException(): ApiException =
    ApiException(if (code == "BRAND_CONTRAST_INSUFFICIENT") ErrorKind.UNPROCESSABLE else ErrorKind.BAD_REQUEST, code)

/** Qualquer membro da clínica lê a marca (o app a aplica no topo). Sem marca gravada, devolve a padrão. */
class GetBranding(
    private val store: BrandingStore,
    private val directory: UserDirectory,
    private val presenter: BrandingPresenter,
    private val transactions: Transactions,
) {
    fun get(clinicId: UUID): BrandingView {
        val stored = transactions.inTransaction { store.find() }
        val branding = stored ?: Branding.default(directory.findClinic(clinicId)?.name.orEmpty())
        return presenter.present(branding)
    }
}

/** `UpdateBranding` (`MANAGE_BRANDING`): nome e cores. Os arquivos têm caso de uso próprio e são preservados. */
class UpdateBranding(
    private val authorizer: Authorizer,
    private val store: BrandingStore,
    private val directory: UserDirectory,
    private val presenter: BrandingPresenter,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun update(user: CurrentUser, displayName: String, primaryColor: String, secondaryColor: String): BrandingView {
        authorizer.require(user, "MANAGE_BRANDING")
        val clinicName = directory.findClinic(user.clinicId)?.name.orEmpty()
        val saved = transactions.inTransaction {
            val current = store.find() ?: Branding.default(clinicName)
            val branding = try {
                Branding.of(displayName, primaryColor, secondaryColor, current.logoKey, current.faviconKey)
            } catch (exception: BrandingException) {
                throw exception.toApiException()
            }
            store.save(user.clinicId, branding)
            auditTrail.record(user, AuditEvent("UPDATE_BRANDING", "BRANDING", user.clinicId.toString(), details = setOf("displayName", "primaryColor", "secondaryColor")))
            branding
        }
        return presenter.present(saved)
    }
}

/** Cria a marca padrão de uma clínica nova (listener de `ClinicCreated`). Não sobrescreve uma marca existente. */
class ProvisionBranding(
    private val store: BrandingStore,
    private val directory: UserDirectory,
    private val transactions: Transactions,
) {
    fun provision(clinicId: UUID) {
        val name = directory.findClinic(clinicId)?.name.orEmpty()
        transactions.inTransaction {
            if (store.find() == null) store.save(clinicId, Branding.default(name))
        }
    }
}
