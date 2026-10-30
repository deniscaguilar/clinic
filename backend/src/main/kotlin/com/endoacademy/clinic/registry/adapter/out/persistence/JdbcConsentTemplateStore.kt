package com.endoacademy.clinic.registry.adapter.out.persistence

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.application.ConsentTemplateStore
import com.endoacademy.clinic.registry.domain.ConsentTemplate
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * Modelos de consentimento da plataforma. A leitura é aberta a toda clínica; `insert` e `update` só funcionam no escopo
 * `platform` (a RLS recusa o resto). Roda na transação do chamador.
 */
class JdbcConsentTemplateStore(private val jdbc: JdbcTemplate) : ConsentTemplateStore {

    override fun list(active: Boolean?): List<ConsentTemplate> =
        jdbc.query(
            "$SELECT where (cast(? as boolean) is null or active = ?) order by lower(name), id",
            { rs, _ -> rs.toTemplate() },
            active,
            active,
        )

    override fun find(id: UUID): ConsentTemplate? = jdbc.query("$SELECT where id = ?", { rs, _ -> rs.toTemplate() }, id).firstOrNull()

    override fun insert(template: ConsentTemplate) {
        translating {
            jdbc.update(
                "insert into registry.consent_template (id, name, text, required_on_signup, controls_messaging, active) values (?, ?, ?, ?, ?, ?)",
                template.id, template.name, template.text, template.requiredOnSignup, template.controlsMessaging, template.active,
            )
        }
    }

    override fun update(template: ConsentTemplate) {
        translating {
            jdbc.update(
                "update registry.consent_template set name = ?, text = ?, required_on_signup = ?, controls_messaging = ?, active = ?, updated_at = now() where id = ?",
                template.name, template.text, template.requiredOnSignup, template.controlsMessaging, template.active, template.id,
            )
        }
    }

    override fun nameTaken(name: String, exceptId: UUID?): Boolean =
        jdbc.queryForObject(
            "select exists (select 1 from registry.consent_template where lower(name) = lower(?) and (cast(? as uuid) is null or id <> cast(? as uuid)))",
            Boolean::class.java,
            name,
            exceptId,
            exceptId,
        ) == true

    private fun ResultSet.toTemplate() = ConsentTemplate.restore(
        getObject("id", UUID::class.java),
        getString("name"),
        getString("text"),
        getBoolean("required_on_signup"),
        getBoolean("controls_messaging"),
        getBoolean("active"),
    )

    /** Corrida entre dois cadastros com o mesmo nome: o índice único decide e vira 409. */
    private fun translating(block: () -> Unit) = try {
        block()
    } catch (_: DuplicateKeyException) {
        throw ApiException(ErrorKind.CONFLICT, "CONSENT_TEMPLATE_NAME_TAKEN")
    }

    private companion object {
        const val SELECT = "select id, name, text, required_on_signup, controls_messaging, active from registry.consent_template"
    }
}
