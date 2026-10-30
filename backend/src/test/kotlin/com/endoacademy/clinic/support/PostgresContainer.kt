package com.endoacademy.clinic.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Path

/**
 * Postgres de teste, criado uma vez por JVM, com a mesma imagem e o mesmo script de papéis do Compose
 * (`docker/postgres/init/01-roles.sh`). Assim o teste parte do mesmo banco do ambiente local.
 */
object PostgresContainer {
    const val MIGRATOR_PASSWORD = "migrator-test"
    const val APP_PASSWORD = "app-test"

    private val initScript: Path = Path.of("..", "docker", "postgres", "init", "01-roles.sh").toAbsolutePath().normalize()

    val instance: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17.11-alpine3.23")
            .withDatabaseName("clinic")
            .withUsername("clinic")
            .withPassword("clinic-test")
            // Cada contexto de teste em cache guarda um pool de até 10 conexões; com dezenas de ITs o padrão (100) acaba.
            .withCommand("postgres", "-c", "max_connections=400")
            .withEnv("CLINIC_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD)
            .withEnv("CLINIC_APP_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(initScript, 0b111_101_101), "/docker-entrypoint-initdb.d/01-roles.sh")
            .also { it.start() }
    }

    fun register(registry: DynamicPropertyRegistry) {
        val url = instance.jdbcUrl
        registry.add("clinic.database.migration.url") { url }
        registry.add("clinic.database.migration.password") { MIGRATOR_PASSWORD }
        registry.add("clinic.database.app.url") { url }
        registry.add("clinic.database.app.password") { APP_PASSWORD }
    }
}
