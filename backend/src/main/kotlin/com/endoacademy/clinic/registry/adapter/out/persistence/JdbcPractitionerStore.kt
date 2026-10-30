package com.endoacademy.clinic.registry.adapter.out.persistence

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.application.PractitionerStore
import com.endoacademy.clinic.registry.domain.Practitioner
import com.endoacademy.clinic.registry.domain.PractitionerType
import com.endoacademy.clinic.registry.domain.WorkInterval
import com.endoacademy.clinic.registry.domain.WorkSchedule
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/** Profissionais da clínica ativa; a RLS por tenant limita todas as consultas. Roda na transação do chamador. */
class JdbcPractitionerStore(private val jdbc: JdbcTemplate) : PractitionerStore {

    override fun list(active: Boolean?): List<Practitioner> {
        val schedules = schedules("")
        return jdbc.query(
            "$SELECT where (cast(? as boolean) is null or active = ?) order by lower(name), id",
            { rs, _ -> rs.toPractitioner(schedules) },
            active,
            active,
        )
    }

    override fun find(id: UUID): Practitioner? {
        val schedules = schedules("where practitioner_id = ?", id)
        return jdbc.query("$SELECT where id = ?", { rs, _ -> rs.toPractitioner(schedules) }, id).firstOrNull()
    }

    override fun insert(clinicId: UUID, practitioner: Practitioner) {
        translating {
            jdbc.update(
                "insert into registry.practitioner (id, tenant_id, user_id, type, name, registration_number, active) values (?, ?, ?, ?, ?, ?, ?)",
                practitioner.id,
                clinicId,
                practitioner.userId,
                practitioner.type.name,
                practitioner.name,
                practitioner.registrationNumber,
                practitioner.active,
            )
            insertSchedule(clinicId, practitioner)
        }
    }

    /** Atualiza o profissional primeiro: a linha travada serializa quem regrava o horário do mesmo profissional. */
    override fun update(practitioner: Practitioner) {
        translating {
            jdbc.update(
                "update registry.practitioner set user_id = ?, type = ?, name = ?, registration_number = ?, active = ?, updated_at = now() where id = ?",
                practitioner.userId,
                practitioner.type.name,
                practitioner.name,
                practitioner.registrationNumber,
                practitioner.active,
                practitioner.id,
            )
            val clinicId = jdbc.queryForObject("select tenant_id from registry.practitioner where id = ?", UUID::class.java, practitioner.id)!!
            jdbc.update("delete from registry.practitioner_work_schedule where practitioner_id = ?", practitioner.id)
            insertSchedule(clinicId, practitioner)
        }
    }

    override fun userLinked(userId: UUID, exceptId: UUID?): Boolean =
        jdbc.queryForObject(
            "select exists (select 1 from registry.practitioner where user_id = ? and (cast(? as uuid) is null or id <> cast(? as uuid)))",
            Boolean::class.java,
            userId,
            exceptId,
            exceptId,
        ) == true

    private fun insertSchedule(clinicId: UUID, practitioner: Practitioner) {
        practitioner.schedule.intervals.forEach {
            jdbc.update(
                "insert into registry.practitioner_work_schedule (tenant_id, practitioner_id, day_of_week, start_time, end_time) values (?, ?, ?, ?, ?)",
                clinicId,
                practitioner.id,
                it.dayOfWeek.value,
                it.start,
                it.end,
            )
        }
    }

    private fun schedules(where: String, vararg args: Any): Map<UUID, WorkSchedule> =
        jdbc.query(
            "select practitioner_id, day_of_week, start_time, end_time from registry.practitioner_work_schedule $where",
            { rs, _ ->
                rs.getObject("practitioner_id", UUID::class.java) to WorkInterval(
                    DayOfWeek.of(rs.getInt("day_of_week")),
                    rs.getObject("start_time", LocalTime::class.java),
                    rs.getObject("end_time", LocalTime::class.java),
                )
            },
            *args,
        ).groupBy({ it.first }, { it.second }).mapValues { WorkSchedule.of(it.value) }

    private fun ResultSet.toPractitioner(schedules: Map<UUID, WorkSchedule>): Practitioner {
        val id = getObject("id", UUID::class.java)
        return Practitioner.restore(
            id,
            getString("name"),
            PractitionerType.valueOf(getString("type")),
            getString("registration_number"),
            getObject("user_id", UUID::class.java),
            getBoolean("active"),
            schedules[id] ?: WorkSchedule.of(emptyList()),
        )
    }

    /** Corridas que só o banco decide viram erro de negócio, não 500. */
    private fun translating(block: () -> Unit) = try {
        block()
    } catch (_: DuplicateKeyException) {
        throw ApiException(ErrorKind.CONFLICT, "PRACTITIONER_USER_ALREADY_LINKED")
    } catch (exception: DataIntegrityViolationException) {
        if (exception.mostSpecificCause.message?.contains("work interval overlaps") == true) throw ApiException(ErrorKind.UNPROCESSABLE, "WORK_SCHEDULE_OVERLAP")
        throw exception
    }

    private companion object {
        const val SELECT = "select id, user_id, type, name, registration_number, active from registry.practitioner"
    }
}
