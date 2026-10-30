package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.identity.CognitoIdentityProvider
import com.endoacademy.clinic.platform.application.IdentityProviderException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDisableUserRequest
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDisableUserResponse
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException

/** Adaptador do Cognito com o cliente simulado: o Cognito não roda no LocalStack gratuito (plano, seção 9.1). */
class CognitoIdentityProviderTest {

    private val client = mockk<CognitoIdentityProviderClient>()
    private val provider = CognitoIdentityProvider(client, "sa-east-1_pool")

    private fun user(sub: String) = UserType.builder().attributes(AttributeType.builder().name("sub").value(sub).build()).build()

    @Test
    fun `createUser creates the login with a verified e-mail and returns the sub`() {
        val request = slot<AdminCreateUserRequest>()
        every { client.adminCreateUser(capture(request)) } returns AdminCreateUserResponse.builder().user(user("sub-123")).build()

        val sub = provider.createUser("Ana@Clinica.test", "Ana Souza")

        assertThat(sub).isEqualTo("sub-123")
        assertThat(request.captured.userPoolId()).isEqualTo("sa-east-1_pool")
        assertThat(request.captured.username()).isEqualTo("ana@clinica.test")
        assertThat(request.captured.messageAction()).isNull() // o Cognito envia o convite
        val attributes = request.captured.userAttributes().associate { it.name() to it.value() }
        assertThat(attributes).containsEntry("email", "ana@clinica.test").containsEntry("email_verified", "true").containsEntry("name", "Ana Souza")
    }

    @Test
    fun `createUser for a login that already exists returns the existing sub without a second invitation`() {
        every { client.adminCreateUser(any<AdminCreateUserRequest>()) } throws UsernameExistsException.builder().message("exists").build()
        every { client.adminGetUser(any<AdminGetUserRequest>()) } returns
            AdminGetUserResponse.builder().userAttributes(AttributeType.builder().name("sub").value("sub-old").build()).build()

        assertThat(provider.createUser("ana@clinica.test", "Ana")).isEqualTo("sub-old")
        verify(exactly = 1) { client.adminCreateUser(any<AdminCreateUserRequest>()) }
    }

    @Test
    fun `resendInvitation asks the Cognito to resend`() {
        val request = slot<AdminCreateUserRequest>()
        every { client.adminCreateUser(capture(request)) } returns AdminCreateUserResponse.builder().user(user("sub-1")).build()

        provider.resendInvitation("Ana@Clinica.test")

        assertThat(request.captured.messageAction()).isEqualTo(MessageActionType.RESEND)
        assertThat(request.captured.username()).isEqualTo("ana@clinica.test")
    }

    @Test
    fun `disableUser disables the login by sub`() {
        val request = slot<AdminDisableUserRequest>()
        every { client.adminDisableUser(capture(request)) } returns AdminDisableUserResponse.builder().build()

        provider.disableUser("sub-9")

        assertThat(request.captured.username()).isEqualTo("sub-9")
        assertThat(request.captured.userPoolId()).isEqualTo("sa-east-1_pool")
    }

    @Test
    fun `provider failures become IdentityProviderException without the e-mail in the message`() {
        every { client.adminCreateUser(any<AdminCreateUserRequest>()) } throws CognitoIdentityProviderException.builder().message("boom ana@clinica.test").build()
        every { client.adminDisableUser(any<AdminDisableUserRequest>()) } throws CognitoIdentityProviderException.builder().message("boom").build()

        assertThatThrownBy { provider.createUser("ana@clinica.test", "Ana") }
            .isInstanceOf(IdentityProviderException::class.java)
            .satisfies({ assertThat(it.message).doesNotContain("ana@clinica.test") })
        assertThatThrownBy { provider.disableUser("sub-9") }.isInstanceOf(IdentityProviderException::class.java)
    }

    @Test
    fun `createUser fails when the response carries no sub`() {
        every { client.adminCreateUser(any<AdminCreateUserRequest>()) } returns AdminCreateUserResponse.builder().user(UserType.builder().build()).build()
        assertThatThrownBy { provider.createUser("ana@clinica.test", "Ana") }.isInstanceOf(IdentityProviderException::class.java)
    }
}
