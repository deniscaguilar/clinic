package com.endoacademy.clinic.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Base dos testes de integração com banco: contexto completo sobre o Postgres de teste. */
@SpringBootTest(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/unused",
        "management.otlp.metrics.export.enabled=false",
        "clinic.identity.provider=local",
        "clinic.storage.provider=memory",
        "clinic.notifications.email.provider=memory",
        "clinic.notifications.worker.enabled=false",
    ],
)
abstract class DatabaseIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = PostgresContainer.register(registry)
    }
}
