package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.DatabaseIT
import com.endoacademy.clinic.support.PostgresContainer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.util.UUID

/** Auditoria append-only (T1.12, CA8). */
class AuditTrailIT : DatabaseIT() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var auditTrail: AuditTrail

    @Autowired
    lateinit var authorizer: Authorizer

    private val tx get() = TransactionTemplate(transactionManager)
    private val clinicA = UUID.randomUUID()
    private val clinicB = UUID.randomUUID()
    private fun user(clinic: UUID, vararg permissions: String) = CurrentUser(UUID.randomUUID(), "sub-${UUID.randomUUID()}", clinic, permissions.toSet())

    companion object {
        private fun migrator() =
            DriverManager.getConnection(PostgresContainer.instance.jdbcUrl, "clinic_migrator", PostgresContainer.MIGRATOR_PASSWORD)

        private fun superuser() = DriverManager.getConnection(PostgresContainer.instance.jdbcUrl, "clinic", "clinic-test")

        @JvmStatic
        @BeforeAll
        fun createProbe() {
            migrator().use { c ->
                c.createStatement().use {
                    it.execute("create table platform.zz_audit_probe (id uuid primary key, tenant_id uuid not null)")
                    it.execute("select platform.apply_tenant_rls('platform', 'zz_audit_probe')")
                    it.execute("grant select, insert on platform.zz_audit_probe to clinic_app")
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun dropProbe() {
            migrator().use { c -> c.createStatement().use { it.execute("drop table platform.zz_audit_probe") } }
        }
    }

    private fun eventsOf(clinic: UUID, action: String) = TenantScope.runAs(clinic) {
        tx.execute { jdbc.queryForList("select outcome from platform.audit_event where action = ?", String::class.java, action) }!!
    }

    @Test
    fun `a successful event is recorded with who, when, what and the outcome`() {
        val actor = user(clinicA)
        TenantScope.runAs(clinicA) {
            tx.executeWithoutResult {
                auditTrail.record(actor, AuditEvent("PATIENT_UPDATED", "PATIENT", "42", details = setOf("cpf", "address")))
            }
        }
        val row = TenantScope.runAs(clinicA) {
            tx.execute {
                jdbc.queryForMap("select * from platform.audit_event where action = 'PATIENT_UPDATED'")
            }!!
        }
        assertThat(row["tenant_id"]).isEqualTo(clinicA)
        assertThat(row["user_id"]).isEqualTo(actor.userId)
        assertThat(row["entity_type"]).isEqualTo("PATIENT")
        assertThat(row["entity_id"]).isEqualTo("42")
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
        assertThat(row["channel"]).isEqualTo("API")
        assertThat(row["occurred_at"]).isNotNull()
        assertThat(row["details"].toString()).isEqualTo("[\"address\", \"cpf\"]")
    }

    @Test
    fun `the application connection cannot update, delete or truncate`() {
        TenantScope.runAs(clinicA) { tx.executeWithoutResult { auditTrail.record(user(clinicA), AuditEvent("KEEP", "X")) } }
        listOf("update platform.audit_event set action = 'X'", "delete from platform.audit_event", "truncate platform.audit_event").forEach { sql ->
            assertThatThrownBy { TenantScope.runAs(clinicA) { tx.execute { jdbc.execute(sql) } } }
                .describedAs(sql)
                .rootCause().hasMessageContaining("permission denied")
        }
    }

    @Test
    fun `even a privileged role is refused by the trigger`() {
        TenantScope.runAs(clinicB) { tx.executeWithoutResult { auditTrail.record(user(clinicB), AuditEvent("PRIVILEGED", "X")) } }
        superuser().use { c ->
            c.createStatement().use { s ->
                listOf("update platform.audit_event set action = 'X'", "delete from platform.audit_event", "truncate platform.audit_event").forEach { sql ->
                    assertThatThrownBy { s.execute(sql) }.describedAs(sql).hasMessageContaining("append-only")
                }
            }
        }
    }

    @Test
    fun `a reverted action still leaves its DENIED record`() {
        val actor = user(clinicA, "VIEW_PATIENTS")
        assertThatThrownBy {
            TenantScope.runAs(clinicA) {
                tx.executeWithoutResult {
                    auditTrail.record(actor, AuditEvent("WILL_BE_REVERTED", "X"))
                    authorizer.require(actor, "MANAGE_USERS")
                }
            }
        }.isInstanceOf(PermissionDeniedException::class.java)

        assertThat(eventsOf(clinicA, "WILL_BE_REVERTED")).describedAs("o sucesso da transação principal foi desfeito").isEmpty()
        assertThat(eventsOf(clinicA, "ACCESS_DENIED")).containsExactly("DENIED")
    }

    @Test
    fun `a FAILURE is recorded even when the main transaction rolls back`() {
        assertThatThrownBy {
            TenantScope.runAs(clinicA) {
                tx.executeWithoutResult {
                    auditTrail.record(user(clinicA), AuditEvent("BROKEN_ACTION", "X", outcome = AuditOutcome.FAILURE))
                    error("falha de negócio")
                }
            }
        }.hasMessageContaining("falha de negócio")

        assertThat(eventsOf(clinicA, "BROKEN_ACTION")).containsExactly("FAILURE")
    }

    @Test
    fun `if the audit fails the action is undone`() {
        val id = UUID.randomUUID()
        // O usuário diz estar na clínica B, mas a transação roda no contexto da A: a RLS recusa o evento.
        assertThatThrownBy {
            TenantScope.runAs(clinicA) {
                tx.executeWithoutResult {
                    jdbc.update("insert into platform.zz_audit_probe (id, tenant_id) values (?, ?)", id, clinicA)
                    auditTrail.record(user(clinicB), AuditEvent("PATIENT_CREATED", "PATIENT"))
                }
            }
        }.rootCause().hasMessageContaining("row-level security")

        val rows = TenantScope.runAs(clinicA) {
            tx.execute { jdbc.queryForObject("select count(*) from platform.zz_audit_probe where id = ?", Int::class.java, id) }
        }
        assertThat(rows).describedAs("a ação foi desfeita junto com a auditoria").isZero()
    }

    @Test
    fun `each clinic reads only its own events, and the platform scope reads all`() {
        TenantScope.runAs(clinicA) { tx.executeWithoutResult { auditTrail.record(user(clinicA), AuditEvent("ISOLATED_A", "X")) } }
        TenantScope.runAs(clinicB) { tx.executeWithoutResult { auditTrail.record(user(clinicB), AuditEvent("ISOLATED_B", "X")) } }

        assertThat(eventsOf(clinicA, "ISOLATED_A")).hasSize(1)
        assertThat(eventsOf(clinicA, "ISOLATED_B")).isEmpty()
        assertThat(eventsOf(clinicB, "ISOLATED_B")).hasSize(1)
        val seenByPlatform = TenantScope.runAsPlatform {
            tx.execute { jdbc.queryForList("select action from platform.audit_event where action like 'ISOLATED_%'", String::class.java) }!!
        }
        assertThat(seenByPlatform).containsExactlyInAnyOrder("ISOLATED_A", "ISOLATED_B")
    }

    @Test
    fun `an event without a clinic is stored with a null tenant and only the platform scope sees it`() {
        tx.executeWithoutResult { auditTrail.record(null, AuditEvent("LOGIN_WITHOUT_CLINIC", "SESSION")) }

        assertThat(eventsOf(clinicA, "LOGIN_WITHOUT_CLINIC")).isEmpty()
        val seen = TenantScope.runAsPlatform {
            tx.execute { jdbc.queryForList("select tenant_id from platform.audit_event where action = 'LOGIN_WITHOUT_CLINIC'") }!!
        }
        assertThat(seen).hasSize(1)
        assertThat(seen.single()["tenant_id"]).isNull()
    }
}
