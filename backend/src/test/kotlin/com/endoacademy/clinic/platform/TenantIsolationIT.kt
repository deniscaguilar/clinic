package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.DatabaseIT
import com.endoacademy.clinic.support.PostgresContainer
import com.endoacademy.clinic.support.RlsCatalog
import com.endoacademy.clinic.support.TenantFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.util.UUID

/**
 * Isolamento entre clínicas, genérico (T1.8, CA10): roda para **cada tabela com `tenant_id`** achada no catálogo.
 * Uma tabela sem inserter em `TenantFixtures` reprova, para que ninguém crie tabela sem teste de isolamento.
 */
class TenantIsolationIT : DatabaseIT() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val tx get() = TransactionTemplate(transactionManager)

    companion object {
        private const val PROBE = "platform.zz_isolation_probe"

        private fun migrator() =
            DriverManager.getConnection(PostgresContainer.instance.jdbcUrl, "clinic_migrator", PostgresContainer.MIGRATOR_PASSWORD)

        /**
         * Tabela de exemplo para exercitar o teste genérico enquanto não há tabela de negócio. É criada depois que o
         * contexto subiu (e as migrações rodaram), por isso a primeira chamada do `@TestFactory` a garante: um
         * `@BeforeAll` estático rodaria antes das migrações quando esta é a primeira classe da execução.
         */
        @Volatile
        private var probeReady = false

        @Synchronized
        fun ensureProbe() {
            if (probeReady) return
            migrator().use { c ->
                c.createStatement().use {
                    it.execute("create table if not exists $PROBE (id uuid primary key, tenant_id uuid not null)")
                    if (!policyExists(it)) it.execute("select platform.apply_tenant_rls('platform', 'zz_isolation_probe')")
                    it.execute("grant select, insert, update, delete on $PROBE to clinic_app")
                }
            }
            TenantFixtures.inserters[PROBE] = TenantFixtures.RowInserter { template, tenant ->
                template.update("insert into $PROBE (id, tenant_id) values (?, ?)", UUID.randomUUID(), tenant)
            }
            probeReady = true
        }

        private fun policyExists(statement: java.sql.Statement): Boolean =
            statement.executeQuery("select count(*) from pg_policy where polrelid = '$PROBE'::regclass").use { rs -> rs.next() && rs.getInt(1) > 0 }

        @JvmStatic
        @AfterAll
        fun dropProbe() {
            TenantFixtures.inserters.remove(PROBE)
            probeReady = false
            migrator().use { c -> c.createStatement().use { it.execute("drop table if exists $PROBE") } }
        }
    }

    private fun tenantTables(): List<String> = jdbc.queryForList(
        """select n.nspname || '.' || c.relname
           from pg_class c join pg_namespace n on n.oid = c.relnamespace
           join pg_attribute a on a.attrelid = c.oid and a.attname = 'tenant_id' and a.attnum > 0 and not a.attisdropped
           where c.relkind in ('r', 'p') and n.nspname = any (?) order by 1""",
        String::class.java,
        RlsCatalog.schemas.toTypedArray() as Any,
    ).filterNotNull()

    private fun count(table: String, where: String = "true", vararg args: Any) =
        tx.execute { jdbc.queryForObject("select count(*) from $table where $where", Int::class.java, *args) }!!

    @TestFactory
    fun `every table with tenant_id is isolated between clinics`(): List<DynamicTest> {
        ensureProbe()
        val tables = tenantTables()
        assertThat(tables).describedAs("tabelas com tenant_id no catálogo").isNotEmpty()
        return tables.flatMap { table ->
            val inserter = TenantFixtures.inserters[table]
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            listOf(
                dynamicTest("$table: tem inserter de isolamento registrado") {
                    assertThat(inserter).describedAs("registre o inserter de $table em TenantFixtures").isNotNull()
                },
                dynamicTest("$table: sem contexto não vê nem altera nada") {
                    seed(inserter!!, a, b)
                    assertThat(count(table)).isZero()
                    if (table in TenantFixtures.appendOnlyTables) {
                        assertThatThrownBy { tx.execute { jdbc.update("update $table set tenant_id = tenant_id") } }
                            .rootCause().hasMessageContaining("permission denied")
                        assertThatThrownBy { tx.execute { jdbc.update("delete from $table") } }
                            .rootCause().hasMessageContaining("permission denied")
                    } else {
                        if (table in TenantFixtures.noUpdateTables) {
                            assertThatThrownBy { tx.execute { jdbc.update("update $table set tenant_id = tenant_id") } }
                                .rootCause().hasMessageContaining("permission denied")
                        } else {
                            assertThat(tx.execute { jdbc.update("update $table set tenant_id = tenant_id") }).isZero()
                        }
                        if (table in TenantFixtures.noDeleteTables) {
                            assertThatThrownBy { tx.execute { jdbc.update("delete from $table") } }.rootCause().hasMessageContaining("permission denied")
                        } else {
                            assertThat(tx.execute { jdbc.update("delete from $table") }).isZero()
                        }
                    }
                },
                dynamicTest("$table: cada clínica vê só as suas linhas") {
                    seed(inserter!!, a, b)
                    assertThat(TenantScope.runAs(a) { count(table, "tenant_id = ?", b) }).isZero()
                    assertThat(TenantScope.runAs(a) { count(table, "tenant_id = ?", a) }).isGreaterThan(0)
                    assertThat(TenantScope.runAs(b) { count(table, "tenant_id = ?", a) }).isZero()
                },
                dynamicTest("$table: uma clínica não altera nem apaga linha de outra") {
                    seed(inserter!!, a, b)
                    if (table in TenantFixtures.appendOnlyTables) {
                        assertThatThrownBy { TenantScope.runAs(b) { tx.execute { jdbc.update("delete from $table where tenant_id = ?", a) } } }
                            .rootCause().hasMessageContaining("permission denied")
                    } else {
                        if (table in TenantFixtures.noUpdateTables) {
                            assertThatThrownBy { TenantScope.runAs(b) { tx.execute { jdbc.update("update $table set tenant_id = tenant_id where tenant_id = ?", a) } } }
                                .rootCause().hasMessageContaining("permission denied")
                        } else {
                            assertThat(TenantScope.runAs(b) { tx.execute { jdbc.update("update $table set tenant_id = tenant_id where tenant_id = ?", a) } }).isZero()
                        }
                        if (table in TenantFixtures.noDeleteTables) {
                            assertThatThrownBy { TenantScope.runAs(b) { tx.execute { jdbc.update("delete from $table where tenant_id = ?", a) } } }
                                .rootCause().hasMessageContaining("permission denied")
                        } else {
                            assertThat(TenantScope.runAs(b) { tx.execute { jdbc.update("delete from $table where tenant_id = ?", a) } }).isZero()
                        }
                    }
                    assertThat(TenantScope.runAs(a) { count(table, "tenant_id = ?", a) }).isGreaterThan(0)
                },
                dynamicTest("$table: WITH CHECK recusa gravar para outra clínica") {
                    assertThatThrownBy { TenantScope.runAs(a) { tx.execute { inserter!!.insert(jdbc, b) } } }
                        .rootCause().hasMessageContaining("row-level security")
                    if (table !in TenantFixtures.appendOnlyTables && table !in TenantFixtures.noUpdateTables) {
                        assertThatThrownBy { TenantScope.runAs(a) { tx.execute { jdbc.update("update $table set tenant_id = ?", b) } } }
                            .describedAs("mover linha de A para B")
                            .rootCause().hasMessageContaining("row-level security")
                    }
                },
                dynamicTest("$table: escopo platform não abre a tabela, salvo as previstas na ADR-0010") {
                    seed(inserter!!, a, b)
                    val platformCount = TenantScope.runAsPlatform { count(table) }
                    if (table in TenantFixtures.platformScopeTables) {
                        assertThat(platformCount).isGreaterThanOrEqualTo(0)
                    } else {
                        assertThat(platformCount).describedAs("escopo platform não pode abrir $table").isZero()
                    }
                },
            )
        }
    }

    private fun seed(inserter: TenantFixtures.RowInserter, vararg tenants: UUID) = tenants.forEach { tenant ->
        TenantScope.runAs(tenant) { tx.execute { inserter.insert(jdbc, tenant) } }
    }
}
