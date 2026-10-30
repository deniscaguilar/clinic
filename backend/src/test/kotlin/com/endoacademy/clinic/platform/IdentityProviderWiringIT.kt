package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.identity.InMemoryIdentityProvider
import com.endoacademy.clinic.platform.application.IdentityProvider
import com.endoacademy.clinic.support.DatabaseIT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class IdentityProviderWiringIT : DatabaseIT() {

    @Autowired
    lateinit var identityProvider: IdentityProvider

    @Test
    fun `local profile wires the in-memory identity provider`() {
        assertThat(identityProvider).isInstanceOf(InMemoryIdentityProvider::class.java)
    }
}
