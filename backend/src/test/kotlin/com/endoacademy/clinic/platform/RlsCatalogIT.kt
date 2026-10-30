package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.DatabaseIT
import com.endoacademy.clinic.support.PostgresContainer
import com.endoacademy.clinic.support.RlsCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.util.UUID

/** Convenção de RLS e teste de catálogo (T1.7, CA10). */
class RlsCatalogIT : DatabaseIT() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    /** Roda DDL como migrator e apaga a tabela de teste ao fim, mesmo se o teste falhar. */
    private fun withTable(name: String, ddl: List<String>, block: () -> Unit) {
        fun migrator(sql: List<String>) =
            DriverManager.getConnection(PostgresContainer.instance.jdbcUrl, "clinic_migrator", PostgresContainer.MIGRATOR_PASSWORD).use { c ->
                c.createStatement().use { s -> sql.forEach { s.execute(it) } }
            }
        migrator(ddl)
        try {
            block()
        } finally {
            migrator(listOf("drop table if exists platform.$name"))
        }
    }

    @Test
    fun `schemas created by the migrations follow the convention`() {
        assertThat(RlsCatalog.violations(jdbc)).isEmpty()
    }

    @Test
    fun `table with tenant_id and no RLS fails the catalog test`() =
        withTable("zz_no_rls", listOf("create table platform.zz_no_rls (id uuid primary key, tenant_id uuid not null)")) {
            assertThat(RlsCatalog.violations(jdbc)).contains(
                "platform.zz_no_rls: RLS não habilitada",
                "platform.zz_no_rls: RLS não forçada (FORCE ROW LEVEL SECURITY)",
                "platform.zz_no_rls: sem política de RLS",
            )
        }

    @Test
    fun `RLS enabled but not forced fails the catalog test`() = withTable(
        "zz_not_forced",
        listOf(
            "create table platform.zz_not_forced (id uuid primary key, tenant_id uuid not null)",
            "alter table platform.zz_not_forced enable row level security",
            "create policy p on platform.zz_not_forced using (true)",
        ),
    ) {
        assertThat(RlsCatalog.violations(jdbc)).containsExactly("platform.zz_not_forced: RLS não forçada (FORCE ROW LEVEL SECURITY)")
    }

    @Test
    fun `RLS enabled and forced but without policy fails the catalog test`() = withTable(
        "zz_no_policy",
        listOf(
            "create table platform.zz_no_policy (id uuid primary key, tenant_id uuid not null)",
            "alter table platform.zz_no_policy enable row level security",
            "alter table platform.zz_no_policy force row level security",
        ),
    ) {
        assertThat(RlsCatalog.violations(jdbc)).containsExactly("platform.zz_no_policy: sem política de RLS")
    }

    @Test
    fun `table without tenant_id must be declared global`() =
        withTable("zz_forgot_tenant", listOf("create table platform.zz_forgot_tenant (id uuid primary key)")) {
            assertThat(RlsCatalog.violations(jdbc))
                .containsExactly("platform.zz_forgot_tenant: sem tenant_id e fora da lista de tabelas globais")
        }

    @Test
    fun `helper function makes a table compliant and isolated`() = withTable(
        "zz_with_helper",
        listOf(
            "create table platform.zz_with_helper (id uuid primary key, tenant_id uuid not null)",
            "select platform.apply_tenant_rls('platform', 'zz_with_helper')",
            "grant select, insert on platform.zz_with_helper to clinic_app",
        ),
    ) {
        assertThat(RlsCatalog.violations(jdbc)).isEmpty()

        val tenantA = UUID.randomUUID()
        val tenantB = UUID.randomUUID()
        val tx = TransactionTemplate(transactionManager)
        TenantScope.runAs(tenantA) {
            tx.execute { jdbc.update("insert into platform.zz_with_helper values (?, ?)", UUID.randomUUID(), tenantA) }
        }
        assertThat(TenantScope.runAs(tenantA) { tx.execute { jdbc.queryForObject("select count(*) from platform.zz_with_helper", Int::class.java) } }).isEqualTo(1)
        assertThat(TenantScope.runAs(tenantB) { tx.execute { jdbc.queryForObject("select count(*) from platform.zz_with_helper", Int::class.java) } }).isZero()
        assertThat(tx.execute { jdbc.queryForObject("select count(*) from platform.zz_with_helper", Int::class.java) }).isZero()
    }

    @Test
    fun `application role cannot call the helper function`() {
        assertThatThrownBy { jdbc.queryForObject("select platform.apply_tenant_rls('platform', 'flyway_schema_history')", String::class.java) }
            .rootCause().hasMessageContaining("permission denied")
    }
}
