package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.DatabaseIT
import com.endoacademy.clinic.support.PostgresContainer
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Prova de conceito do contexto de tenant (T1.6, ADR-0010): RLS por transação, sem vazamento no pool. */
class TenantContextIT : DatabaseIT() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @PersistenceContext
    lateinit var entityManager: EntityManager

    private val tx get() = TransactionTemplate(transactionManager)
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()

    companion object {
        private fun migrator() =
            DriverManager.getConnection(PostgresContainer.instance.jdbcUrl, "clinic_migrator", PostgresContainer.MIGRATOR_PASSWORD)

        @JvmStatic
        @BeforeAll
        fun createProbeTable() {
            migrator().use { c ->
                c.createStatement().use {
                    it.execute("create table platform.tenant_probe (id uuid primary key, tenant_id uuid not null)")
                    it.execute("select platform.apply_tenant_rls('platform', 'tenant_probe')")
                    it.execute("grant select, insert on platform.tenant_probe to clinic_app")
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun dropProbeTable() {
            migrator().use { c -> c.createStatement().use { it.execute("drop table platform.tenant_probe") } }
        }
    }

    private fun insertRow(tenant: UUID) = TenantScope.runAs(tenant) {
        tx.execute {
            jdbc.update("insert into platform.tenant_probe (id, tenant_id) values (?, ?)", UUID.randomUUID(), tenant)
        }
    }

    private fun countVisible() = tx.execute { jdbc.queryForObject("select count(*) from platform.tenant_probe", Int::class.java) }!!

    private fun contextSetting(name: String) =
        tx.execute { jdbc.queryForObject("select coalesce(current_setting(?, true), '')", String::class.java, name) }!!

    @Test
    fun `without context no rows are visible and no row can be written`() {
        insertRow(tenantA)
        assertThat(countVisible()).isZero()
        assertThatThrownBy {
            tx.execute { jdbc.update("insert into platform.tenant_probe (id, tenant_id) values (?, ?)", UUID.randomUUID(), tenantA) }
        }.rootCause().hasMessageContaining("row-level security")
    }

    @Test
    fun `each tenant sees only its own rows`() {
        repeat(3) { insertRow(tenantA) }
        repeat(2) { insertRow(tenantB) }
        assertThat(TenantScope.runAs(tenantA) { countVisible() }).isEqualTo(3)
        assertThat(TenantScope.runAs(tenantB) { countVisible() }).isEqualTo(2)
        assertThat(countVisible()).isZero()
    }

    @Test
    fun `a tenant cannot insert a row for another tenant`() {
        assertThatThrownBy {
            TenantScope.runAs(tenantA) {
                tx.execute { jdbc.update("insert into platform.tenant_probe (id, tenant_id) values (?, ?)", UUID.randomUUID(), tenantB) }
            }
        }.rootCause().hasMessageContaining("row-level security")
    }

    @Test
    fun `context values are set for the transaction and gone after it`() {
        TenantScope.runAs(tenantA) { assertThat(contextSetting("app.tenant_id")).isEqualTo(tenantA.toString()) }
        TenantScope.runAsUser("sub-123") { assertThat(contextSetting("app.user_sub")).isEqualTo("sub-123") }
        TenantScope.runAsPlatform { assertThat(contextSetting("app.scope")).isEqualTo("platform") }
        // Fora do bloco, a mesma conexão do pool volta sem nada.
        assertThat(contextSetting("app.tenant_id")).isEmpty()
        assertThat(contextSetting("app.user_sub")).isEmpty()
        assertThat(contextSetting("app.scope")).isEmpty()
    }

    @Test
    fun `Hibernate session sees the same tenant context as JDBC`() {
        insertRow(tenantA)
        insertRow(tenantB)
        fun hibernateCount() = tx.execute {
            (entityManager.createNativeQuery("select count(*) from platform.tenant_probe").singleResult as Number).toInt()
        }!!
        fun hibernateSetting() = tx.execute {
            entityManager.createNativeQuery("select coalesce(current_setting('app.tenant_id', true), '')").singleResult as String
        }!!
        assertThat(TenantScope.runAs(tenantA) { hibernateSetting() }).isEqualTo(tenantA.toString())
        assertThat(TenantScope.runAs(tenantA) { hibernateCount() }).isEqualTo(TenantScope.runAs(tenantA) { countVisible() })
        assertThat(TenantScope.runAs(tenantB) { hibernateCount() }).isEqualTo(TenantScope.runAs(tenantB) { countVisible() })
        assertThat(hibernateCount()).isZero()
        assertThat(hibernateSetting()).isEmpty()
    }

    @Test
    fun `context cannot be changed while a transaction is active`() {
        assertThatThrownBy {
            TenantScope.runAs(tenantA) {
                tx.execute { TenantScope.runAs(tenantB) { } }
            }
        }.hasMessageContaining("não pode mudar")
    }

    @Test
    fun `context does not leak between transactions sharing the pool`() {
        insertRow(tenantA)
        insertRow(tenantB)
        val threads = 8
        val iterations = 150
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val failures = (1..threads).map { seed ->
                pool.submit(Callable {
                    val random = Random(seed)
                    var bad = 0
                    repeat(iterations) {
                        val expected = listOf(tenantA, tenantB, null)[random.nextInt(3)]
                        val seen = if (expected == null) {
                            contextSetting("app.tenant_id") to countVisible()
                        } else {
                            TenantScope.runAs(expected) { contextSetting("app.tenant_id") to countVisible() }
                        }
                        val ok = if (expected == null) seen.first.isEmpty() && seen.second == 0
                        else seen.first == expected.toString() && seen.second >= 1
                        if (!ok) bad++
                    }
                    bad
                })
            }.sumOf { it.get(60, TimeUnit.SECONDS) }
            assertThat(failures).describedAs("transações com contexto errado").isZero()
        } finally {
            pool.shutdownNow()
        }
    }
}
