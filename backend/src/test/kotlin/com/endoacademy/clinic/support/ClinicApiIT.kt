package com.endoacademy.clinic.support

import com.endoacademy.clinic.platform.api.TenantScope
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Base dos testes de API de ponta a ponta dos módulos de negócio: clínicas criadas pelo fluxo real, tokens do mock OIDC
 * e membros com permissões escolhidas. O mock OIDC é um só para a suíte, de modo que os contextos do Spring são
 * reaproveitados entre as classes.
 */
@AutoConfigureMockMvc
abstract class ClinicApiIT : DatabaseIT() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var json: JsonMapper

    protected val tx get() = TransactionTemplate(transactionManager)

    /** `adminSub` é o `sub` do 1º administrador da clínica (`CLINIC_ADMIN`). */
    class Clinic(val id: UUID, val adminSub: String)

    companion object {
        private val oidc: MockOAuth2Server by lazy {
            MockOAuth2Server().also { server ->
                server.start()
                Runtime.getRuntime().addShutdownHook(Thread { server.shutdown() })
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun oidc(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { oidc.issuerUrl("clinic").toString() }
        }

        fun bearer(subject: String) = "Bearer " + oidc.issueToken(
            "clinic", "client", DefaultOAuth2TokenCallback("clinic", subject, "JWT", listOf("clinic-api"), emptyMap(), 3600),
        ).serialize()
    }

    /** `sub` de um `PLATFORM_ADMIN` criado para o teste. */
    protected val platformAdminSub: String by lazy {
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

    protected fun newClinic(): Clinic {
        val email = "admin-${UUID.randomUUID()}@clinica.test"
        val body = mockMvc.perform(
            post("/api/v1/admin/clinics").header(HttpHeaders.AUTHORIZATION, bearer(platformAdminSub)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("name" to "Clínica", "adminEmail" to email, "adminName" to "Admin"))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return Clinic(UUID.fromString(json.readTree(body).get("id").asString()), UUID.nameUUIDFromBytes(email.toByteArray()).toString())
    }

    /** Acrescenta o cabeçalho de autenticação e a clínica ativa; por padrão age como o administrador da clínica. */
    protected fun call(builder: MockHttpServletRequestBuilder, clinic: Clinic, sub: String = clinic.adminSub): MockHttpServletRequestBuilder =
        builder.header(HttpHeaders.AUTHORIZATION, bearer(sub)).header("X-Clinic-Id", clinic.id.toString())

    protected fun body(vararg pairs: Pair<String, Any?>): String = json.writeValueAsString(mapOf(*pairs))

    /** Usuário ativo na clínica com um perfil que tem exatamente as permissões dadas; devolve o `sub`. */
    protected fun memberWithPermissions(clinic: Clinic, vararg permissions: String): String {
        val roleId = UUID.randomUUID()
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.role (id, tenant_id, name) values (?, ?, ?)", roleId, clinic.id, "Perfil de teste $roleId")
                permissions.forEach { jdbc.update("insert into platform.role_permission (tenant_id, role_id, permission) values (?, ?, ?)", clinic.id, roleId, it) }
            }
        }
        val userId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val sub = "sub-member-$userId"
        TenantScope.runAsPlatform {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Membro')", userId, sub, "$userId@example.test")
                jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, 'ACTIVE', ?)", membershipId, clinic.id, userId, sub)
            }
        }
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult { jdbc.update("insert into platform.membership_role (tenant_id, membership_id, role_id) values (?, ?, ?)", clinic.id, membershipId, roleId) }
        }
        return sub
    }

    /** Espera uma condição que depende de um ouvinte assíncrono (até 15 s), com falha explicando o que faltou. */
    protected fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "tempo esgotado esperando: $what" }
            Thread.sleep(100)
        }
    }

    /** Conta eventos de auditoria da clínica por ação (sob a RLS da própria clínica). */
    protected fun auditCount(clinic: Clinic, action: String, outcome: String = "SUCCESS"): Int =
        TenantScope.runAs(clinic.id) {
            tx.execute {
                jdbc.queryForObject("select count(*) from platform.audit_event where action = ? and outcome = ?", Int::class.java, action, outcome)
            }!!
        }
}
