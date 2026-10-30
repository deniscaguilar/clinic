package com.endoacademy.clinic.registry.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PlatformAdminAccess
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.registry.domain.ConsentTemplate
import java.util.UUID

/** `active` só vale na atualização: o modelo nasce ativo. */
data class ConsentTemplateData(val name: String, val text: String, val requiredOnSignup: Boolean, val controlsMessaging: Boolean, val active: Boolean = true)

private fun templateNotFound() = ApiException(ErrorKind.NOT_FOUND, "CONSENT_TEMPLATE_NOT_FOUND")

private fun templateNameTaken() = ApiException(ErrorKind.CONFLICT, "CONSENT_TEMPLATE_NAME_TAKEN")

/**
 * Modelos de consentimento da plataforma (`/admin/consent-templates`). Tudo é do `PLATFORM_ADMIN`: o acesso é conferido
 * antes de abrir transação e as gravações rodam no escopo `platform`, o único que a RLS deixa gravar. A auditoria é de
 * ação da plataforma (sem clínica) e nunca leva o texto.
 */
class ListConsentTemplates(private val access: PlatformAdminAccess, private val store: ConsentTemplateStore, private val transactions: Transactions) {
    fun list(actorSub: String, active: Boolean?): List<ConsentTemplate> {
        access.require(actorSub)
        return transactions.inTransaction { store.list(active) }
    }
}

class GetConsentTemplate(private val access: PlatformAdminAccess, private val store: ConsentTemplateStore, private val transactions: Transactions) {
    fun get(actorSub: String, id: UUID): ConsentTemplate {
        access.require(actorSub)
        return transactions.inTransaction { store.find(id) } ?: throw templateNotFound()
    }
}

class CreateConsentTemplate(
    private val access: PlatformAdminAccess,
    private val store: ConsentTemplateStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun create(actorSub: String, data: ConsentTemplateData): ConsentTemplate {
        val actor = access.require(actorSub)
        val template = domain { ConsentTemplate.create(newId(), data.name, data.text, data.requiredOnSignup, data.controlsMessaging) }
        return TenantScope.runAsPlatform {
            transactions.inTransaction {
                if (store.nameTaken(template.name, null)) throw templateNameTaken()
                store.insert(template)
                auditTrail.recordPlatform(
                    actor,
                    null,
                    AuditEvent("CREATE_CONSENT_TEMPLATE", "CONSENT_TEMPLATE", template.id.toString(), details = setOf("name", "text", "requiredOnSignup", "controlsMessaging")),
                )
                template
            }
        }
    }
}

class UpdateConsentTemplate(
    private val access: PlatformAdminAccess,
    private val store: ConsentTemplateStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun update(actorSub: String, id: UUID, data: ConsentTemplateData): ConsentTemplate {
        val actor = access.require(actorSub)
        return TenantScope.runAsPlatform {
            transactions.inTransaction {
                val current = store.find(id) ?: throw templateNotFound()
                val updated = domain { current.update(data.name, data.text, data.requiredOnSignup, data.controlsMessaging, data.active) }
                if (store.nameTaken(updated.name, id)) throw templateNameTaken()
                store.update(updated)
                auditTrail.recordPlatform(
                    actor,
                    null,
                    AuditEvent("UPDATE_CONSENT_TEMPLATE", "CONSENT_TEMPLATE", id.toString(), details = setOf("name", "text", "requiredOnSignup", "controlsMessaging", "active")),
                )
                updated
            }
        }
    }
}
