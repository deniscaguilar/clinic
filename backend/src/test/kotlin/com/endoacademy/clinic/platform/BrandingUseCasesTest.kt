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
import com.endoacademy.clinic.platform.application.BrandingPresenter
import com.endoacademy.clinic.platform.application.BrandingStore
import com.endoacademy.clinic.platform.application.ClinicSummary
import com.endoacademy.clinic.platform.application.GetBranding
import com.endoacademy.clinic.platform.application.PermissionAuthorizer
import com.endoacademy.clinic.platform.application.PermissionCatalog
import com.endoacademy.clinic.platform.application.ProvisionBranding
import com.endoacademy.clinic.platform.application.Transactions
import com.endoacademy.clinic.platform.application.UpdateBranding
import com.endoacademy.clinic.platform.application.UserDirectory
import com.endoacademy.clinic.platform.application.UserProfile
import com.endoacademy.clinic.platform.domain.Branding
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class BrandingUseCasesTest {

    private class FakeStore : BrandingStore {
        var stored: Branding? = null
        var saves = 0
        override fun find() = stored
        override fun save(clinicId: UUID, branding: Branding) {
            stored = branding
            saves++
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
        override fun findClinic(clinicId: UUID) = if (clinicId == this@BrandingUseCasesTest.clinicId) ClinicSummary(clinicId, "Clínica da DCA") else null
        override fun findRoleNames(clinicId: UUID, userId: UUID) = emptyList<String>()
    }
    private val catalog = PermissionCatalog(listOf(object : PermissionContributor {
        override fun permissions() = listOf(PermissionDefinition("MANAGE_BRANDING", "marca"))
    }))
    private val files = InMemoryFileStorage()
    private val presenter = BrandingPresenter(files)
    private val store = FakeStore()
    private val audit = RecordingAudit()
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private val designer = CurrentUser(UUID.randomUUID(), "sub", clinicId, setOf("MANAGE_BRANDING"))
    private val get = GetBranding(store, directory, presenter, transactions)
    private val update = UpdateBranding(PermissionAuthorizer(catalog, audit), store, directory, presenter, audit, transactions)
    private val provision = ProvisionBranding(store, directory, transactions)

    @Test
    fun `without a stored brand the clinic gets the default one named after it`() {
        val view = get.get(clinicId)
        assertThat(view.displayName).isEqualTo("Clínica da DCA")
        assertThat(view.onPrimary).isEqualTo("#FFFFFF")
        assertThat(view.logoUrl).isNull()
        assertThat(store.saves).isZero() // ler não grava
    }

    @Test
    fun `update saves the colors, computes the text color and audits only field names`() {
        val view = update.update(designer, " Clínica Verde ", "#198038", "#6b7280")

        assertThat(view.displayName).isEqualTo("Clínica Verde")
        assertThat(view.primaryColor).isEqualTo("#198038")
        assertThat(view.secondaryColor).isEqualTo("#6B7280")
        assertThat(view.onPrimary).isEqualTo("#FFFFFF")
        assertThat(store.stored!!.primary.value).isEqualTo("#198038")
        assertThat(audit.events.single().action).isEqualTo("UPDATE_BRANDING")
        assertThat(audit.events.single().details).containsExactlyInAnyOrder("displayName", "primaryColor", "secondaryColor")
    }

    @Test
    fun `a primary that is too light is rejected with BRAND_CONTRAST_INSUFFICIENT and nothing is saved`() {
        assertThatThrownBy { update.update(designer, "Clínica", "#FFFF00", "#6B7280") }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.kind).isEqualTo(ErrorKind.UNPROCESSABLE)
                assertThat(it.code).isEqualTo("BRAND_CONTRAST_INSUFFICIENT")
            }
        assertThat(store.saves).isZero()
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `invalid colors and names are 400 with the rule code`() {
        assertThatThrownBy { update.update(designer, "Clínica", "verde", "#6B7280") }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.kind).isEqualTo(ErrorKind.BAD_REQUEST); assertThat(it.code).isEqualTo("COLOR_INVALID") }
        assertThatThrownBy { update.update(designer, " ", "#198038", "#6B7280") }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("DISPLAY_NAME_INVALID") }
    }

    @Test
    fun `update keeps the logo and favicon that are already stored`() {
        files.put("branding/x/logo.png", byteArrayOf(1), "image/png")
        files.put("branding/x/favicon.png", byteArrayOf(1), "image/png")
        store.stored = Branding.default("Clínica").withLogo("branding/x/logo.png").withFavicon("branding/x/favicon.png")

        val view = update.update(designer, "Nova marca", "#198038", "#6B7280")

        assertThat(store.stored!!.logoKey).isEqualTo("branding/x/logo.png")
        assertThat(store.stored!!.faviconKey).isEqualTo("branding/x/favicon.png")
        assertThat(view.logoUrl).startsWith("memory://branding/x/logo.png")
        assertThat(view.faviconUrl).startsWith("memory://branding/x/favicon.png")
    }

    @Test
    fun `without MANAGE_BRANDING the update is refused and audited as DENIED`() {
        assertThatThrownBy { update.update(designer.copy(permissions = emptySet()), "Clínica", "#198038", "#6B7280") }
            .isInstanceOf(PermissionDeniedException::class.java)
        assertThat(store.saves).isZero()
        assertThat(audit.events.single().outcome).isEqualTo(AuditOutcome.DENIED)
    }

    @Test
    fun `provisioning creates the default brand once and never overwrites an existing one`() {
        provision.provision(clinicId)
        assertThat(store.stored!!.displayName).isEqualTo("Clínica da DCA")

        store.stored = Branding.of("Marca própria", "#198038", "#6B7280", null, null)
        provision.provision(clinicId)

        assertThat(store.stored!!.displayName).isEqualTo("Marca própria")
        assertThat(store.saves).isEqualTo(1)
    }
}
