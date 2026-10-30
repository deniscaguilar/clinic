package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.api.ClinicMembers
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Pertença à clínica lida sob a RLS do tenant da requisição. Roda na transação do chamador, sem abrir outra. */
class JdbcClinicMembers(private val jdbc: JdbcTemplate) : ClinicMembers {

    override fun isActiveMember(clinicId: UUID, userId: UUID): Boolean =
        jdbc.queryForObject(
            "select exists (select 1 from platform.membership where tenant_id = ? and user_id = ? and status = 'ACTIVE')",
            Boolean::class.java,
            clinicId,
            userId,
        ) == true
}
