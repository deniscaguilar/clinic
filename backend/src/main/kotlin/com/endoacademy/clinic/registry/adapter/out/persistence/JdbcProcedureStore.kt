package com.endoacademy.clinic.registry.adapter.out.persistence

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.application.ProcedureStore
import com.endoacademy.clinic.registry.domain.Procedure
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Procedimentos da clínica ativa; a RLS por tenant limita todas as consultas. Roda na transação do chamador. */
class JdbcProcedureStore(private val jdbc: JdbcTemplate) : ProcedureStore {

    override fun list(active: Boolean?): List<Procedure> =
        jdbc.query(
            "select id, name, description, active from registry.procedure where (cast(? as boolean) is null or active = ?) order by lower(name), id",
            { rs, _ -> rs.toProcedure() },
            active,
            active,
        )

    override fun find(id: UUID): Procedure? =
        jdbc.query("select id, name, description, active from registry.procedure where id = ?", { rs, _ -> rs.toProcedure() }, id).firstOrNull()

    override fun insert(clinicId: UUID, procedure: Procedure) {
        translatingDuplicates {
            jdbc.update(
                "insert into registry.procedure (id, tenant_id, name, description, active) values (?, ?, ?, ?, ?)",
                procedure.id,
                clinicId,
                procedure.name,
                procedure.description,
                procedure.active,
            )
        }
    }

    override fun update(procedure: Procedure) {
        translatingDuplicates {
            jdbc.update(
                "update registry.procedure set name = ?, description = ?, active = ?, updated_at = now() where id = ?",
                procedure.name,
                procedure.description,
                procedure.active,
                procedure.id,
            )
        }
    }

    override fun nameTaken(name: String, exceptId: UUID?): Boolean =
        jdbc.queryForObject(
            "select exists (select 1 from registry.procedure where lower(name) = lower(?) and (cast(? as uuid) is null or id <> cast(? as uuid)))",
            Boolean::class.java,
            name,
            exceptId,
            exceptId,
        ) == true

    private fun java.sql.ResultSet.toProcedure() =
        Procedure.restore(getObject("id", UUID::class.java), getString("name"), getString("description"), getBoolean("active"))

    /** Corrida entre duas requisições com o mesmo nome: o índice único decide e vira 409, não 500. */
    private fun translatingDuplicates(block: () -> Unit) = try {
        block()
    } catch (_: DuplicateKeyException) {
        throw ApiException(ErrorKind.CONFLICT, "PROCEDURE_NAME_TAKEN")
    }
}
