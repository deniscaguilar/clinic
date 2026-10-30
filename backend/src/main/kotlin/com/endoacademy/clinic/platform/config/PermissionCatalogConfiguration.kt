package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.application.PermissionCatalog
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class PermissionCatalogConfiguration {
    @Bean
    fun permissionCatalog(contributors: List<PermissionContributor>) = PermissionCatalog(contributors)
}
