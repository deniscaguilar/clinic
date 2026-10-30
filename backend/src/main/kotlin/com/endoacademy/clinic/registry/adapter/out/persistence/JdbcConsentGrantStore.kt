package com.endoacademy.clinic.registry.adapter.out.persistence

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.application.ConsentGrantStore
import com.endoacademy.clinic.registry.domain.ConsentChannel
import com.endoacademy.clinic.registry.domain.ConsentGrant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/**
 * Aceites de consentimento da clínica ativa; a RLS por tenant limita todas as consultas. O banco só deixa gravar a
 * revogação (privilégio por coluna e gatilho); aqui nada atualiza os dados do aceite. Roda na transação do chamador.
 */
class JdbcConsentGrantStore(private val jdbc: JdbcTemplate) : ConsentGrantStore {

    override fun find(patientId: UUID, id: UUID): ConsentGrant? =
        jdbc.query("$SELECT where id = ? and patient_id = ?", { rs, _ -> rs.toGrant() }, id, patientId).firstOrNull()

    override fun listByPatient(patientId: UUID): List<ConsentGrant> =
        jdbc.query("$SELECT where patient_id = ? order by accepted_at desc, id", { rs, _ -> rs.toGrant() }, patientId)

    override fun hasActive(patientId: UUID, typeId: UUID): Boolean =
        jdbc.queryForObject(
            "select exists (select 1 from registry.consent_grant where patient_id = ? and type_id = ? and revoked_at is null)",
            Boolean::class.java,
            patientId,
            typeId,
        ) == true

    override fun insert(clinicId: UUID, grant: ConsentGrant) {
        try {
            jdbc.update(
                """insert into registry.consent_grant (id, tenant_id, patient_id, type_id, version_number, accepted_at, channel, accepted_by)
                   values (?, ?, ?, ?, ?, ?, ?, ?)""",
                grant.id, clinicId, grant.patientId, grant.typeId, grant.versionNumber, Timestamp.from(grant.acceptedAt), grant.channel.name, grant.acceptedBy,
            )
        } catch (_: DuplicateKeyException) {
            throw ApiException(ErrorKind.CONFLICT, "CONSENT_ALREADY_ACCEPTED")
        }
    }

    override fun revoke(grant: ConsentGrant) {
        val updated = jdbc.update(
            "update registry.consent_grant set revoked_at = ?, revoked_by = ? where id = ? and revoked_at is null",
            Timestamp.from(checkNotNull(grant.revokedAt)), checkNotNull(grant.revokedBy), grant.id,
        )
        if (updated == 0) throw ApiException(ErrorKind.CONFLICT, "CONSENT_ALREADY_REVOKED")
    }

    private fun ResultSet.toGrant() = ConsentGrant.restore(
        getObject("id", UUID::class.java),
        getObject("patient_id", UUID::class.java),
        getObject("type_id", UUID::class.java),
        getInt("version_number"),
        getTimestamp("accepted_at").toInstant(),
        ConsentChannel.valueOf(getString("channel")),
        getObject("accepted_by", UUID::class.java),
        getTimestamp("revoked_at")?.toInstant(),
        getObject("revoked_by", UUID::class.java),
    )

    private companion object {
        const val SELECT = "select id, patient_id, type_id, version_number, accepted_at, channel, accepted_by, revoked_at, revoked_by from registry.consent_grant"
    }
}
