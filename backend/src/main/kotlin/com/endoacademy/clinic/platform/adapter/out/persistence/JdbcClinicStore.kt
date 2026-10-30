package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.application.ClinicAdmin
import com.endoacademy.clinic.platform.application.ClinicOverview
import com.endoacademy.clinic.platform.application.ClinicStatus
import com.endoacademy.clinic.platform.application.ClinicStore
import com.endoacademy.clinic.platform.application.MembershipInfo
import com.endoacademy.clinic.platform.application.MembershipStatus
import com.endoacademy.clinic.platform.application.StoredUser
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Gravações de identidade na transação aberta pelo `UnitOfWork` (escopo `platform` + tenant da clínica, ADR-0010). */
class JdbcClinicStore(private val jdbc: JdbcTemplate) : ClinicStore {

    override fun insertClinic(id: UUID, name: String) {
        jdbc.update("insert into platform.clinic (id, name) values (?, ?)", id, name)
    }

    override fun findUserByEmail(email: String): StoredUser? = jdbc.query(
        "select id, sub from platform.app_user where lower(email) = ?",
        { rs, _ -> StoredUser(rs.getObject("id", UUID::class.java), rs.getString("sub")) },
        email,
    ).firstOrNull()

    override fun insertUser(id: UUID, email: String, name: String) {
        jdbc.update("insert into platform.app_user (id, email, name) values (?, ?, ?)", id, email, name)
    }

    override fun insertMembership(id: UUID, clinicId: UUID, userId: UUID, status: MembershipStatus, userSub: String?) {
        jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, ?, ?)", id, clinicId, userId, status.name, userSub)
    }

    override fun insertRole(id: UUID, clinicId: UUID, name: String, permissions: Set<String>) {
        jdbc.update("insert into platform.role (id, tenant_id, name) values (?, ?, ?)", id, clinicId, name)
        permissions.sorted().forEach {
            jdbc.update("insert into platform.role_permission (tenant_id, role_id, permission) values (?, ?, ?)", clinicId, id, it)
        }
    }

    override fun assignRole(clinicId: UUID, membershipId: UUID, roleId: UUID) {
        jdbc.update("insert into platform.membership_role (tenant_id, membership_id, role_id) values (?, ?, ?)", clinicId, membershipId, roleId)
    }

    override fun setUserSub(userId: UUID, sub: String) {
        jdbc.update("update platform.app_user set sub = ? where id = ? and sub is null", sub, userId)
    }

    override fun activateMembership(clinicId: UUID, membershipId: UUID, sub: String) {
        jdbc.update("update platform.membership set status = 'ACTIVE', user_sub = ? where id = ? and tenant_id = ?", sub, membershipId, clinicId)
    }

    override fun updateClinicName(id: UUID, name: String): Boolean =
        jdbc.update("update platform.clinic set name = ?, updated_at = now() where id = ?", name, id) > 0

    override fun setClinicStatus(id: UUID, status: ClinicStatus): ClinicStatus? {
        val previous = jdbc.query("select status from platform.clinic where id = ? for update", { rs, _ -> ClinicStatus.valueOf(rs.getString(1)) }, id).firstOrNull()
            ?: return null
        if (previous != status) jdbc.update("update platform.clinic set status = ?, updated_at = now() where id = ?", status.name, id)
        return previous
    }

    override fun findMembership(clinicId: UUID, membershipId: UUID): MembershipInfo? = jdbc.query(
        """select m.id, m.user_id, m.status, u.email, u.name, u.sub
           from platform.membership m join platform.app_user u on u.id = m.user_id
           where m.id = ? and m.tenant_id = ?""",
        { rs, _ ->
            MembershipInfo(
                membershipId = rs.getObject("id", UUID::class.java),
                userId = rs.getObject("user_id", UUID::class.java),
                email = rs.getString("email"),
                name = rs.getString("name"),
                sub = rs.getString("sub"),
                status = MembershipStatus.valueOf(rs.getString("status")),
            )
        },
        membershipId,
        clinicId,
    ).firstOrNull()

    override fun listClinics(): List<ClinicOverview> =
        jdbc.query("select id, name, status from platform.clinic order by lower(name), id", { rs, _ -> overview(rs) })

    override fun findClinic(id: UUID): ClinicOverview? =
        jdbc.query("select id, name, status from platform.clinic where id = ?", { rs, _ -> overview(rs) }, id).firstOrNull()

    override fun listClinicAdmins(clinicId: UUID): List<ClinicAdmin> = jdbc.query(
        """select distinct m.id, m.status, u.email, u.name
           from platform.membership m
             join platform.app_user u on u.id = m.user_id
             join platform.membership_role mr on mr.membership_id = m.id and mr.tenant_id = m.tenant_id
             join platform.role r on r.id = mr.role_id and r.tenant_id = m.tenant_id
           where m.tenant_id = ? and r.name = 'CLINIC_ADMIN'
           order by u.email, m.id""",
        { rs, _ ->
            ClinicAdmin(rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("name"), MembershipStatus.valueOf(rs.getString("status")))
        },
        clinicId,
    )

    private fun overview(rs: java.sql.ResultSet) =
        ClinicOverview(rs.getObject("id", UUID::class.java), rs.getString("name"), ClinicStatus.valueOf(rs.getString("status")))
}
