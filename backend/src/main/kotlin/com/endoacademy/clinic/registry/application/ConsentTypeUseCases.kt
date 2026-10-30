package com.endoacademy.clinic.registry.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.domain.ConsentRules
import com.endoacademy.clinic.registry.domain.ConsentTemplate
import com.endoacademy.clinic.registry.domain.ConsentType
import com.endoacademy.clinic.registry.domain.ConsentVersion
import java.time.Clock
import java.util.UUID

/** Tipos de consentimento da clínica ativa, cada um com todas as suas versões. Roda na transação do [Transactions]. */
interface ConsentTypeStore {
    /** Ordenados pelo nome, sem diferenciar maiúsculas; `active` nulo traz todos. */
    fun list(active: Boolean?): List<ConsentType>

    fun find(id: UUID): ConsentType?

    /** Grava o tipo e a(s) versão(ões) que ele já tem. */
    fun insert(clinicId: UUID, type: ConsentType)

    /** Atualiza só nome e opções. As versões não passam por aqui: use [addVersion]. */
    fun update(type: ConsentType)

    /**
     * Acrescenta a próxima versão do tipo. Se outra revisão já gravou esse número (duas pessoas revisando a partir da
     * mesma versão), lança `ApiException` 409 `CONSENT_VERSION_CONFLICT` e nada é gravado: o texto nunca se perde em
     * silêncio. Nenhuma versão existente é alterada.
     */
    fun addVersion(typeId: UUID, version: ConsentVersion)

    /** Compara ignorando maiúsculas e minúsculas. */
    fun nameTaken(name: String, exceptId: UUID?): Boolean
}

/** Modelos de consentimento oferecidos pela plataforma. Os da leitura abrem para toda clínica; a escrita é do `platform`. */
interface ConsentTemplateStore {
    /** Ordenados pelo nome, sem diferenciar maiúsculas; `active` nulo traz todos. */
    fun list(active: Boolean?): List<ConsentTemplate>

    fun find(id: UUID): ConsentTemplate?

    fun insert(template: ConsentTemplate)

    fun update(template: ConsentTemplate)

    /** Compara ignorando maiúsculas e minúsculas. */
    fun nameTaken(name: String, exceptId: UUID?): Boolean
}

data class ConsentTypeData(val name: String, val text: String, val requiredOnSignup: Boolean, val controlsMessaging: Boolean)

/** `text` nulo, ou igual ao vigente, não cria versão. */
data class ConsentTypeChange(val name: String, val text: String?, val requiredOnSignup: Boolean, val controlsMessaging: Boolean, val active: Boolean)

private const val MANAGE_CONSENT_TYPES = "MANAGE_CONSENT_TYPES"
private const val MANAGE_PATIENTS = "MANAGE_PATIENTS"
private const val RECORD_CONSENT = "RECORD_CONSENT"

private fun typeNotFound() = ApiException(ErrorKind.NOT_FOUND, "CONSENT_TYPE_NOT_FOUND")

private fun nameTaken() = ApiException(ErrorKind.CONFLICT, "CONSENT_TYPE_NAME_TAKEN")

/** Quem gere os tipos e quem cadastra pacientes ou registra aceites (precisam dos tipos para o aceite) podem ler. */
private fun requireReadAccess(authorizer: Authorizer, user: CurrentUser) {
    if (user.has(MANAGE_CONSENT_TYPES) || user.has(MANAGE_PATIENTS) || user.has(RECORD_CONSENT)) return
    authorizer.require(user, MANAGE_CONSENT_TYPES)
}

class ListConsentTypes(private val authorizer: Authorizer, private val store: ConsentTypeStore, private val transactions: Transactions) {
    fun list(user: CurrentUser, active: Boolean?): List<ConsentType> {
        requireReadAccess(authorizer, user)
        return transactions.inTransaction { store.list(active) }
    }
}

class GetConsentType(private val authorizer: Authorizer, private val store: ConsentTypeStore, private val transactions: Transactions) {
    fun get(user: CurrentUser, id: UUID): ConsentType {
        requireReadAccess(authorizer, user)
        return transactions.inTransaction { store.find(id) } ?: throw typeNotFound()
    }
}

class CreateConsentType(
    private val authorizer: Authorizer,
    private val store: ConsentTypeStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun create(user: CurrentUser, data: ConsentTypeData): ConsentType {
        authorizer.require(user, MANAGE_CONSENT_TYPES)
        val type = domain { ConsentType.create(newId(), data.name, data.text, data.requiredOnSignup, data.controlsMessaging, clock.instant()) }
        return transactions.inTransaction {
            if (store.nameTaken(type.name, null)) throw nameTaken()
            domain { ConsentRules.requireSingleMessagingControl(store.list(null), type) }
            store.insert(user.clinicId, type)
            auditTrail.record(
                user,
                AuditEvent("CREATE_CONSENT_TYPE", "CONSENT_TYPE", type.id.toString(), details = setOf("name", "text", "requiredOnSignup", "controlsMessaging")),
            )
            type
        }
    }
}

/**
 * `PUT /consent-types/{id}`: muda nome e opções e, **se o texto for outro, cria a versão seguinte** (CA15). Nenhuma
 * versão existente é alterada; os aceites continuam presos à versão que o paciente viu.
 */
class ReviseConsentType(
    private val authorizer: Authorizer,
    private val store: ConsentTypeStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val clock: Clock,
) {
    fun revise(user: CurrentUser, id: UUID, change: ConsentTypeChange): ConsentType {
        authorizer.require(user, MANAGE_CONSENT_TYPES)
        return transactions.inTransaction {
            val current = store.find(id) ?: throw typeNotFound()
            val newText = change.text
            val textChanged = newText != null && newText.trim() != current.current.text
            val withText = if (textChanged) domain { current.revise(newText!!, clock.instant()) } else current
            val updated = domain { withText.update(change.name, change.requiredOnSignup, change.controlsMessaging, change.active) }
            if (store.nameTaken(updated.name, id)) throw nameTaken()
            domain { ConsentRules.requireSingleMessagingControl(store.list(null), updated) }
            val settings = buildSet {
                if (updated.name != current.name) add("name")
                if (updated.requiredOnSignup != current.requiredOnSignup) add("requiredOnSignup")
                if (updated.controlsMessaging != current.controlsMessaging) add("controlsMessaging")
                if (updated.active != current.active) add("active")
            }
            if (textChanged || settings.isNotEmpty()) {
                if (textChanged) store.addVersion(id, updated.current)
                if (settings.isNotEmpty()) store.update(updated)
                if (textChanged) auditTrail.record(user, AuditEvent("REVISE_CONSENT_TYPE", "CONSENT_TYPE", id.toString(), details = setOf("text")))
                if (settings.isNotEmpty()) auditTrail.record(user, AuditEvent("UPDATE_CONSENT_TYPE", "CONSENT_TYPE", id.toString(), details = settings))
            }
            updated
        }
    }
}

/** Os modelos que a clínica pode importar: só os ativos. */
class ListAvailableConsentTemplates(
    private val authorizer: Authorizer,
    private val templates: ConsentTemplateStore,
    private val transactions: Transactions,
) {
    fun list(user: CurrentUser): List<ConsentTemplate> {
        authorizer.require(user, MANAGE_CONSENT_TYPES)
        return transactions.inTransaction { templates.list(true) }
    }
}

/** Copia um modelo ativo para um tipo da clínica (versão 1 com o texto do modelo); a cópia não acompanha o modelo. */
class ImportConsentTemplate(
    private val authorizer: Authorizer,
    private val store: ConsentTypeStore,
    private val templates: ConsentTemplateStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun import(user: CurrentUser, templateId: UUID): ConsentType {
        authorizer.require(user, MANAGE_CONSENT_TYPES)
        return transactions.inTransaction {
            val template = templates.find(templateId)?.takeIf { it.active } ?: throw ApiException(ErrorKind.NOT_FOUND, "CONSENT_TEMPLATE_NOT_FOUND")
            val type = domain { template.toType(newId(), clock.instant()) }
            if (store.nameTaken(type.name, null)) throw nameTaken()
            domain { ConsentRules.requireSingleMessagingControl(store.list(null), type) }
            store.insert(user.clinicId, type)
            auditTrail.record(user, AuditEvent("IMPORT_CONSENT_TEMPLATE", "CONSENT_TYPE", type.id.toString(), details = setOf("template")))
            type
        }
    }
}
