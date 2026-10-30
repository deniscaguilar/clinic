package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.adapter.out.persistence.JdbcBrandingStore
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.FileStorage
import com.endoacademy.clinic.platform.application.BrandingPresenter
import com.endoacademy.clinic.platform.application.BrandingStore
import com.endoacademy.clinic.platform.application.GetBranding
import com.endoacademy.clinic.platform.application.ProvisionBranding
import com.endoacademy.clinic.platform.application.Transactions
import com.endoacademy.clinic.platform.application.UpdateBranding
import com.endoacademy.clinic.platform.application.UploadBrandAsset
import com.endoacademy.clinic.platform.application.UserDirectory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

@Configuration(proxyBeanMethods = false)
class BrandingConfiguration {

    @Bean
    fun brandingStore(jdbc: JdbcTemplate): BrandingStore = JdbcBrandingStore(jdbc)

    @Bean
    fun brandingPresenter(fileStorage: FileStorage) = BrandingPresenter(fileStorage)

    @Bean
    fun getBranding(store: BrandingStore, directory: UserDirectory, presenter: BrandingPresenter, transactions: Transactions) =
        GetBranding(store, directory, presenter, transactions)

    @Bean
    fun updateBranding(
        authorizer: Authorizer,
        store: BrandingStore,
        directory: UserDirectory,
        presenter: BrandingPresenter,
        auditTrail: AuditTrail,
        transactions: Transactions,
    ) = UpdateBranding(authorizer, store, directory, presenter, auditTrail, transactions)

    @Bean
    fun uploadBrandAsset(
        authorizer: Authorizer,
        store: BrandingStore,
        directory: UserDirectory,
        fileStorage: FileStorage,
        presenter: BrandingPresenter,
        auditTrail: AuditTrail,
        transactions: Transactions,
    ) = UploadBrandAsset(authorizer, store, directory, fileStorage, presenter, auditTrail, transactions)

    @Bean
    fun provisionBranding(store: BrandingStore, directory: UserDirectory, transactions: Transactions) = ProvisionBranding(store, directory, transactions)
}
