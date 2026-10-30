package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.application.MemberStore
import com.endoacademy.clinic.platform.application.MemberView
import com.endoacademy.clinic.platform.application.MembershipStatus
import com.endoacademy.clinic.platform.application.RoleRef
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Usuários e vínculos da clínica ativa, na transação do chamador; a RLS limita tudo ao tenant (V3 e V4). */
class JdbcMemberStore(private val jdbc: JdbcTemplate) : MemberStore {

    override fun ensureUser(id: UUID, email: String, name: String) {
        jdbc.update("insert into platform.app_user (id, email, name) values (?, ?, ?) on conflict do nothing", id, email, name)
    }

    override fun findByUser(userId: UUID): MemberView? = members("where m.user_id = ?", userId).firstOrNull()

    override fun find(membershipId: UUID): MemberView? = members("where m.id = ?", membershipId).firstOrNull()

    override fun list(): List<MemberView> = members("")

    override fun insertMembership(id: UUID, clinicId: UUID, userId: UUID, status: MembershipStatus, userSub: String?) {
        jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, ?, ?)", id, clinicId, userId, status.name, userSub)
    }

    override fun rolesById(ids: Collection<UUID>): List<RoleRef> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return jdbc.query("select id, name from platform.role where id in ($placeholders)", { rs, _ -> RoleRef(rs.getObject("id", UUID::class.java), rs.getString("name")) }, *ids.toTypedArray())
    }

    override fun replaceRoles(clinicId: UUID, membershipId: UUID, roleIds: Set<UUID>) {
        jdbc.update("delete from platform.membership_role where membership_id = ?", membershipId)
        roleIds.forEach {
            jdbc.update("insert into platform.membership_role (tenant_id, membership_id, role_id) values (?, ?, ?)", clinicId, membershipId, it)
        }
    }

    override fun setUserSub(userId: UUID, sub: String) {
        jdbc.update("update platform.app_user set sub = ? where id = ? and sub is null", sub, userId)
    }

    override fun activate(clinicId: UUID, membershipId: UUID, sub: String) {
        jdbc.update("update platform.membership set status = 'ACTIVE', user_sub = ? where id = ? and tenant_id = ?", sub, membershipId, clinicId)
    }

    override fun setStatus(membershipId: UUID, status: MembershipStatus) {
        jdbc.update("update platform.membership set status = ? where id = ?", status.name, membershipId)
    }

    /** `for update` trava os administradores ativos: duas remoções simultâneas não deixam a clínica sem nenhum. */
    override fun activeClinicAdmins(): Set<UUID> = jdbc.queryForList(
        """select m.id
           from platform.membership m
           join platform.membership_role mr on mr.membership_id = m.id
           join platform.role r on r.id = mr.role_id
           where m.status = 'ACTIVE' and r.name = 'CLINIC_ADMIN'
           for update of m""",
        UUID::class.java,
    ).filterNotNull().toSet()

    private fun members(where: String, vararg args: Any): List<MemberView> {
        val roles = jdbc.query(
            """select mr.membership_id, r.id, r.name from platform.membership_role mr join platform.role r on r.id = mr.role_id order by r.name""",
            { rs, _ -> rs.getObject("membership_id", UUID::class.java) to RoleRef(rs.getObject("id", UUID::class.java), rs.getString("name")) },
        ).groupBy({ it.first }, { it.second })
        return jdbc.query(
            """select m.id, m.user_id, m.status, u.email, u.name, u.sub
               from platform.membership m join platform.app_user u on u.id = m.user_id
               $where order by u.name, m.id""",
            { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                MemberView(
                    membershipId = id,
                    userId = rs.getObject("user_id", UUID::class.java),
                    email = rs.getString("email"),
                    name = rs.getString("name"),
                    sub = rs.getString("sub"),
                    status = MembershipStatus.valueOf(rs.getString("status")),
                    roles = roles[id].orEmpty(),
                )
            },
            *args,
        )
    }
}
