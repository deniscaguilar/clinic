package com.endoacademy.clinic.platform

import com.endoacademy.clinic.support.DatabaseIT
import com.endoacademy.clinic.support.PostgresContainer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.DriverManager

class ModuleMigrationIT : DatabaseIT() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val modules = listOf("platform", "registry", "notifications", "eventing")

    @Test
    fun `application connects as clinic_app`() {
        assertThat(jdbc.queryForObject("select current_user", String::class.java)).isEqualTo("clinic_app")
    }

    @Test
    fun `each module keeps its Flyway history in its own schema, owned by the migrator`() {
        modules.forEach { module ->
            val owner = jdbc.queryForList(
                "select tableowner from pg_tables where schemaname = ? and tablename = 'flyway_schema_history'",
                String::class.java,
                module,
            )
            assertThat(owner).describedAs("histórico do módulo $module").containsExactly("clinic_migrator")
        }
    }

    @Test
    fun `no Flyway history leaks into public`() {
        val count = jdbc.queryForObject(
            "select count(*) from pg_tables where schemaname = 'public' and tablename = 'flyway_schema_history'",
            Int::class.java,
        )
        assertThat(count).isZero()
    }

    @Test
    fun `application role cannot create objects in module schemas`() {
        modules.forEach { module ->
            assertThatThrownBy { jdbc.execute("create table $module.should_fail (id int)") }
                .describedAs("clinic_app criando tabela em $module")
                .rootCause().hasMessageContaining("permission denied")
        }
    }

    @Test
    fun `migrator can create objects but application is not owner of them`() {
        val container = PostgresContainer.instance
        DriverManager.getConnection(container.jdbcUrl, "clinic_migrator", PostgresContainer.MIGRATOR_PASSWORD).use { connection ->
            connection.createStatement().use {
                it.execute("create table platform.probe (id int)")
                it.execute("grant select on platform.probe to clinic_app")
            }
        }
        try {
            assertThat(jdbc.queryForObject("select count(*) from platform.probe", Int::class.java)).isZero()
            assertThatThrownBy { jdbc.execute("drop table platform.probe") }
                .rootCause().hasMessageContaining("must be owner")
        } finally {
            DriverManager.getConnection(container.jdbcUrl, "clinic_migrator", PostgresContainer.MIGRATOR_PASSWORD).use {
                it.createStatement().use { s -> s.execute("drop table platform.probe") }
            }
        }
    }
}
