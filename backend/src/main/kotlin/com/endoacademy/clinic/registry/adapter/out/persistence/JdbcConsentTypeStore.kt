package com.endoacademy.clinic.registry.adapter.out.persistence

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.application.ConsentTypeStore
import com.endoacademy.clinic.registry.domain.ConsentType
import com.endoacademy.clinic.registry.domain.ConsentVersion
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * Tipos de consentimento da clínica ativa; a RLS por tenant limita todas as consultas. As versões só são inseridas:
 * nenhuma consulta altera ou apaga uma versão (o banco também recusa). Roda na transação do chamador.
 */
class JdbcConsentTypeStore(private val jdbc: JdbcTemplate) : ConsentTypeStore {

    private data class Row(val id: UUID, val name: String, val requiredOnSignup: Boolean, val controlsMessaging: Boolean, val active: Boolean)

    override fun list(active: Boolean?): List<ConsentType> {
        val versions = versions("")
        return jdbc.query(
            "$SELECT where (cast(? as boolean) is null or active = ?) order by lower(name), id",
            { rs, _ -> rs.toRow() },
            active,
            active,
        ).map { it.toType(versions) }
    }

    override fun find(id: UUID): ConsentType? {
        val versions = versions("where type_id = ?", id)
        return jdbc.query("$SELECT where id = ?", { rs, _ -> rs.toRow() }, id).firstOrNull()?.toType(versions)
    }

    override fun insert(clinicId: UUID, type: ConsentType) {
        translating {
            jdbc.update(
                "insert into registry.consent_type (id, tenant_id, name, required_on_signup, controls_messaging, active) values (?, ?, ?, ?, ?, ?)",
                type.id, clinicId, type.name, type.requiredOnSignup, type.controlsMessaging, type.active,
            )
            type.versions.forEach { insertVersion(clinicId, type.id, it) }
        }
    }

    override fun update(type: ConsentType) {
        translating {
            jdbc.update(
                "update registry.consent_type set name = ?, required_on_signup = ?, controls_messaging = ?, active = ?, updated_at = now() where id = ?",
                type.name, type.requiredOnSignup, type.controlsMessaging, type.active, type.id,
            )
        }
    }

    override fun addVersion(typeId: UUID, version: ConsentVersion) {
        translating {
            val clinicId = jdbc.queryForObject("select tenant_id from registry.consent_type where id = ?", UUID::class.java, typeId)!!
            insertVersion(clinicId, typeId, version)
        }
    }

    override fun nameTaken(name: String, exceptId: UUID?): Boolean =
        jdbc.queryForObject(
            "select exists (select 1 from registry.consent_type where lower(name) = lower(?) and (cast(? as uuid) is null or id <> cast(? as uuid)))",
            Boolean::class.java,
            name,
            exceptId,
            exceptId,
        ) == true

    private fun insertVersion(clinicId: UUID, typeId: UUID, version: ConsentVersion) {
        jdbc.update(
            "insert into registry.consent_type_version (tenant_id, type_id, number, text, created_at) values (?, ?, ?, ?, ?)",
            clinicId, typeId, version.number, version.text, java.sql.Timestamp.from(version.createdAt),
        )
    }

    private fun versions(where: String, vararg args: Any): Map<UUID, List<ConsentVersion>> =
        jdbc.query(
            "select type_id, number, text, created_at from registry.consent_type_version $where order by type_id, number",
            { rs, _ ->
                rs.getObject("type_id", UUID::class.java) to ConsentVersion(rs.getInt("number"), rs.getString("text"), rs.getTimestamp("created_at").toInstant())
            },
            *args,
        ).groupBy({ it.first }, { it.second })

    private fun ResultSet.toRow() = Row(
        getObject("id", UUID::class.java),
        getString("name"),
        getBoolean("required_on_signup"),
        getBoolean("controls_messaging"),
        getBoolean("active"),
    )

    private fun Row.toType(versions: Map<UUID, List<ConsentVersion>>): ConsentType =
        ConsentType.restore(id, name, requiredOnSignup, controlsMessaging, active, versions[id].orEmpty())

    /** Corridas que só o índice ou o gatilho decidem viram erro de negócio (409), não 500. */
    private fun translating(block: () -> Unit) = try {
        block()
    } catch (exception: DuplicateKeyException) {
        val cause = exception.mostSpecificCause.message.orEmpty()
        throw when {
            "consent_type_one_messaging_key" in cause -> ApiException(ErrorKind.CONFLICT, "CONSENT_MESSAGING_TYPE_EXISTS")
            "consent_type_tenant_name_key" in cause -> ApiException(ErrorKind.CONFLICT, "CONSENT_TYPE_NAME_TAKEN")
            "consent_type_version_pkey" in cause -> ApiException(ErrorKind.CONFLICT, "CONSENT_VERSION_CONFLICT")
            else -> exception
        }
    } catch (exception: DataIntegrityViolationException) {
        if ("must be the next one" in exception.mostSpecificCause.message.orEmpty()) throw ApiException(ErrorKind.CONFLICT, "CONSENT_VERSION_CONFLICT")
        throw exception
    }

    private companion object {
        const val SELECT = "select id, name, required_on_signup, controls_messaging, active from registry.consent_type"
    }
}
