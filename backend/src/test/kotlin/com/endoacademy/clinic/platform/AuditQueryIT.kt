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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** `GET /audit-events`: só a clínica ativa, filtros e paginação (T2.8). */
@AutoConfigureMockMvc
class AuditQueryIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var json: JsonMapper

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

    private class Clinic(val id: UUID, val adminSub: String)

    private val platformSub: String by lazy {
        val id = UUID.randomUUID()
        val sub = "sub-platform-$id"
        TenantScope.runAsPlatform {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Admin')", id, sub, "$id@example.test")
                jdbc.update("insert into platform.platform_admin (user_id) values (?)", id)
            }
        }
        sub
    }

    private fun newClinic(): Clinic {
        val email = "admin-${UUID.randomUUID()}@clinica.test"
        val body = mockMvc.perform(
            post("/api/v1/admin/clinics").header(HttpHeaders.AUTHORIZATION, bearer(platformSub)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("name" to "Clínica", "adminEmail" to email, "adminName" to "Admin"))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return Clinic(UUID.fromString(json.readTree(body).get("id").asString()), UUID.nameUUIDFromBytes(email.toByteArray()).toString())
    }

    private fun createRole(clinic: Clinic, name: String): UUID {
        val body = mockMvc.perform(
            post("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, bearer(clinic.adminSub)).header("X-Clinic-Id", clinic.id.toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("name" to name, "permissions" to emptyList<String>()))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(body).get("id").asString())
    }

    private fun events(clinic: Clinic, query: String = "", sub: String = clinic.adminSub) =
        get("/api/v1/audit-events$query").header(HttpHeaders.AUTHORIZATION, bearer(sub)).header("X-Clinic-Id", clinic.id.toString())

    private fun JsonNode.elements(): List<JsonNode> = (0 until size()).map { get(it) }

    private fun items(clinic: Clinic, query: String = ""): JsonNode =
        json.readTree(mockMvc.perform(events(clinic, query)).andExpect(status().isOk).andReturn().response.contentAsString).get("items")

    @Test
    fun `returns only the events of the active clinic, newest first`() {
        val a = newClinic()
        val b = newClinic()
        val roleOfA = createRole(a, "Só A")
        val roleOfB = createRole(b, "Só B")

        val listed = items(a, "?size=100")
        val entityIds = listed.elements().map { it.get("entityId")?.asString() }
        assertThat(entityIds).contains(roleOfA.toString()).doesNotContain(roleOfB.toString())
        val inDatabase = TenantScope.runAs(a.id) { tx.execute { jdbc.queryForObject("select count(*) from platform.audit_event", Int::class.java) }!! }
        assertThat(listed.size()).isEqualTo(inDatabase)
        val times = listed.elements().map { it.get("occurredAt").asString() }
        assertThat(times).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(listed.elements().first { it.get("action").asString() == "CREATE_ROLE" }.get("details").elements().map { it.asString() }).containsExactlyInAnyOrder("name", "permissions")
    }

    @Test
    fun `filters by action, entity type, user and period`() {
        val a = newClinic()
        createRole(a, "R1")
        createRole(a, "R2")

        assertThat(items(a, "?action=CREATE_ROLE").elements().map { it.get("action").asString() }).hasSize(2).containsOnly("CREATE_ROLE")
        assertThat(items(a, "?entityType=ROLE").size()).isEqualTo(2)
        assertThat(items(a, "?entityType=CLINIC").elements().map { it.get("action").asString() }).containsExactly("CREATE_CLINIC")
        assertThat(items(a, "?action=NADA").size()).isZero()
        val userId = items(a, "?action=CREATE_ROLE").elements().first().get("userId").asString()
        assertThat(items(a, "?userId=$userId&action=CREATE_ROLE").size()).isEqualTo(2)
        assertThat(items(a, "?userId=${UUID.randomUUID()}").size()).isZero()
        assertThat(items(a, "?from=2999-01-01T00:00:00Z").size()).isZero()
        assertThat(items(a, "?to=2000-01-01T00:00:00Z").size()).isZero()
        assertThat(items(a, "?from=2000-01-01T00:00:00Z&to=2999-01-01T00:00:00Z").size()).isGreaterThan(0)
    }

    @Test
    fun `paginates with a default of 20 and a maximum of 100`() {
        val a = newClinic()
        repeat(3) { createRole(a, "Paginação $it") }

        val first = json.readTree(mockMvc.perform(events(a, "?size=2&page=0")).andExpect(status().isOk).andReturn().response.contentAsString)
        val second = json.readTree(mockMvc.perform(events(a, "?size=2&page=1")).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(first.get("items").size()).isEqualTo(2)
        assertThat(first.get("total").asLong()).isGreaterThanOrEqualTo(5) // CREATE_CLINIC, ACTIVATE_MEMBERSHIP e 3 perfis
        assertThat(second.get("total").asLong()).isEqualTo(first.get("total").asLong())
        assertThat(second.get("items").elements().map { it.get("id").asString() }).doesNotContainAnyElementsOf(first.get("items").elements().map { it.get("id").asString() })

        mockMvc.perform(events(a)).andExpect(status().isOk).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.page").value(0))
        mockMvc.perform(events(a, "?size=100")).andExpect(status().isOk)
        mockMvc.perform(events(a, "?size=101")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        mockMvc.perform(events(a, "?size=0")).andExpect(status().isBadRequest)
        mockMvc.perform(events(a, "?page=-1")).andExpect(status().isBadRequest)
        mockMvc.perform(events(a, "?from=2026-10-02T00:00:00Z&to=2026-10-01T00:00:00Z")).andExpect(status().isBadRequest)
        mockMvc.perform(events(a, "?from=ontem")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
    }

    @Test
    fun `a member without VIEW_AUDIT_TRAIL gets 403 and one with it can read`() {
        val a = newClinic()
        // perfil com a permissão, criado pela API, e dois usuários: um com o perfil, outro com um perfil sem ela
        val withPermission = roleWith(a, "Auditor", listOf("VIEW_AUDIT_TRAIL"))
        val without = roleWith(a, "Sem auditoria", listOf("MANAGE_BRANDING"))
        val auditorSub = member(a, withPermission)
        val otherSub = member(a, without)

        mockMvc.perform(events(a, sub = auditorSub)).andExpect(status().isOk)
        mockMvc.perform(events(a, sub = otherSub)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        mockMvc.perform(get("/api/v1/audit-events").header(HttpHeaders.AUTHORIZATION, bearer(auditorSub))).andExpect(status().isBadRequest) // sem X-Clinic-Id
        assertThat(items(a, "?action=ACCESS_DENIED").elements().map { it.get("outcome").asString() }).contains("DENIED")
    }

    private fun roleWith(clinic: Clinic, name: String, permissions: List<String>): UUID {
        val body = mockMvc.perform(
            post("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, bearer(clinic.adminSub)).header("X-Clinic-Id", clinic.id.toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("name" to name, "permissions" to permissions))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(body).get("id").asString())
    }

    private fun member(clinic: Clinic, roleId: UUID): String {
        val userId = UUID.randomUUID()
        val sub = "sub-member-$userId"
        val membershipId = UUID.randomUUID()
        TenantScope.runAsPlatform {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Membro')", userId, sub, "$userId@example.test")
                jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, 'ACTIVE', ?)", membershipId, clinic.id, userId, sub)
            }
        }
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult { jdbc.update("insert into platform.membership_role (tenant_id, membership_id, role_id) values (?, ?, ?)", clinic.id, membershipId, roleId) }
        }
        // chamada negada para gerar um evento DENIED, quando o membro não tem a permissão
        mockMvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, bearer(sub)).header("X-Clinic-Id", clinic.id.toString()))
        return sub
    }
}
