package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.identity.InMemoryIdentityProvider
import com.endoacademy.clinic.platform.application.IdentityProviderException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InMemoryIdentityProviderTest {

    private val provider = InMemoryIdentityProvider()

    @Test
    fun `the sub is stable for the same e-mail, regardless of case and spaces`() {
        val first = provider.createUser("Ana@Clinica.test", "Ana")
        val second = InMemoryIdentityProvider().createUser("  ana@clinica.test ", "Outro nome")
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `different e-mails get different subs`() {
        assertThat(provider.createUser("a@clinica.test", "A")).isNotEqualTo(provider.createUser("b@clinica.test", "B"))
    }

    @Test
    fun `the local development e-mail maps to the sub issued by the mock OIDC server`() {
        assertThat(provider.createUser(InMemoryIdentityProvider.LOCAL_EMAIL, "Dev")).isEqualTo("local-user")
    }

    @Test
    fun `creating twice keeps one login`() {
        provider.createUser("a@clinica.test", "A")
        provider.createUser("a@clinica.test", "A")
        assertThat(provider.users()).containsExactly("a@clinica.test")
    }

    @Test
    fun `resend and disable are recorded`() {
        val sub = provider.createUser("a@clinica.test", "A")
        provider.resendInvitation("a@clinica.test")
        provider.disableUser(sub)
        assertThat(provider.resent()).containsExactly("a@clinica.test")
        assertThat(provider.disabled()).containsExactly(sub)
    }

    @Test
    fun `an injected failure is thrown once and then the provider works again`() {
        provider.failNext(IdentityProviderException("indisponível"))
        assertThatThrownBy { provider.createUser("a@clinica.test", "A") }.isInstanceOf(IdentityProviderException::class.java)
        assertThat(provider.users()).isEmpty()
        assertThat(provider.createUser("a@clinica.test", "A")).isNotBlank()
    }
}
