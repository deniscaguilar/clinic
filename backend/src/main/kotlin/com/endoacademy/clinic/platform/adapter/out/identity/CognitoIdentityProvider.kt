package com.endoacademy.clinic.platform.adapter.out.identity

import com.endoacademy.clinic.platform.application.IdentityProvider
import com.endoacademy.clinic.platform.application.IdentityProviderException
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDisableUserRequest
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException

/**
 * Adaptador do Cognito. O `AdminCreateUser` já envia o convite por e-mail. O pool usa o e-mail como nome de usuário.
 * Só é exercitado de verdade no ambiente de fumaça da AWS; antes disso, o teste usa um cliente simulado (plano, 9.1).
 * Erros do SDK viram [IdentityProviderException] sem a mensagem original, que pode citar e-mail.
 */
class CognitoIdentityProvider(
    private val client: CognitoIdentityProviderClient,
    private val userPoolId: String,
) : IdentityProvider {

    override fun createUser(email: String, name: String): String {
        val username = normalize(email)
        return call("criar usuário") {
            try {
                val response = client.adminCreateUser(
                    AdminCreateUserRequest.builder()
                        .userPoolId(userPoolId)
                        .username(username)
                        .userAttributes(attribute("email", username), attribute("email_verified", "true"), attribute("name", name))
                        .build(),
                )
                subOf(response.user().attributes())
            } catch (_: UsernameExistsException) {
                subOf(client.adminGetUser(AdminGetUserRequest.builder().userPoolId(userPoolId).username(username).build()).userAttributes())
            }
        }
    }

    override fun resendInvitation(email: String) = call("reenviar convite") {
        client.adminCreateUser(
            AdminCreateUserRequest.builder()
                .userPoolId(userPoolId)
                .username(normalize(email))
                .messageAction(MessageActionType.RESEND)
                .build(),
        )
        Unit
    }

    override fun disableUser(sub: String) = call("desativar usuário") {
        client.adminDisableUser(AdminDisableUserRequest.builder().userPoolId(userPoolId).username(sub).build())
        Unit
    }

    private fun <T> call(operation: String, block: () -> T): T = try {
        block()
    } catch (exception: IdentityProviderException) {
        throw exception
    } catch (exception: RuntimeException) {
        throw IdentityProviderException("Falha no provedor de identidade ao $operation (${exception.javaClass.simpleName})")
    }

    private fun subOf(attributes: List<AttributeType>): String =
        attributes.firstOrNull { it.name() == "sub" }?.value()?.takeIf { it.isNotBlank() }
            ?: throw IdentityProviderException("O provedor de identidade não devolveu o sub")

    private fun attribute(name: String, value: String) = AttributeType.builder().name(name).value(value).build()

    private fun normalize(email: String) = email.trim().lowercase()
}
