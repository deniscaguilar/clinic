package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.DatabaseIT
import com.endoacademy.clinic.support.RlsCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Políticas de RLS das tabelas de identidade que não cabem no `TenantIsolationIT` genérico (T2.1, CA10):
 * `clinic`, `app_user` e `platform_admin` não têm `tenant_id`, e `membership` tem leitura própria por `user_sub`.
 */
class IdentityRlsIT : DatabaseIT() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val tx get() = TransactionTemplate(transactionManager)

    private class Person(val id: UUID = UUID.randomUUID()) {
        val sub = "sub-$id"
    }

    private fun clinic(): UUID = UUID.randomUUID().also { id ->
        platform { jdbc.update("insert into platform.clinic (id, name) values (?, 'Clínica')", id) }
    }

    private fun person(): Person = Person().also { p ->
        platform { jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Pessoa')", p.id, p.sub, "${p.id}@example.test") }
    }

    private fun link(clinic: UUID, person: Person, status: String = "ACTIVE") = platform {
        jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, ?, ?)", UUID.randomUUID(), clinic, person.id, status, person.sub)
    }

    private fun platform(block: () -> Unit) = TenantScope.runAsPlatform { tx.executeWithoutResult { block() } }

    private fun ids(sql: String, vararg args: Any): Set<UUID> =
        tx.execute { jdbc.queryForList(sql, UUID::class.java, *args).filterNotNull().toSet() }!!

    @Test
    fun `new tables follow the RLS convention`() {
        assertThat(RlsCatalog.violations(jdbc)).isEmpty()
    }

    @Test
    fun `clinic is invisible without context and visible to its tenant, its members and the platform`() {
        val a = clinic()
        val b = clinic()
        val memberOfA = person().also { link(a, it) }
        val stranger = person()

        assertThat(ids("select id from platform.clinic where id in (?, ?)", a, b)).isEmpty()
        assertThat(TenantScope.runAs(a) { ids("select id from platform.clinic where id in (?, ?)", a, b) }).containsExactly(a)
        assertThat(TenantScope.runAsUser(memberOfA.sub) { ids("select id from platform.clinic where id in (?, ?)", a, b) }).containsExactly(a)
        assertThat(TenantScope.runAsUser(stranger.sub) { ids("select id from platform.clinic where id in (?, ?)", a, b) }).isEmpty()
        assertThat(TenantScope.runAsPlatform { ids("select id from platform.clinic where id in (?, ?)", a, b) }).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `only the platform scope writes clinics`() {
        val a = clinic()
        val member = person().also { link(a, it) }

        assertThatThrownBy { tx.execute { jdbc.update("insert into platform.clinic (id, name) values (?, 'X')", UUID.randomUUID()) } }
            .rootCause().hasMessageContaining("row-level security")
        assertThatThrownBy { TenantScope.runAs(a) { tx.execute { jdbc.update("insert into platform.clinic (id, name) values (?, 'X')", UUID.randomUUID()) } } }
            .rootCause().hasMessageContaining("row-level security")
        assertThat(TenantScope.runAs(a) { tx.execute { jdbc.update("update platform.clinic set status = 'INACTIVE' where id = ?", a) } }).isZero()
        assertThat(TenantScope.runAsUser(member.sub) { tx.execute { jdbc.update("update platform.clinic set status = 'INACTIVE' where id = ?", a) } }).isZero()
        assertThat(TenantScope.runAsPlatform { tx.execute { jdbc.update("update platform.clinic set status = 'INACTIVE' where id = ?", a) } }).isEqualTo(1)
    }

    @Test
    fun `app_user is visible only to itself and to the platform scope`() {
        val me = person()
        val other = person()

        assertThat(ids("select id from platform.app_user where id in (?, ?)", me.id, other.id)).isEmpty()
        assertThat(TenantScope.runAsUser(me.sub) { ids("select id from platform.app_user where id in (?, ?)", me.id, other.id) }).containsExactly(me.id)
        assertThat(TenantScope.runAs(clinic()) { ids("select id from platform.app_user where id in (?, ?)", me.id, other.id) }).isEmpty()
        assertThat(TenantScope.runAsPlatform { ids("select id from platform.app_user where id in (?, ?)", me.id, other.id) }).containsExactlyInAnyOrder(me.id, other.id)
    }

    @Test
    fun `app_user is not written outside the platform scope`() {
        val me = person()
        assertThatThrownBy {
            TenantScope.runAsUser(me.sub) { tx.execute { jdbc.update("insert into platform.app_user (id, email, name) values (?, ?, 'X')", UUID.randomUUID(), "x@example.test") } }
        }.rootCause().hasMessageContaining("row-level security")
        assertThat(TenantScope.runAsUser(me.sub) { tx.execute { jdbc.update("update platform.app_user set name = 'Outro' where id = ?", me.id) } }).isZero()
    }

    @Test
    fun `platform_admin is visible only to itself and to the platform scope`() {
        val admin = person()
        val common = person()
        platform { jdbc.update("insert into platform.platform_admin (user_id) values (?)", admin.id) }

        assertThat(ids("select user_id from platform.platform_admin")).isEmpty()
        assertThat(TenantScope.runAsUser(admin.sub) { ids("select user_id from platform.platform_admin") }).contains(admin.id)
        assertThat(TenantScope.runAsUser(common.sub) { ids("select user_id from platform.platform_admin") }).isEmpty()
        assertThatThrownBy {
            TenantScope.runAsUser(common.sub) { tx.execute { jdbc.update("insert into platform.platform_admin (user_id) values (?)", common.id) } }
        }.rootCause().hasMessageContaining("row-level security")
    }

    @Test
    fun `a user reads only the own memberships, across clinics, and cannot change them`() {
        val a = clinic()
        val b = clinic()
        val me = person()
        val other = person()
        link(a, me)
        link(b, me)
        link(a, other)

        val mine = TenantScope.runAsUser(me.sub) { ids("select tenant_id from platform.membership") }
        assertThat(mine).containsExactlyInAnyOrder(a, b)
        assertThat(TenantScope.runAsUser(me.sub) { ids("select user_id from platform.membership") }).containsExactly(me.id)

        assertThat(TenantScope.runAsUser(me.sub) { tx.execute { jdbc.update("update platform.membership set status = 'ACTIVE'") } }).isZero()
        assertThat(TenantScope.runAsUser(me.sub) { tx.execute { jdbc.update("delete from platform.membership") } }).isZero()
        assertThatThrownBy {
            TenantScope.runAsUser(me.sub) {
                tx.execute { jdbc.update("insert into platform.membership (id, tenant_id, user_id, status) values (?, ?, ?, 'ACTIVE')", UUID.randomUUID(), a, other.id) }
            }
        }.rootCause().hasMessageContaining("row-level security")
    }

    @Test
    fun `the tenant sees the memberships of its clinic only and the platform sees all`() {
        val a = clinic()
        val b = clinic()
        val inA = person().also { link(a, it) }
        val inB = person().also { link(b, it) }

        assertThat(TenantScope.runAs(a) { ids("select user_id from platform.membership where user_id in (?, ?)", inA.id, inB.id) }).containsExactly(inA.id)
        assertThat(TenantScope.runAsPlatform { ids("select user_id from platform.membership where user_id in (?, ?)", inA.id, inB.id) })
            .containsExactlyInAnyOrder(inA.id, inB.id)
    }

    @Test
    fun `roles cannot be linked across clinics`() {
        val a = clinic()
        val b = clinic()
        val roleOfB = UUID.randomUUID()
        TenantScope.runAs(b) { tx.executeWithoutResult { jdbc.update("insert into platform.role (id, tenant_id, name) values (?, ?, 'R')", roleOfB, b) } }

        assertThatThrownBy {
            TenantScope.runAs(a) {
                tx.executeWithoutResult { jdbc.update("insert into platform.role_permission (tenant_id, role_id, permission) values (?, ?, 'VIEW_PATIENTS')", a, roleOfB) }
            }
        }.rootCause().hasMessageContaining("violates foreign key")
    }

    @Test
    fun `a clinic cannot have two roles with the same name`() {
        val a = clinic()
        TenantScope.runAs(a) { tx.executeWithoutResult { jdbc.update("insert into platform.role (id, tenant_id, name) values (?, ?, 'DOCTOR')", UUID.randomUUID(), a) } }
        assertThatThrownBy {
            TenantScope.runAs(a) { tx.executeWithoutResult { jdbc.update("insert into platform.role (id, tenant_id, name) values (?, ?, 'DOCTOR')", UUID.randomUUID(), a) } }
        }.rootCause().hasMessageContaining("duplicate key")
    }

    @Test
    fun `a clinic reads the users that belong to it and no others`() {
        val a = clinic()
        val b = clinic()
        val inA = person().also { link(a, it) }
        val inB = person().also { link(b, it) }
        val nowhere = person()

        val seen = TenantScope.runAs(a) { ids("select id from platform.app_user where id in (?, ?, ?)", inA.id, inB.id, nowhere.id) }
        assertThat(seen).containsExactly(inA.id)
    }

    @Test
    fun `inside a clinic a user can be created only without a sub`() {
        val a = clinic()
        val email = "novo-${UUID.randomUUID()}@example.test"

        assertThat(TenantScope.runAs(a) { tx.execute { jdbc.update("insert into platform.app_user (id, email, name) values (?, ?, 'Novo')", UUID.randomUUID(), email) } }).isEqualTo(1)
        assertThatThrownBy {
            TenantScope.runAs(a) { tx.execute { jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, 'sub-forjado', ?, 'X')", UUID.randomUUID(), "outro-$email") } }
        }.rootCause().hasMessageContaining("row-level security")
        assertThatThrownBy {
            tx.execute { jdbc.update("insert into platform.app_user (id, email, name) values (?, ?, 'X')", UUID.randomUUID(), "sem-contexto-$email") }
        }.rootCause().hasMessageContaining("row-level security")
        assertThatThrownBy {
            TenantScope.runAsUser("sub-qualquer") { tx.execute { jdbc.update("insert into platform.app_user (id, email, name) values (?, ?, 'X')", UUID.randomUUID(), "so-sub-$email") } }
        }.rootCause().hasMessageContaining("row-level security")
    }

    @Test
    fun `creating a user whose e-mail exists in another clinic does nothing and reveals nothing`() {
        val a = clinic()
        val b = clinic()
        val existing = person().also { link(b, it) }
        val email = "${existing.id}@example.test"

        val inserted = TenantScope.runAs(a) {
            tx.execute {
                jdbc.update("insert into platform.app_user (id, email, name) values (?, ?, 'Outro nome') on conflict do nothing", UUID.randomUUID(), email)
            }
        }

        assertThat(inserted).isZero()
        assertThat(TenantScope.runAs(a) { ids("select id from platform.app_user where lower(email) = ?", email) }).isEmpty()
        assertThat(TenantScope.runAsPlatform { tx.execute { jdbc.queryForObject("select name from platform.app_user where id = ?", String::class.java, existing.id) } }).isEqualTo("Pessoa")
    }

    @Test
    fun `a clinic sets the sub of its pending user once, and never changes or touches others`() {
        val a = clinic()
        val b = clinic()
        val pending = UUID.randomUUID()
        TenantScope.runAs(a) { tx.executeWithoutResult { jdbc.update("insert into platform.app_user (id, email, name) values (?, ?, 'P')", pending, "$pending@example.test") } }
        platform { jdbc.update("insert into platform.membership (id, tenant_id, user_id, status) values (?, ?, ?, 'PENDING')", UUID.randomUUID(), a, pending) }
        val stranger = person().also { link(b, it) }
        val withLogin = person().also { link(a, it) }

        assertThat(TenantScope.runAs(a) { tx.execute { jdbc.update("update platform.app_user set sub = 'sub-novo' where id = ?", pending) } }).isEqualTo(1)
        assertThat(TenantScope.runAs(a) { tx.execute { jdbc.update("update platform.app_user set sub = 'sub-troca' where id = ?", pending) } }).isZero()
        assertThat(TenantScope.runAs(a) { tx.execute { jdbc.update("update platform.app_user set sub = 'sub-troca' where id = ?", withLogin.id) } }).isZero()
        assertThat(TenantScope.runAs(a) { tx.execute { jdbc.update("update platform.app_user set sub = 'sub-troca' where id = ?", stranger.id) } }).isZero()
        assertThat(TenantScope.runAs(a) { tx.execute { jdbc.update("update platform.app_user set name = 'Renomeado' where id = ?", withLogin.id) } }).isZero()
    }

    @Test
    fun `a pending membership without user_sub is not visible to its user`() {
        val a = clinic()
        val me = person()
        platform { jdbc.update("insert into platform.membership (id, tenant_id, user_id, status) values (?, ?, ?, 'PENDING')", UUID.randomUUID(), a, me.id) }

        assertThat(TenantScope.runAsUser(me.sub) { ids("select tenant_id from platform.membership") }).isEmpty()
        assertThat(TenantScope.runAs(a) { ids("select user_id from platform.membership") }).containsExactly(me.id)
    }

    @Test
    fun `membership user_sub always matches the user sub when both are set`() {
        val a = clinic()
        link(a, person())
        val mismatches = TenantScope.runAsPlatform {
            tx.execute {
                jdbc.queryForObject(
                    "select count(*) from platform.membership m join platform.app_user u on u.id = m.user_id where m.user_sub is not null and m.user_sub is distinct from u.sub",
                    Int::class.java,
                )
            }
        }
        assertThat(mismatches).isZero()
    }
}
