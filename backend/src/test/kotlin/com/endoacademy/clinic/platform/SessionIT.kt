package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.DatabaseIT
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** `/me`, `/me/clinics` e `/session` com tokens reais do mock OIDC e RLS real (T2.3; CA11). */
@AutoConfigureMockMvc
class SessionIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val tx get() = TransactionTemplate(transactionManager)

    companion object {
        private val oidc = MockOAuth2Server().also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun oidc(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { oidc.issuerUrl("clinic").toString() }
        }

        @JvmStatic
        @AfterAll
        fun stop() = oidc.shutdown()

        private fun bearer(subject: String) = "Bearer " + oidc.issueToken(
            "clinic", "client", DefaultOAuth2TokenCallback("clinic", subject, "JWT", listOf("clinic-api"), emptyMap(), 3600),
        ).serialize()
    }

    private class Person(val sub: String = "sub-" + UUID.randomUUID(), val id: UUID = UUID.randomUUID(), val email: String = "$id@example.test")

    private fun person(name: String = "Ana Souza"): Person = Person().also { p ->
        TenantScope.runAsPlatform {
            tx.executeWithoutResult { jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, ?)", p.id, p.sub, p.email, name) }
        }
    }

    private fun clinic(name: String, status: String = "ACTIVE"): UUID = UUID.randomUUID().also { id ->
        TenantScope.runAsPlatform { tx.executeWithoutResult { jdbc.update("insert into platform.clinic (id, name, status) values (?, ?, ?)", id, name, status) } }
    }

    private fun join(clinic: UUID, person: Person, roleName: String, permissions: Set<String>, status: String = "ACTIVE") {
        val membershipId = UUID.randomUUID()
        TenantScope.runAsPlatform {
            tx.executeWithoutResult { jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, ?, ?)", membershipId, clinic, person.id, status, person.sub) }
        }
        TenantScope.runAs(clinic) {
            tx.executeWithoutResult {
                val roleId = UUID.randomUUID()
                jdbc.update("insert into platform.role (id, tenant_id, name) values (?, ?, ?)", roleId, clinic, roleName)
                permissions.forEach { jdbc.update("insert into platform.role_permission (tenant_id, role_id, permission) values (?, ?, ?)", clinic, roleId, it) }
                jdbc.update("insert into platform.membership_role (tenant_id, membership_id, role_id) values (?, ?, ?)", clinic, membershipId, roleId)
            }
        }
    }

    private fun auditedLogins(clinic: UUID): Int = TenantScope.runAs(clinic) {
        tx.execute { jdbc.queryForObject("select count(*) from platform.audit_event where action = 'LOGIN' and outcome = 'SUCCESS'", Int::class.java) }!!
    }

    private fun session(person: Person, clinic: UUID?) =
        get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, bearer(person.sub)).apply { if (clinic != null) header("X-Clinic-Id", clinic.toString()) }

    @Test
    fun `me returns the profile of the token owner`() {
        val ana = person()
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(ana.sub)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(ana.id.toString()))
            .andExpect(jsonPath("$.email").value(ana.email))
            .andExpect(jsonPath("$.name").value("Ana Souza"))
    }

    @Test
    fun `me clinics lists only the active clinics where the membership is active`() {
        val ana = person()
        val a = clinic("Clínica Alfa")
        val b = clinic("Clínica Beta")
        val inactiveClinic = clinic("Clínica Gama", "INACTIVE")
        val inactiveMembership = clinic("Clínica Delta")
        val others = clinic("Clínica de outros")
        join(a, ana, "DOCTOR", setOf("VIEW_PATIENTS"))
        join(b, ana, "MANAGER", setOf("MANAGE_USERS"))
        join(inactiveClinic, ana, "DOCTOR", setOf("VIEW_PATIENTS"))
        join(inactiveMembership, ana, "DOCTOR", setOf("VIEW_PATIENTS"), status = "INACTIVE")
        join(others, person(), "DOCTOR", setOf("VIEW_PATIENTS"))

        mockMvc.perform(get("/api/v1/me/clinics").header(HttpHeaders.AUTHORIZATION, bearer(ana.sub)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(a.toString()))
            .andExpect(jsonPath("$[0].name").value("Clínica Alfa"))
            .andExpect(jsonPath("$[1].id").value(b.toString()))
    }

    @Test
    fun `a user in two clinics sees only the roles and permissions of the chosen one`() {
        val ana = person()
        val a = clinic("Clínica Alfa")
        val b = clinic("Clínica Beta")
        join(a, ana, "DOCTOR", setOf("VIEW_PATIENTS"))
        join(b, ana, "MANAGER", setOf("MANAGE_USERS", "VIEW_AUDIT_TRAIL"))

        mockMvc.perform(session(ana, a))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.clinic.id").value(a.toString()))
            .andExpect(jsonPath("$.clinic.name").value("Clínica Alfa"))
            .andExpect(jsonPath("$.user.email").value(ana.email))
            .andExpect(jsonPath("$.roles.length()").value(1))
            .andExpect(jsonPath("$.roles[0]").value("DOCTOR"))
            .andExpect(jsonPath("$.permissions.length()").value(1))
            .andExpect(jsonPath("$.permissions[0]").value("VIEW_PATIENTS"))
        mockMvc.perform(session(ana, b))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.clinic.id").value(b.toString()))
            .andExpect(jsonPath("$.roles[0]").value("MANAGER"))
            .andExpect(jsonPath("$.permissions[0]").value("MANAGE_USERS"))
            .andExpect(jsonPath("$.permissions[1]").value("VIEW_AUDIT_TRAIL"))
    }

    @Test
    fun `the session does not carry the roles of other members of the same clinic`() {
        val ana = person()
        val bruno = person("Bruno Lima")
        val clinic = clinic("Clínica Alfa")
        join(clinic, ana, "DOCTOR", setOf("VIEW_PATIENTS"))
        join(clinic, bruno, "MANAGER", setOf("MANAGE_USERS"))

        mockMvc.perform(session(ana, clinic))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.roles.length()").value(1))
            .andExpect(jsonPath("$.roles[0]").value("DOCTOR"))
    }

    @Test
    fun `opening the session audits LOGIN in the chosen clinic only`() {
        val ana = person()
        val a = clinic("Clínica Alfa")
        val b = clinic("Clínica Beta")
        join(a, ana, "DOCTOR", setOf("VIEW_PATIENTS"))
        join(b, ana, "DOCTOR", setOf("VIEW_PATIENTS"))

        mockMvc.perform(session(ana, a)).andExpect(status().isOk)

        assertThat(auditedLogins(a)).isEqualTo(1)
        assertThat(auditedLogins(b)).isZero()
    }

    @Test
    fun `session without the header, or for a clinic without membership, is refused and not audited`() {
        val ana = person()
        val mine = clinic("Clínica Alfa")
        val foreign = clinic("Clínica de outros")
        join(mine, ana, "DOCTOR", setOf("VIEW_PATIENTS"))
        join(foreign, person(), "DOCTOR", setOf("VIEW_PATIENTS"))

        mockMvc.perform(session(ana, null)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CLINIC_HEADER_REQUIRED"))
        mockMvc.perform(session(ana, foreign)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("CLINIC_ACCESS_DENIED"))
        assertThat(auditedLogins(foreign)).isZero()
    }

    @Test
    fun `a valid token of an unregistered user gets 403 on me and an empty clinic list`() {
        val ghost = Person()
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(ghost.sub)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("USER_NOT_REGISTERED"))
        mockMvc.perform(get("/api/v1/me/clinics").header(HttpHeaders.AUTHORIZATION, bearer(ghost.sub)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `without a token the three routes answer 401`() {
        listOf("/api/v1/me", "/api/v1/me/clinics", "/api/v1/session").forEach {
            mockMvc.perform(get(it)).andExpect(status().isUnauthorized)
        }
    }
}
