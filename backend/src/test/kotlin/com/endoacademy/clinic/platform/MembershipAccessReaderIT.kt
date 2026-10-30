package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.platform.application.MembershipAccessReader
import com.endoacademy.clinic.support.DatabaseIT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Adaptador real do `MembershipAccessReader` sobre o banco, com RLS (T2.1; base de CA11). */
class MembershipAccessReaderIT : DatabaseIT() {

    @Autowired
    lateinit var reader: MembershipAccessReader

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val tx get() = TransactionTemplate(transactionManager)

    private fun clinic(status: String = "ACTIVE"): UUID = UUID.randomUUID().also { id ->
        TenantScope.runAsPlatform { tx.executeWithoutResult { jdbc.update("insert into platform.clinic (id, name, status) values (?, 'Clínica', ?)", id, status) } }
    }

    private fun user(): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val sub = "sub-$id"
        TenantScope.runAsPlatform {
            tx.executeWithoutResult { jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Pessoa')", id, sub, "$id@example.test") }
        }
        return id to sub
    }

    /** Cria o vínculo e um perfil com as permissões dadas; devolve o id do vínculo. */
    private fun membership(clinic: UUID, userId: UUID, status: String, vararg rolePermissions: Set<String>): UUID {
        val membershipId = UUID.randomUUID()
        TenantScope.runAsPlatform {
            tx.executeWithoutResult { jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, ?, ?)", membershipId, clinic, userId, status, "sub-$userId") }
        }
        TenantScope.runAs(clinic) {
            tx.executeWithoutResult {
                rolePermissions.forEach { permissions ->
                    val roleId = UUID.randomUUID()
                    jdbc.update("insert into platform.role (id, tenant_id, name) values (?, ?, ?)", roleId, clinic, "role-$roleId")
                    permissions.forEach { jdbc.update("insert into platform.role_permission (tenant_id, role_id, permission) values (?, ?, ?)", clinic, roleId, it) }
                    jdbc.update("insert into platform.membership_role (tenant_id, membership_id, role_id) values (?, ?, ?)", clinic, membershipId, roleId)
                }
            }
        }
        return membershipId
    }

    @Test
    fun `active member gets the union of the permissions of all the roles`() {
        val clinic = clinic()
        val (userId, sub) = user()
        membership(clinic, userId, "ACTIVE", setOf("VIEW_PATIENTS", "MANAGE_USERS"), setOf("VIEW_PATIENTS", "MANAGE_ROLES"))

        val access = reader.find(sub, clinic)!!

        assertThat(access.userId).isEqualTo(userId)
        assertThat(access.membershipActive).isTrue()
        assertThat(access.clinicActive).isTrue()
        assertThat(access.permissions).containsExactlyInAnyOrder("VIEW_PATIENTS", "MANAGE_USERS", "MANAGE_ROLES")
    }

    @Test
    fun `members of the same clinic do not receive each other's permissions`() {
        val clinic = clinic()
        val (doctorId, doctorSub) = user()
        val (staffId, staffSub) = user()
        membership(clinic, doctorId, "ACTIVE", setOf("VIEW_PATIENTS"))
        membership(clinic, staffId, "ACTIVE", setOf("MANAGE_PATIENTS"))

        assertThat(reader.find(doctorSub, clinic)!!.permissions).containsExactly("VIEW_PATIENTS")
        assertThat(reader.find(staffSub, clinic)!!.permissions).containsExactly("MANAGE_PATIENTS")
    }

    @Test
    fun `user without membership in the clinic gets nothing`() {
        val clinic = clinic()
        val (_, sub) = user()
        assertThat(reader.find(sub, clinic)).isNull()
    }

    @Test
    fun `unknown sub gets nothing even for an existing clinic`() {
        val clinic = clinic()
        val (userId, _) = user()
        membership(clinic, userId, "ACTIVE", setOf("VIEW_PATIENTS"))
        assertThat(reader.find("sub-unknown", clinic)).isNull()
    }

    @Test
    fun `member of another clinic does not see this one`() {
        val mine = clinic()
        val other = clinic()
        val (userId, sub) = user()
        membership(mine, userId, "ACTIVE", setOf("VIEW_PATIENTS"))
        assertThat(reader.find(sub, other)).isNull()
    }

    @Test
    fun `user in two clinics gets only the permissions of the chosen one`() {
        val a = clinic()
        val b = clinic()
        val (userId, sub) = user()
        membership(a, userId, "ACTIVE", setOf("VIEW_PATIENTS"))
        membership(b, userId, "ACTIVE", setOf("MANAGE_USERS"))

        assertThat(reader.find(sub, a)!!.permissions).containsExactly("VIEW_PATIENTS")
        assertThat(reader.find(sub, b)!!.permissions).containsExactly("MANAGE_USERS")
    }

    @Test
    fun `inactive or pending membership is reported as not active and carries no permissions`() {
        val clinic = clinic()
        val (inactiveId, inactiveSub) = user()
        val (pendingId, pendingSub) = user()
        membership(clinic, inactiveId, "INACTIVE", setOf("VIEW_PATIENTS"))
        membership(clinic, pendingId, "PENDING", setOf("VIEW_PATIENTS"))

        assertThat(reader.find(inactiveSub, clinic)).satisfies({ assertThat(it!!.membershipActive).isFalse(); assertThat(it.permissions).isEmpty() })
        assertThat(reader.find(pendingSub, clinic)).satisfies({ assertThat(it!!.membershipActive).isFalse(); assertThat(it.permissions).isEmpty() })
    }

    @Test
    fun `inactive clinic is reported as not active and carries no permissions`() {
        val clinic = clinic("INACTIVE")
        val (userId, sub) = user()
        membership(clinic, userId, "ACTIVE", setOf("VIEW_PATIENTS"))

        val access = reader.find(sub, clinic)!!

        assertThat(access.clinicActive).isFalse()
        assertThat(access.permissions).isEmpty()
    }
}
