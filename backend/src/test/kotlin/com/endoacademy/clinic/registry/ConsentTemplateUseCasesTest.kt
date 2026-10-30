package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.platform.api.PlatformAdminAccess
import com.endoacademy.clinic.registry.application.ConsentTemplateData
import com.endoacademy.clinic.registry.application.ConsentTemplateStore
import com.endoacademy.clinic.registry.application.CreateConsentTemplate
import com.endoacademy.clinic.registry.application.GetConsentTemplate
import com.endoacademy.clinic.registry.application.ListConsentTemplates
import com.endoacademy.clinic.registry.application.Transactions
import com.endoacademy.clinic.registry.application.UpdateConsentTemplate
import com.endoacademy.clinic.registry.domain.ConsentTemplate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ConsentTemplateUseCasesTest {

    private class FakeTemplateStore : ConsentTemplateStore {
        val rows = linkedMapOf<UUID, ConsentTemplate>()
        override fun list(active: Boolean?) = rows.values.filter { active == null || it.active == active }.sortedBy { it.name.lowercase() }
        override fun find(id: UUID) = rows[id]
        override fun insert(template: ConsentTemplate) {
            rows[template.id] = template
        }

        override fun update(template: ConsentTemplate) {
            rows[template.id] = template
        }

        override fun nameTaken(name: String, exceptId: UUID?) = rows.values.any { it.name.equals(name, ignoreCase = true) && it.id != exceptId }
    }

    private data class PlatformEvent(val actor: UUID?, val tenant: UUID?, val event: AuditEvent)

    private class RecordingAudit : AuditTrail {
        val platformEvents = mutableListOf<PlatformEvent>()
        override fun record(user: CurrentUser?, event: AuditEvent) = error("ação de PLATFORM_ADMIN não pode gravar como usuário de clínica")
        override fun recordPlatform(actorUserId: UUID?, tenantId: UUID?, event: AuditEvent) {
            platformEvents += PlatformEvent(actorUserId, tenantId, event)
        }
    }

    private val adminId = UUID.randomUUID()
    private val store = FakeTemplateStore()
    private val audit = RecordingAudit()
    private val access = object : PlatformAdminAccess {
        override fun require(actorSub: String): UUID = if (actorSub == "admin-sub") adminId else throw PermissionDeniedException("MANAGE_CLINICS")
    }
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private var nextId = 0
    private val create = CreateConsentTemplate(access, store, audit, transactions) { UUID(0, (++nextId).toLong()) }
    private val update = UpdateConsentTemplate(access, store, audit, transactions)
    private val list = ListConsentTemplates(access, store, transactions)
    private val get = GetConsentTemplate(access, store, transactions)

    private fun data(name: String = "Mensagens", text: String = "Autorizo.", required: Boolean = true, messaging: Boolean = true, active: Boolean = true) =
        ConsentTemplateData(name, text, required, messaging, active)

    private fun assertApi(kind: ErrorKind, code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(kind)
            assertThat(it.code).isEqualTo(code)
        }
    }

    @Test
    fun `only a PLATFORM_ADMIN reaches any template operation, and nothing changes otherwise`() {
        val existing = create.create("admin-sub", data())
        audit.platformEvents.clear()
        assertThatThrownBy { create.create("clinic-user-sub", data(name = "Outro")) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { update.update("clinic-user-sub", existing.id, data(name = "Invadido")) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { list.list("clinic-user-sub", null) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { get.get("clinic-user-sub", existing.id) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThat(store.rows).hasSize(1)
        assertThat(store.rows.getValue(existing.id).name).isEqualTo("Mensagens")
        assertThat(audit.platformEvents).isEmpty()
    }

    @Test
    fun `creating stores an active template and audits it as a platform action with no clinic and no text`() {
        val created = create.create("admin-sub", data(text = "Texto sigiloso do modelo"))
        assertThat(created.active).isTrue()
        assertThat(store.rows).containsKey(created.id)
        assertThat(audit.platformEvents).singleElement().satisfies({
            assertThat(it.actor).isEqualTo(adminId)
            assertThat(it.tenant).isNull()
            assertThat(it.event.action).isEqualTo("CREATE_CONSENT_TEMPLATE")
            assertThat(it.event.entityType).isEqualTo("CONSENT_TEMPLATE")
            assertThat(it.event.entityId).isEqualTo(created.id.toString())
            assertThat(it.event.details).containsExactlyInAnyOrder("name", "text", "requiredOnSignup", "controlsMessaging")
            assertThat(it.event.toString()).doesNotContain("sigiloso")
        })
    }

    @Test
    fun `the template name is unique ignoring case and violations are 422`() {
        create.create("admin-sub", data(name = "Mensagens"))
        audit.platformEvents.clear()
        assertApi(ErrorKind.CONFLICT, "CONSENT_TEMPLATE_NAME_TAKEN") { create.create("admin-sub", data(name = " MENSAGENS ")) }
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_NAME_REQUIRED") { create.create("admin-sub", data(name = " ")) }
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_TEXT_REQUIRED") { create.create("admin-sub", data(text = " ")) }
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_TEXT_TOO_LONG") { create.create("admin-sub", data(text = "x".repeat(20_001))) }
        assertThat(store.rows).hasSize(1)
        assertThat(audit.platformEvents).isEmpty()
    }

    @Test
    fun `updating changes the fields, may inactivate, keeps its own name and refuses another's`() {
        val first = create.create("admin-sub", data(name = "Imagem"))
        val second = create.create("admin-sub", data(name = "Mensagens"))
        audit.platformEvents.clear()
        val updated = update.update("admin-sub", first.id, data(name = "IMAGEM", text = "Novo texto", required = false, messaging = false, active = false))
        assertThat(updated.name).isEqualTo("IMAGEM")
        assertThat(updated.text).isEqualTo("Novo texto")
        assertThat(updated.requiredOnSignup).isFalse()
        assertThat(updated.active).isFalse()
        assertThat(store.rows.getValue(first.id)).isEqualTo(updated)
        assertThat(audit.platformEvents).singleElement().satisfies({
            assertThat(it.event.action).isEqualTo("UPDATE_CONSENT_TEMPLATE")
            assertThat(it.event.entityId).isEqualTo(first.id.toString())
            assertThat(it.actor).isEqualTo(adminId)
            assertThat(it.tenant).isNull()
        })
        audit.platformEvents.clear()
        assertApi(ErrorKind.CONFLICT, "CONSENT_TEMPLATE_NAME_TAKEN") { update.update("admin-sub", second.id, data(name = "imagem")) }
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_NAME_REQUIRED") { update.update("admin-sub", second.id, data(name = " ")) }
        assertThat(store.rows.getValue(second.id).name).isEqualTo("Mensagens")
        assertThat(audit.platformEvents).isEmpty()
    }

    @Test
    fun `an unknown template answers 404`() {
        val unknown = UUID.randomUUID()
        assertApi(ErrorKind.NOT_FOUND, "CONSENT_TEMPLATE_NOT_FOUND") { get.get("admin-sub", unknown) }
        assertApi(ErrorKind.NOT_FOUND, "CONSENT_TEMPLATE_NOT_FOUND") { update.update("admin-sub", unknown, data()) }
    }

    @Test
    fun `listing filters by active and orders by name`() {
        val b = create.create("admin-sub", data(name = "b Imagem"))
        create.create("admin-sub", data(name = "A Mensagens"))
        update.update("admin-sub", b.id, data(name = "b Imagem", active = false))
        assertThat(list.list("admin-sub", null).map { it.name }).containsExactly("A Mensagens", "b Imagem")
        assertThat(list.list("admin-sub", true).map { it.name }).containsExactly("A Mensagens")
        assertThat(list.list("admin-sub", false).map { it.name }).containsExactly("b Imagem")
        assertThat(get.get("admin-sub", b.id).name).isEqualTo("b Imagem")
    }
}
