package com.endoacademy.clinic.platform.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Duas conexões com papéis distintos (ADR-0010): `migration` (clinic_migrator, dono dos schemas, só para o Flyway)
 * e `app` (clinic_app, sem BYPASSRLS e sem ser dono de tabela, usada pela aplicação).
 * `modules` é a ordem de execução das migrações; cada nome é também o schema e a pasta `db/migration/<nome>`.
 */
@ConfigurationProperties("clinic.database")
data class DatabaseProperties(
    val migration: Credentials,
    val app: Credentials,
    val modules: List<String> = listOf("platform", "registry", "notifications", "eventing"),
) {
    data class Credentials(val url: String, val username: String, val password: String)
}
