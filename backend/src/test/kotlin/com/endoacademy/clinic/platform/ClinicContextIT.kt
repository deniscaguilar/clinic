package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.application.MembershipAccess
import com.endoacademy.clinic.platform.application.MembershipAccessReader
import com.endoacademy.clinic.support.DatabaseIT
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Clínica ativa por cabeçalho (T1.10): sem vínculo, vínculo inativo ou clínica inativa dão 403; sem cabeçalho dá erro. */
@AutoConfigureMockMvc
@Import(ClinicContextIT.Memberships::class)
class ClinicContextIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    companion object {
        private val oidc = MockOAuth2Server().also { it.start() }
        val active = UUID.randomUUID()
        val inactiveMembership = UUID.randomUUID()
        val inactiveClinic = UUID.randomUUID()
        val stranger = UUID.randomUUID()

        @JvmStatic
        @DynamicPropertySource
        fun oidc(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { oidc.issuerUrl("clinic").toString() }
        }

        @JvmStatic
        @AfterAll
        fun stop() = oidc.shutdown()

        private fun bearer(subject: String = "user-1") = "Bearer " + oidc.issueToken(
            "clinic", "client", DefaultOAuth2TokenCallback("clinic", subject, "JWT", listOf("clinic-api"), emptyMap(), 3600),
        ).serialize()
    }

    @TestConfiguration
    class Memberships {
        @Bean
        @Primary
        fun fakeMembershipAccessReader() = MembershipAccessReader { sub, clinic ->
            if (sub != "user-1") return@MembershipAccessReader null
            val userId = UUID.nameUUIDFromBytes(sub.toByteArray())
            when (clinic) {
                active -> MembershipAccess(userId, true, true, setOf("VIEW_PATIENTS", "MANAGE_USERS"))
                inactiveMembership -> MembershipAccess(userId, false, true, setOf("VIEW_PATIENTS"))
                inactiveClinic -> MembershipAccess(userId, true, false, setOf("VIEW_PATIENTS"))
                else -> null
            }
        }
    }

    private fun request(clinic: String?, subject: String = "user-1") =
        get("/api/v1/test-clinic").header(HttpHeaders.AUTHORIZATION, bearer(subject)).apply {
            if (clinic != null) header("X-Clinic-Id", clinic)
        }

    @Test
    fun `active membership passes, exposes the permissions and runs inside the tenant scope`() {
        mockMvc.perform(request(active.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.clinicId").value(active.toString()))
            .andExpect(jsonPath("$.permissions[0]").value("MANAGE_USERS"))
            .andExpect(jsonPath("$.permissions[1]").value("VIEW_PATIENTS"))
            .andExpect(jsonPath("$.tenantInScope").value(active.toString()))
    }

    @Test
    fun `missing header is an error with a stable code`() {
        mockMvc.perform(request(null))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("CLINIC_HEADER_REQUIRED"))
    }

    @Test
    fun `malformed header is an error with a stable code`() {
        mockMvc.perform(request("not-a-uuid"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("CLINIC_HEADER_INVALID"))
    }

    @Test
    fun `no membership, inactive membership and inactive clinic all answer the same 403`() {
        listOf(stranger, inactiveMembership, inactiveClinic).forEach { clinic ->
            mockMvc.perform(request(clinic.toString()))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("CLINIC_ACCESS_DENIED"))
        }
    }

    @Test
    fun `another user does not get the clinic of user-1`() {
        mockMvc.perform(request(active.toString(), subject = "user-2")).andExpect(status().isForbidden)
    }

    @Test
    fun `admin routes do not need the header`() {
        mockMvc.perform(get("/api/v1/admin/test").header(HttpHeaders.AUTHORIZATION, bearer())).andExpect(status().isOk)
    }

    @Test
    fun `the context does not leak after the request`() {
        mockMvc.perform(request(active.toString())).andExpect(status().isOk)
        org.assertj.core.api.Assertions.assertThat(com.endoacademy.clinic.platform.api.TenantScope.current()).isNull()
    }

    @Test
    fun `without token the answer is still 401, before any clinic check`() {
        mockMvc.perform(get("/api/v1/test-clinic").header("X-Clinic-Id", active.toString())).andExpect(status().isUnauthorized)
    }
}
