package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.application.RoleStore
import com.endoacademy.clinic.platform.application.RoleView
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Perfis da clínica ativa; a RLS por tenant limita todas as consultas. Roda na transação do chamador. */
class JdbcRoleStore(private val jdbc: JdbcTemplate) : RoleStore {

    override fun list(): List<RoleView> = rows("")

    override fun find(id: UUID): RoleView? = rows("where r.id = ?", id).firstOrNull()

    override fun insert(id: UUID, clinicId: UUID, name: String, permissions: Set<String>) {
        translatingDuplicates {
            jdbc.update("insert into platform.role (id, tenant_id, name) values (?, ?, ?)", id, clinicId, name)
        }
        insertPermissions(id, clinicId, permissions)
    }

    override fun nameTaken(name: String, exceptId: UUID?): Boolean =
        jdbc.queryForObject(
            "select exists (select 1 from platform.role where lower(name) = lower(?) and (cast(? as uuid) is null or id <> cast(? as uuid)))",
            Boolean::class.java,
            name,
            exceptId,
            exceptId,
        ) == true

    override fun update(id: UUID, name: String, permissions: Set<String>) {
        translatingDuplicates { jdbc.update("update platform.role set name = ? where id = ?", name, id) }
        val clinicId = jdbc.queryForObject("select tenant_id from platform.role where id = ?", UUID::class.java, id)!!
        jdbc.update("delete from platform.role_permission where role_id = ?", id)
        insertPermissions(id, clinicId, permissions)
    }

    override fun delete(id: UUID) {
        jdbc.update("delete from platform.role where id = ?", id)
    }

    private fun insertPermissions(roleId: UUID, clinicId: UUID, permissions: Set<String>) {
        permissions.sorted().forEach {
            jdbc.update("insert into platform.role_permission (tenant_id, role_id, permission) values (?, ?, ?)", clinicId, roleId, it)
        }
    }

    private fun rows(where: String, vararg args: Any): List<RoleView> {
        val permissions = jdbc.query("select role_id, permission from platform.role_permission order by permission", { rs, _ ->
            rs.getObject("role_id", UUID::class.java) to rs.getString("permission")
        }).groupBy({ it.first }, { it.second })
        return jdbc.query(
            """select r.id, r.name, (select count(*) from platform.membership_role mr where mr.role_id = r.id) as members
               from platform.role r $where order by r.name, r.id""",
            { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                RoleView(id, rs.getString("name"), permissions[id].orEmpty().toSet(), rs.getInt("members"))
            },
            *args,
        )
    }

    private fun translatingDuplicates(block: () -> Unit) = try {
        block()
    } catch (_: DuplicateKeyException) {
        throw ApiException(ErrorKind.CONFLICT, "ROLE_NAME_TAKEN")
    }
}
