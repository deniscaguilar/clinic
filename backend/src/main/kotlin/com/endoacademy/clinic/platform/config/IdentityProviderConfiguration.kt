package com.endoacademy.clinic.platform.config

import com.endoacademy.clinic.platform.adapter.out.identity.CognitoIdentityProvider
import com.endoacademy.clinic.platform.adapter.out.identity.InMemoryIdentityProvider
import com.endoacademy.clinic.platform.application.IdentityProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient

/**
 * Escolhe o adaptador do `IdentityProvider` por `clinic.identity.provider` (`local` ou `cognito`). Sem valor a
 * aplicação não sobe (falha fechada): o adaptador falso nunca entra em produção por esquecimento.
 */
@Configuration(proxyBeanMethods = false)
class IdentityProviderConfiguration {

    @Bean
    @ConditionalOnProperty("clinic.identity.provider", havingValue = "local")
    fun inMemoryIdentityProvider(): InMemoryIdentityProvider = InMemoryIdentityProvider()

    @Bean
    @ConditionalOnProperty("clinic.identity.provider", havingValue = "cognito")
    fun cognitoIdentityProvider(
        @Value("\${clinic.identity.cognito.user-pool-id}") userPoolId: String,
        @Value("\${clinic.identity.cognito.region:sa-east-1}") region: String,
    ): IdentityProvider = CognitoIdentityProvider(
        CognitoIdentityProviderClient.builder().region(Region.of(region)).build(),
        userPoolId,
    )
}
