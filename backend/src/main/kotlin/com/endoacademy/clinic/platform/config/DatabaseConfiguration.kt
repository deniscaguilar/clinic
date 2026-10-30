package com.endoacademy.clinic.platform.config

import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties::class)
class DatabaseConfiguration {

    /** Só migra; fecha ao fim das migrações para não manter conexão do papel dono. */
    @Bean
    fun moduleMigrator(properties: DatabaseProperties): ModuleMigrator {
        val dataSource = hikari(properties.migration, "migration", maxPoolSize = 2)
        try {
            return ModuleMigrator(dataSource, properties.modules).also { it.migrate() }
        } finally {
            dataSource.close()
        }
    }

    /** Depende do `moduleMigrator`: nada usa o banco antes das migrações. */
    @Bean(destroyMethod = "close")
    @Primary
    fun dataSource(properties: DatabaseProperties, @Suppress("UNUSED_PARAMETER") moduleMigrator: ModuleMigrator): DataSource =
        hikari(properties.app, "app", maxPoolSize = 10)

    /** Substitui o gerenciador padrão do Boot: aplica o contexto de tenant em cada transação. */
    @Bean
    fun transactionManager(entityManagerFactory: EntityManagerFactory): TenantAwareTransactionManager =
        TenantAwareTransactionManager(entityManagerFactory)

    private fun hikari(credentials: DatabaseProperties.Credentials, name: String, maxPoolSize: Int) =
        HikariDataSource().apply {
            poolName = "clinic-$name"
            jdbcUrl = credentials.url
            username = credentials.username
            password = credentials.password
            maximumPoolSize = maxPoolSize
        }
}
