package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.identity.InMemoryIdentityProvider
import com.endoacademy.clinic.platform.api.ClinicCreated
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.platform.application.IdentityProviderException
import com.endoacademy.clinic.support.DatabaseIT
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** `POST /admin/clinics` de ponta a ponta, com tokens do mock OIDC e RLS real (T2.4; CA4, CA5). */
@AutoConfigureMockMvc
@Import(CreateClinicIT.Events::class)
class CreateClinicIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var identity: InMemoryIdentityProvider

    @Autowired
    lateinit var json: JsonMapper

    private val tx get() = TransactionTemplate(transactionManager)

    @TestConfiguration
    class Events {
        @Bean
        fun clinicCreatedCollector() = Collector()

        class Collector {
            val received = CopyOnWriteArrayList<ClinicCreated>()

            @EventListener
            fun on(event: ClinicCreated) {
                received += event
            }
        }
    }

    @Autowired
    lateinit var collector: Events.Collector

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

    private fun newUser(name: String = "Pessoa"): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val sub = "sub-$id"
        TenantScope.runAsPlatform {
            tx.executeWithoutResult { jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, ?)", id, sub, "$id@example.test", name) }
        }
        return id to sub
    }

    private fun platformAdmin(): Pair<UUID, String> = newUser("Admin").also { (id, _) ->
        TenantScope.runAsPlatform { tx.executeWithoutResult { jdbc.update("insert into platform.platform_admin (user_id) values (?)", id) } }
    }

    private fun create(sub: String, email: String, name: String = "Clínica da DCA", adminName: String = "Admin DCA") =
        post("/api/v1/admin/clinics")
            .header(HttpHeaders.AUTHORIZATION, bearer(sub))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("name" to name, "adminEmail" to email, "adminName" to adminName)))

    private fun <T> platform(block: () -> T): T = TenantScope.runAsPlatform { tx.execute { block() }!! }

    private fun uniqueEmail() = "admin-${UUID.randomUUID()}@dca.test"

    @Test
    fun `a platform admin creates the clinic and the first admin can enter it`() {
        val (_, adminSub) = platformAdmin()
        val email = uniqueEmail()

        val body = mockMvc.perform(create(adminSub, email))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.firstAdmin.status").value("ACTIVE"))
            .andReturn().response.contentAsString
        val clinicId = UUID.fromString(json.readTree(body).get("id").asString())

        // O 1º admin entra: o login do provedor (falso) devolveu um sub e o vínculo ficou ACTIVE.
        val firstAdminSub = identity.createUser(email, "qualquer")
        mockMvc.perform(get("/api/v1/me/clinics").header(HttpHeaders.AUTHORIZATION, bearer(firstAdminSub)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(clinicId.toString()))
            .andExpect(jsonPath("$[0].name").value("Clínica da DCA"))
        mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, bearer(firstAdminSub)).header("X-Clinic-Id", clinicId.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.roles[0]").value("CLINIC_ADMIN"))
            .andExpect(jsonPath("$.permissions[?(@ == 'MANAGE_USERS')]").exists())
            .andExpect(jsonPath("$.permissions[?(@ == 'MANAGE_CLINICS')]").doesNotExist())
        assertThat(collector.received).contains(ClinicCreated(clinicId))
    }

    @Test
    fun `the new clinic gets the five template roles and only the creation is audited in it`() {
        val (_, adminSub) = platformAdmin()
        val body = mockMvc.perform(create(adminSub, uniqueEmail())).andExpect(status().isCreated).andReturn().response.contentAsString
        val clinicId = UUID.fromString(json.readTree(body).get("id").asString())

        val roles = TenantScope.runAs(clinicId) { tx.execute { jdbc.queryForList("select name from platform.role order by name", String::class.java) }!! }
        assertThat(roles).containsExactly("CLINIC_ADMIN", "DOCTOR", "MANAGER", "NURSE", "STAFF")
        val actions = TenantScope.runAs(clinicId) {
            tx.execute { jdbc.queryForList("select action from platform.audit_event order by occurred_at, action", String::class.java) }!!
        }
        assertThat(actions).containsExactlyInAnyOrder("CREATE_CLINIC", "ACTIVATE_MEMBERSHIP")
    }

    @Test
    fun `a doctor cannot create a clinic and the denial is audited`() {
        val (_, doctorSub) = newUser("Médica")
        val clinicsBefore = platform { jdbc.queryForObject("select count(*) from platform.clinic", Int::class.java)!! }

        mockMvc.perform(create(doctorSub, uniqueEmail()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))

        assertThat(platform { jdbc.queryForObject("select count(*) from platform.clinic", Int::class.java)!! }).isEqualTo(clinicsBefore)
        val denied = platform {
            jdbc.queryForObject("select count(*) from platform.audit_event where outcome = 'DENIED' and entity_id = 'MANAGE_CLINICS'", Int::class.java)!!
        }
        assertThat(denied).isGreaterThan(0)
    }

    @Test
    fun `without a token the answer is 401 and an invalid body is 400 without echoing the value`() {
        mockMvc.perform(post("/api/v1/admin/clinics").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized)

        val (_, adminSub) = platformAdmin()
        val response = mockMvc.perform(create(adminSub, "sem-arroba@"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andReturn().response.contentAsString
        assertThat(response).doesNotContain("sem-arroba")
        mockMvc.perform(create(adminSub, uniqueEmail(), name = "  ")).andExpect(status().isBadRequest)
    }

    @Test
    fun `when the identity provider fails the clinic exists and the first admin stays PENDING`() {
        val (_, adminSub) = platformAdmin()
        val email = uniqueEmail()
        identity.failNext(IdentityProviderException("indisponível"))

        val body = mockMvc.perform(create(adminSub, email))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.firstAdmin.status").value("PENDING"))
            .andReturn().response.contentAsString
        val clinicId = UUID.fromString(json.readTree(body).get("id").asString())

        val status = platform { jdbc.queryForObject("select status from platform.membership where tenant_id = ?", String::class.java, clinicId) }
        assertThat(status).isEqualTo("PENDING")
        val subs = platform { jdbc.queryForList("select sub from platform.app_user where lower(email) = ?", String::class.java, email) }
        assertThat(subs).hasSize(1)
        assertThat(subs.first()).isNull()
        val failures = TenantScope.runAs(clinicId) {
            tx.execute { jdbc.queryForList("select action from platform.audit_event where outcome = 'FAILURE'", String::class.java) }!!
        }
        assertThat(failures).containsExactly("CREATE_IDENTITY")
    }

    @Test
    fun `an admin of another clinic reused as first admin enters the new clinic with the same login`() {
        val (_, adminSub) = platformAdmin()
        val email = uniqueEmail()
        val first = mockMvc.perform(create(adminSub, email)).andExpect(status().isCreated).andReturn().response.contentAsString
        val second = mockMvc.perform(create(adminSub, email, name = "Outra Clínica")).andExpect(status().isCreated)
            .andExpect(jsonPath("$.firstAdmin.status").value("ACTIVE")).andReturn().response.contentAsString

        val sub = identity.createUser(email, "x")
        mockMvc.perform(get("/api/v1/me/clinics").header(HttpHeaders.AUTHORIZATION, bearer(sub)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
        assertThat(json.readTree(first).get("id").asString()).isNotEqualTo(json.readTree(second).get("id").asString())
    }
}
