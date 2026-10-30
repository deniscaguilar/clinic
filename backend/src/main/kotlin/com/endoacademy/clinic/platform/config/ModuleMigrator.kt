package com.endoacademy.clinic.platform.config

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Roda uma instância do Flyway por módulo, na ordem dada. Cada uma usa o schema do módulo
 * (que o ambiente já criou, com dono clinic_migrator) e guarda o histórico nele.
 */
class ModuleMigrator(private val dataSource: DataSource, private val modules: List<String>) {

    fun migrate() {
        modules.forEach { module ->
            Flyway.configure()
                .dataSource(dataSource)
                .schemas(module)
                .defaultSchema(module)
                .createSchemas(false)
                .locations("classpath:db/migration/$module")
                .table("flyway_schema_history")
                .failOnMissingLocations(false)
                .load()
                .migrate()
        }
    }
}
