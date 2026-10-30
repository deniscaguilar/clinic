package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.registry.application.ConsentTemplateStore
import com.endoacademy.clinic.registry.application.ConsentTypeChange
import com.endoacademy.clinic.registry.application.ConsentTypeData
import com.endoacademy.clinic.registry.application.ConsentTypeStore
import com.endoacademy.clinic.registry.application.CreateConsentType
import com.endoacademy.clinic.registry.application.GetConsentType
import com.endoacademy.clinic.registry.application.ImportConsentTemplate
import com.endoacademy.clinic.registry.application.ListAvailableConsentTemplates
import com.endoacademy.clinic.registry.application.ListConsentTypes
import com.endoacademy.clinic.registry.application.ReviseConsentType
import com.endoacademy.clinic.registry.application.Transactions
import com.endoacademy.clinic.registry.domain.ConsentTemplate
import com.endoacademy.clinic.registry.domain.ConsentType
import com.endoacademy.clinic.registry.domain.ConsentVersion
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ConsentTypeUseCasesTest {

    private class FakeTypeStore : ConsentTypeStore {
        val rows = linkedMapOf<UUID, ConsentType>()
        override fun list(active: Boolean?) = rows.values.filter { active == null || it.active == active }.sortedBy { it.name.lowercase() }
        override fun find(id: UUID) = rows[id]
        override fun insert(clinicId: UUID, type: ConsentType) {
            rows[type.id] = type
        }

        /** Como o banco: só nome e opções; as versões entram por [addVersion]. */
        override fun update(type: ConsentType) {
            rows[type.id] = rows.getValue(type.id).update(type.name, type.requiredOnSignup, type.controlsMessaging, type.active)
        }

        override fun addVersion(typeId: UUID, version: ConsentVersion) {
            val current = rows.getValue(typeId)
            check(version.number == current.current.number + 1) { "a próxima versão é ${current.current.number + 1}" }
            rows[typeId] = ConsentType.restore(current.id, current.name, current.requiredOnSignup, current.controlsMessaging, current.active, current.versions + version)
        }

        override fun nameTaken(name: String, exceptId: UUID?) = rows.values.any { it.name.equals(name, ignoreCase = true) && it.id != exceptId }
    }

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

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<AuditEvent>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += event
        }
    }

    private val clinic = UUID.randomUUID()
    private val manager = CurrentUser(UUID.randomUUID(), "sub-1", clinic, setOf("MANAGE_CONSENT_TYPES"))
    private val registrar = CurrentUser(UUID.randomUUID(), "sub-2", clinic, setOf("MANAGE_PATIENTS"))
    private val outsider = CurrentUser(UUID.randomUUID(), "sub-3", clinic, setOf("MANAGE_PROCEDURES"))
    private val store = FakeTypeStore()
    private val templates = FakeTemplateStore()
    private val audit = RecordingAudit()
    private val authorizer = object : Authorizer {
        override fun require(user: CurrentUser, permission: String) {
            if (!user.has(permission)) throw PermissionDeniedException(permission)
        }
    }
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private var now = Instant.parse("2026-10-30T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant(): Instant = now
    }
    private var nextId = 0
    private fun id() = UUID(0, (++nextId).toLong())
    private val create = CreateConsentType(authorizer, store, audit, transactions, clock) { id() }
    private val revise = ReviseConsentType(authorizer, store, audit, transactions, clock)
    private val get = GetConsentType(authorizer, store, transactions)
    private val list = ListConsentTypes(authorizer, store, transactions)
    private val import = ImportConsentTemplate(authorizer, store, templates, audit, transactions, clock) { id() }
    private val available = ListAvailableConsentTemplates(authorizer, templates, transactions)

    private fun data(name: String = "Cadastro e mensagens", text: String = "Autorizo.", required: Boolean = true, messaging: Boolean = false) =
        ConsentTypeData(name, text, required, messaging)

    private fun change(
        name: String = "Cadastro e mensagens",
        text: String? = null,
        required: Boolean = true,
        messaging: Boolean = false,
        active: Boolean = true,
    ) = ConsentTypeChange(name, text, required, messaging, active)

    private fun assertApi(kind: ErrorKind, code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(kind)
            assertThat(it.code).isEqualTo(code)
        }
    }

    // --- criar ---

    @Test
    fun `creating stores the type with version 1 at the current time and audits field names only`() {
        val created = create.create(manager, data(text = "Texto sigiloso do consentimento"))
        assertThat(store.rows).containsKey(created.id)
        assertThat(created.versions).containsExactly(ConsentVersion(1, "Texto sigiloso do consentimento", now))
        assertThat(created.active).isTrue()
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("CREATE_CONSENT_TYPE")
            assertThat(it.entityType).isEqualTo("CONSENT_TYPE")
            assertThat(it.entityId).isEqualTo(created.id.toString())
            assertThat(it.details).containsExactlyInAnyOrder("name", "text", "requiredOnSignup", "controlsMessaging")
            assertThat(it.toString()).doesNotContain("sigiloso")
        })
    }

    @Test
    fun `only MANAGE_CONSENT_TYPES changes types, and nothing is stored without it`() {
        assertThatThrownBy { create.create(registrar, data()) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { create.create(outsider, data()) }.isInstanceOf(PermissionDeniedException::class.java)
        val existing = create.create(manager, data())
        audit.events.clear()
        assertThatThrownBy { revise.revise(registrar, existing.id, change(text = "Novo")) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { import.import(registrar, UUID.randomUUID()) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThat(store.rows).hasSize(1)
        assertThat(store.rows.getValue(existing.id).versions).hasSize(1)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `the name is unique per clinic ignoring case and only one active type controls messaging`() {
        create.create(manager, data(name = "Mensagens", messaging = true))
        audit.events.clear()
        assertApi(ErrorKind.CONFLICT, "CONSENT_TYPE_NAME_TAKEN") { create.create(manager, data(name = "  MENSAGENS ")) }
        assertApi(ErrorKind.CONFLICT, "CONSENT_MESSAGING_TYPE_EXISTS") { create.create(manager, data(name = "Outras mensagens", messaging = true)) }
        create.create(manager, data(name = "Imagem", messaging = false))
        assertThat(store.rows).hasSize(2)
        assertThat(audit.events).hasSize(1)
    }

    @Test
    fun `domain violations become 422 with the stable code`() {
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_NAME_REQUIRED") { create.create(manager, data(name = " ")) }
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_NAME_TOO_LONG") { create.create(manager, data(name = "x".repeat(121))) }
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_TEXT_REQUIRED") { create.create(manager, data(text = " ")) }
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_TEXT_TOO_LONG") { create.create(manager, data(text = "x".repeat(20_001))) }
        assertThat(store.rows).isEmpty()
        assertThat(audit.events).isEmpty()
    }

    // --- revisar (PUT) ---

    @Test
    fun `a new text creates the next version and the previous versions stay as they were (CA15)`() {
        val created = create.create(manager, data(text = "Texto original"))
        audit.events.clear()
        now = Instant.parse("2026-10-30T11:00:00Z")
        val revised = revise.revise(manager, created.id, change(text = "Texto novo"))
        assertThat(revised.versions.map { it.number }).containsExactly(1, 2)
        assertThat(revised.versions[0]).isEqualTo(created.versions[0])
        assertThat(revised.current).isEqualTo(ConsentVersion(2, "Texto novo", now))
        assertThat(store.rows.getValue(created.id)).isEqualTo(revised)
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("REVISE_CONSENT_TYPE")
            assertThat(it.entityId).isEqualTo(created.id.toString())
            assertThat(it.details).containsExactly("text")
        })
    }

    @Test
    fun `the same text, or no text, creates no version`() {
        val created = create.create(manager, data(text = "Texto original"))
        audit.events.clear()
        assertThat(revise.revise(manager, created.id, change(text = "  Texto original ")).versions).hasSize(1)
        assertThat(revise.revise(manager, created.id, change(text = null)).versions).hasSize(1)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `changing only the settings audits the names of what changed`() {
        val created = create.create(manager, data(name = "Imagem", required = true, messaging = false))
        audit.events.clear()
        val updated = revise.revise(manager, created.id, change(name = "Uso de imagem", required = false, active = false))
        assertThat(updated.name).isEqualTo("Uso de imagem")
        assertThat(updated.requiredOnSignup).isFalse()
        assertThat(updated.active).isFalse()
        assertThat(updated.versions).isEqualTo(created.versions)
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("UPDATE_CONSENT_TYPE")
            assertThat(it.details).containsExactlyInAnyOrder("name", "requiredOnSignup", "active")
        })
    }

    @Test
    fun `a text and settings together audit both events`() {
        val created = create.create(manager, data(name = "Imagem"))
        audit.events.clear()
        revise.revise(manager, created.id, change(name = "Imagem 2", text = "Outro texto"))
        assertThat(audit.events.map { it.action }).containsExactly("REVISE_CONSENT_TYPE", "UPDATE_CONSENT_TYPE")
    }

    @Test
    fun `a revision may keep its own name but not take another type's name`() {
        val first = create.create(manager, data(name = "Imagem"))
        val second = create.create(manager, data(name = "Mensagens"))
        assertThat(revise.revise(manager, first.id, change(name = "IMAGEM", text = "Novo texto")).name).isEqualTo("IMAGEM")
        audit.events.clear()
        assertApi(ErrorKind.CONFLICT, "CONSENT_TYPE_NAME_TAKEN") { revise.revise(manager, second.id, change(name = "imagem")) }
        assertThat(store.rows.getValue(second.id).name).isEqualTo("Mensagens")
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `turning messaging control on is refused while another active type has it, and allowed once that one is inactive`() {
        val first = create.create(manager, data(name = "Mensagens", messaging = true))
        val second = create.create(manager, data(name = "Imagem", messaging = false))
        assertApi(ErrorKind.CONFLICT, "CONSENT_MESSAGING_TYPE_EXISTS") { revise.revise(manager, second.id, change(name = "Imagem", messaging = true)) }
        assertThat(store.rows.getValue(second.id).controlsMessaging).isFalse()
        revise.revise(manager, first.id, change(name = "Mensagens", messaging = true, active = false))
        assertThat(revise.revise(manager, second.id, change(name = "Imagem", messaging = true)).controlsMessaging).isTrue()
        // reativar o antigo agora conflita
        assertApi(ErrorKind.CONFLICT, "CONSENT_MESSAGING_TYPE_EXISTS") { revise.revise(manager, first.id, change(name = "Mensagens", messaging = true, active = true)) }
    }

    @Test
    fun `an invalid revision keeps the stored type untouched`() {
        val created = create.create(manager, data())
        audit.events.clear()
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_NAME_REQUIRED") { revise.revise(manager, created.id, change(name = " ", text = "Novo")) }
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_TEXT_REQUIRED") { revise.revise(manager, created.id, change(text = "   ")) }
        assertApi(ErrorKind.UNPROCESSABLE, "CONSENT_TEXT_TOO_LONG") { revise.revise(manager, created.id, change(text = "x".repeat(20_001))) }
        assertThat(store.rows.getValue(created.id)).isEqualTo(created)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `an unknown type answers 404`() {
        val unknown = UUID.randomUUID()
        assertApi(ErrorKind.NOT_FOUND, "CONSENT_TYPE_NOT_FOUND") { revise.revise(manager, unknown, change()) }
        assertApi(ErrorKind.NOT_FOUND, "CONSENT_TYPE_NOT_FOUND") { get.get(manager, unknown) }
    }

    // --- ler ---

    @Test
    fun `types are listed and read by whoever manages types or registers patients, and by nobody else`() {
        val a = create.create(manager, data(name = "b Imagem"))
        create.create(manager, data(name = "A Mensagens"))
        revise.revise(manager, a.id, change(name = "b Imagem", active = false))
        listOf(manager, registrar).forEach { user ->
            assertThat(list.list(user, null).map { it.name }).containsExactly("A Mensagens", "b Imagem")
            assertThat(list.list(user, true).map { it.name }).containsExactly("A Mensagens")
            assertThat(list.list(user, false).map { it.name }).containsExactly("b Imagem")
            assertThat(get.get(user, a.id).versions).hasSize(1)
        }
        assertThatThrownBy { list.list(outsider, null) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { get.get(outsider, a.id) }.isInstanceOf(PermissionDeniedException::class.java)
    }

    // --- importar modelo ---

    private fun template(name: String = "Mensagens", text: String = "Autorizo mensagens.", messaging: Boolean = true, active: Boolean = true): ConsentTemplate {
        val template = ConsentTemplate.create(id(), name, text, requiredOnSignup = true, controlsMessaging = messaging).update(name, text, true, messaging, active)
        templates.insert(template)
        return template
    }

    @Test
    fun `importing copies the template into a new type with version 1 and later template changes do not touch it`() {
        val template = template()
        val imported = import.import(manager, template.id)
        assertThat(imported.name).isEqualTo("Mensagens")
        assertThat(imported.requiredOnSignup).isTrue()
        assertThat(imported.controlsMessaging).isTrue()
        assertThat(imported.versions).containsExactly(ConsentVersion(1, "Autorizo mensagens.", now))
        assertThat(imported.id).isNotEqualTo(template.id)
        assertThat(store.rows).containsKey(imported.id)
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("IMPORT_CONSENT_TEMPLATE")
            assertThat(it.entityType).isEqualTo("CONSENT_TYPE")
            assertThat(it.entityId).isEqualTo(imported.id.toString())
            assertThat(it.details).containsExactly("template")
        })
        templates.update(template.update("Mensagens 2", "Outro texto.", false, false, true))
        assertThat(store.rows.getValue(imported.id).current.text).isEqualTo("Autorizo mensagens.")
    }

    @Test
    fun `an unknown or inactive template cannot be imported`() {
        val inactive = template(name = "Antigo", active = false)
        assertApi(ErrorKind.NOT_FOUND, "CONSENT_TEMPLATE_NOT_FOUND") { import.import(manager, UUID.randomUUID()) }
        assertApi(ErrorKind.NOT_FOUND, "CONSENT_TEMPLATE_NOT_FOUND") { import.import(manager, inactive.id) }
        assertThat(store.rows).isEmpty()
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `importing respects the unique name and the single messaging type`() {
        val messaging = template(name = "Mensagens", messaging = true)
        val another = template(name = "Outras mensagens", messaging = true)
        import.import(manager, messaging.id)
        audit.events.clear()
        assertApi(ErrorKind.CONFLICT, "CONSENT_TYPE_NAME_TAKEN") { import.import(manager, messaging.id) }
        assertApi(ErrorKind.CONFLICT, "CONSENT_MESSAGING_TYPE_EXISTS") { import.import(manager, another.id) }
        assertThat(store.rows).hasSize(1)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `the clinic sees only the active templates, and only with MANAGE_CONSENT_TYPES`() {
        template(name = "b Ativo")
        template(name = "A Ativo")
        template(name = "Inativo", active = false)
        assertThat(available.list(manager).map { it.name }).containsExactly("A Ativo", "b Ativo")
        assertThatThrownBy { available.list(registrar) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { available.list(outsider) }.isInstanceOf(PermissionDeniedException::class.java)
    }
}
