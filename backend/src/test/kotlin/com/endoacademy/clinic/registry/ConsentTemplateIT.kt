package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.registry.application.ConsentTemplateStore
import com.endoacademy.clinic.registry.domain.ConsentTemplate
import com.endoacademy.clinic.support.ClinicApiIT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Modelos de consentimento da plataforma de ponta a ponta (T5.3): só o `PLATFORM_ADMIN` administra. */
class ConsentTemplateIT : ClinicApiIT() {

    @Autowired
    lateinit var store: ConsentTemplateStore

    private fun asAdmin(builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) =
        builder.header("Authorization", bearer(platformAdminSub))

    private fun create(name: String, text: String = "Texto do modelo.", required: Boolean = true, messaging: Boolean = false): ResultActions =
        mockMvc.perform(asAdmin(post("/api/v1/admin/consent-templates")).contentType(MediaType.APPLICATION_JSON).content(body("name" to name, "text" to text, "requiredOnSignup" to required, "controlsMessaging" to messaging)))

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    private fun update(id: UUID, name: String, text: String = "Texto do modelo.", active: Boolean = true): ResultActions =
        mockMvc.perform(
            asAdmin(put("/api/v1/admin/consent-templates/$id")).contentType(MediaType.APPLICATION_JSON)
                .content(body("name" to name, "text" to text, "requiredOnSignup" to true, "controlsMessaging" to false, "active" to active)),
        )

    private fun unique(prefix: String) = "$prefix ${UUID.randomUUID()}"

    @Test
    fun `the platform admin creates, reads, updates, inactivates and lists templates`() {
        val name = unique("Mensagens")
        val id = idOf(
            create("  $name  ", "  Autorizo as mensagens.  ", messaging = true).andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value(name)).andExpect(jsonPath("$.text").value("Autorizo as mensagens."))
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.controlsMessaging").value(true)),
        )
        mockMvc.perform(asAdmin(get("/api/v1/admin/consent-templates/$id"))).andExpect(status().isOk).andExpect(jsonPath("$.name").value(name))
        update(id, name, "Texto revisado.", active = false).andExpect(status().isOk).andExpect(jsonPath("$.text").value("Texto revisado.")).andExpect(jsonPath("$.active").value(false))

        mockMvc.perform(asAdmin(get("/api/v1/admin/consent-templates"))).andExpect(status().isOk).andExpect(jsonPath("$[?(@.id == '$id')].active").value(false))
        mockMvc.perform(asAdmin(get("/api/v1/admin/consent-templates?active=true"))).andExpect(jsonPath("$[?(@.id == '$id')]").doesNotExist())
        mockMvc.perform(asAdmin(get("/api/v1/admin/consent-templates?active=false"))).andExpect(jsonPath("$[?(@.id == '$id')]").exists())
    }

    @Test
    fun `nobody but a platform admin reaches the templates, and every refusal is audited`() {
        val clinic = newClinic()
        val id = idOf(create(unique("Imagem")).andExpect(status().isCreated))
        val before = platformDenials()

        fun clinicUser(builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) = builder.header("Authorization", bearer(clinic.adminSub))
        mockMvc.perform(clinicUser(get("/api/v1/admin/consent-templates"))).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        mockMvc.perform(clinicUser(get("/api/v1/admin/consent-templates/$id"))).andExpect(status().isForbidden)
        mockMvc.perform(clinicUser(post("/api/v1/admin/consent-templates")).contentType(MediaType.APPLICATION_JSON).content(body("name" to unique("Invasor"), "text" to "x", "requiredOnSignup" to true, "controlsMessaging" to false)))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            clinicUser(put("/api/v1/admin/consent-templates/$id")).contentType(MediaType.APPLICATION_JSON)
                .content(body("name" to "Invadido", "text" to "x", "requiredOnSignup" to true, "controlsMessaging" to false, "active" to false)),
        ).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/admin/consent-templates")).andExpect(status().isUnauthorized)

        assertThat(platformDenials() - before).isEqualTo(4)
        mockMvc.perform(asAdmin(get("/api/v1/admin/consent-templates/$id"))).andExpect(jsonPath("$.active").value(true))
    }

    private fun platformDenials(): Int = TenantScope.runAsPlatform {
        tx.execute { jdbc.queryForObject("select count(*) from platform.audit_event where outcome = 'DENIED' and entity_id = 'MANAGE_CLINICS'", Int::class.java) }!!
    }

    @Test
    fun `the name is unique ignoring case, input is validated, and unknown ids are 404`() {
        val name = unique("Único")
        val id = idOf(create(name).andExpect(status().isCreated))
        create(" ${name.uppercase()} ").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONSENT_TEMPLATE_NAME_TAKEN"))
        val other = idOf(create(unique("Outro")).andExpect(status().isCreated))
        update(other, name.lowercase()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONSENT_TEMPLATE_NAME_TAKEN"))
        update(id, name.lowercase(), "Texto novo.").andExpect(status().isOk)

        create(" ").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_NAME_REQUIRED"))
        create(unique("Sem texto"), text = " ").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_TEXT_REQUIRED"))
        create("x".repeat(121)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_NAME_TOO_LONG"))
        create(unique("Longo"), text = "x".repeat(20_001)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_TEXT_TOO_LONG"))
        mockMvc.perform(asAdmin(post("/api/v1/admin/consent-templates")).contentType(MediaType.APPLICATION_JSON).content(body("name" to unique("Sem opções"), "text" to "x")))
            .andExpect(status().isBadRequest)

        val unknown = UUID.randomUUID()
        mockMvc.perform(asAdmin(get("/api/v1/admin/consent-templates/$unknown"))).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CONSENT_TEMPLATE_NOT_FOUND"))
        update(unknown, unique("X")).andExpect(status().isNotFound)
    }

    @Test
    fun `template changes are audited as platform actions, with the actor, no clinic and no text`() {
        val id = idOf(create(unique("Auditado"), "Texto sigiloso do modelo").andExpect(status().isCreated))
        update(id, unique("Auditado 2"), "Outro texto sigiloso").andExpect(status().isOk)

        val rows = TenantScope.runAsPlatform {
            tx.execute {
                jdbc.query(
                    "select action, tenant_id, user_id, details::text || coalesce(entity_id, '') from platform.audit_event where entity_type = 'CONSENT_TEMPLATE' and entity_id = ? order by occurred_at",
                    { rs, _ -> listOf(rs.getString(1), rs.getObject(2), rs.getObject(3), rs.getString(4)) },
                    id.toString(),
                )
            }!!
        }
        assertThat(rows.map { it[0] }).containsExactly("CREATE_CONSENT_TEMPLATE", "UPDATE_CONSENT_TEMPLATE")
        rows.forEach {
            assertThat(it[1]).describedAs("sem clínica").isNull()
            assertThat(it[2]).describedAs("autor").isNotNull()
            assertThat(it[3] as String).doesNotContain("sigiloso")
        }
    }

    @Test
    fun `a clinic reads only the active templates through its own endpoint`() {
        val clinic = newClinic()
        val active = unique("Ativo")
        val inactive = unique("Inativo")
        create(active).andExpect(status().isCreated)
        update(idOf(create(inactive).andExpect(status().isCreated)), inactive, active = false).andExpect(status().isOk)
        mockMvc.perform(call(get("/api/v1/consent-templates"), clinic))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.name == '$active')]").exists())
            .andExpect(jsonPath("$[?(@.name == '$inactive')]").doesNotExist())
    }
    @Test
    fun `the database unique index turns a race on the template name into a conflict, not a server error`() {
        val name = unique("Corrida")
        TenantScope.runAsPlatform { tx.executeWithoutResult { store.insert(ConsentTemplate.create(UUID.randomUUID(), name, "Texto", true, false)) } }
        assertThatThrownBy {
            TenantScope.runAsPlatform { tx.executeWithoutResult { store.insert(ConsentTemplate.create(UUID.randomUUID(), name.uppercase(), "Texto", true, false)) } }
        }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
            assertThat(it.code).isEqualTo("CONSENT_TEMPLATE_NAME_TAKEN")
        }
        val other = ConsentTemplate.create(UUID.randomUUID(), unique("Outro"), "Texto", true, false)
        TenantScope.runAsPlatform { tx.executeWithoutResult { store.insert(other) } }
        assertThatThrownBy {
            TenantScope.runAsPlatform { tx.executeWithoutResult { store.update(other.update(name, "Texto", true, false, true)) } }
        }.isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("CONSENT_TEMPLATE_NAME_TAKEN") }
    }
}
