package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.registry.application.ConsentTypeStore
import com.endoacademy.clinic.registry.domain.ConsentType
import com.endoacademy.clinic.support.ClinicApiIT
import com.endoacademy.clinic.support.TenantFixtures
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
import java.time.Instant
import java.util.UUID

/** Tipos de consentimento e importação de modelos de ponta a ponta (T5.3; CA15). */
class ConsentTypeIT : ClinicApiIT() {

    @Autowired
    lateinit var store: ConsentTypeStore

    private fun create(clinic: Clinic, name: String = "Cadastro e mensagens", text: String = "Autorizo o cadastro.", required: Boolean = true, messaging: Boolean = false, sub: String = clinic.adminSub): ResultActions =
        mockMvc.perform(
            call(post("/api/v1/consent-types").contentType(MediaType.APPLICATION_JSON).content(body("name" to name, "text" to text, "requiredOnSignup" to required, "controlsMessaging" to messaging)), clinic, sub),
        )

    private fun revise(
        clinic: Clinic,
        id: UUID,
        name: String = "Cadastro e mensagens",
        text: String? = null,
        required: Boolean = true,
        messaging: Boolean = false,
        active: Boolean = true,
        sub: String = clinic.adminSub,
    ): ResultActions = mockMvc.perform(
        call(put("/api/v1/consent-types/$id").contentType(MediaType.APPLICATION_JSON).content(body("name" to name, "text" to text, "requiredOnSignup" to required, "controlsMessaging" to messaging, "active" to active)), clinic, sub),
    )

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    private fun createTemplate(name: String, text: String = "Texto do modelo.", messaging: Boolean = false): UUID = idOf(
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/consent-templates")
                .header("Authorization", bearer(platformAdminSub)).contentType(MediaType.APPLICATION_JSON)
                .content(body("name" to name, "text" to text, "requiredOnSignup" to true, "controlsMessaging" to messaging)),
        ).andExpect(status().isCreated),
    )

    @Test
    fun `the clinic admin creates, reads, lists and filters consent types`() {
        val clinic = newClinic()
        val id = idOf(
            create(clinic, name = "  Cadastro e mensagens  ", text = "Autorizo o cadastro.").andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("Cadastro e mensagens"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.currentVersion.number").value(1))
                .andExpect(jsonPath("$.currentVersion.text").value("Autorizo o cadastro."))
                .andExpect(jsonPath("$.versions.length()").value(1)),
        )
        create(clinic, name = "Uso de imagem", required = false).andExpect(status().isCreated)

        mockMvc.perform(call(get("/api/v1/consent-types/$id"), clinic)).andExpect(status().isOk).andExpect(jsonPath("$.versions[0].text").value("Autorizo o cadastro."))
        mockMvc.perform(call(get("/api/v1/consent-types"), clinic)).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].name").value("Cadastro e mensagens")).andExpect(jsonPath("$[0].currentVersion.number").value(1))
            .andExpect(jsonPath("$[0].text").doesNotExist()).andExpect(jsonPath("$[0].versions").doesNotExist())
        revise(clinic, id, name = "Cadastro e mensagens", active = false).andExpect(status().isOk).andExpect(jsonPath("$.active").value(false))
        mockMvc.perform(call(get("/api/v1/consent-types?active=true"), clinic)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Uso de imagem"))
        mockMvc.perform(call(get("/api/v1/consent-types?active=false"), clinic)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Cadastro e mensagens"))
    }

    @Test
    fun `a new text creates the next version and a past acceptance stays on the version the patient saw (CA15)`() {
        val clinic = newClinic()
        val id = idOf(create(clinic, text = "Texto original").andExpect(status().isCreated))
        val patient = TenantScope.runAs(clinic.id) { tx.execute { TenantFixtures.newPatient(jdbc, clinic.id) }!! }
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult {
                jdbc.update(
                    "insert into registry.consent_grant (id, tenant_id, patient_id, type_id, version_number, accepted_at, channel, accepted_by) values (?, ?, ?, ?, 1, now(), 'IN_CLINIC', ?)",
                    UUID.randomUUID(), clinic.id, patient, id, UUID.randomUUID(),
                )
            }
        }

        revise(clinic, id, text = "Texto novo").andExpect(status().isOk)
            .andExpect(jsonPath("$.currentVersion.number").value(2)).andExpect(jsonPath("$.currentVersion.text").value("Texto novo"))
            .andExpect(jsonPath("$.versions.length()").value(2)).andExpect(jsonPath("$.versions[0].text").value("Texto original"))
        revise(clinic, id, text = "Terceiro texto").andExpect(status().isOk).andExpect(jsonPath("$.currentVersion.number").value(3))
        // a lista mostra a versão vigente de cada tipo, não a primeira
        mockMvc.perform(call(get("/api/v1/consent-types"), clinic)).andExpect(jsonPath("$[0].currentVersion.number").value(3))

        val texts = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForList("select text from registry.consent_type_version where type_id = ? order by number", String::class.java, id) }!!
        }
        assertThat(texts).containsExactly("Texto original", "Texto novo", "Terceiro texto")
        val grantVersion = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForObject("select version_number from registry.consent_grant where type_id = ?", Int::class.java, id) }!!
        }
        assertThat(grantVersion).isEqualTo(1)
    }

    @Test
    fun `the same text or no text creates no version, and settings alone do not either`() {
        val clinic = newClinic()
        val id = idOf(create(clinic, text = "Texto original").andExpect(status().isCreated))
        revise(clinic, id, text = "Texto original").andExpect(status().isOk).andExpect(jsonPath("$.versions.length()").value(1))
        revise(clinic, id, text = null, required = false).andExpect(status().isOk).andExpect(jsonPath("$.versions.length()").value(1)).andExpect(jsonPath("$.requiredOnSignup").value(false))
        assertThat(auditCount(clinic, "REVISE_CONSENT_TYPE")).isZero()
        assertThat(auditCount(clinic, "UPDATE_CONSENT_TYPE")).isEqualTo(1)
    }

    @Test
    fun `the name is unique per clinic and only one active type controls messaging`() {
        val a = newClinic()
        val b = newClinic()
        val messaging = idOf(create(a, name = "Mensagens", messaging = true).andExpect(status().isCreated))
        create(a, name = " MENSAGENS ").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONSENT_TYPE_NAME_TAKEN"))
        create(a, name = "Outras mensagens", messaging = true).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONSENT_MESSAGING_TYPE_EXISTS"))
        val image = idOf(create(a, name = "Imagem").andExpect(status().isCreated))
        revise(a, image, name = "Imagem", messaging = true).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONSENT_MESSAGING_TYPE_EXISTS"))
        revise(a, image, name = "mensagens").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONSENT_TYPE_NAME_TAKEN"))
        // desativado o antigo, outro pode controlar as mensagens
        revise(a, messaging, name = "Mensagens", messaging = true, active = false).andExpect(status().isOk)
        revise(a, image, name = "Imagem", messaging = true).andExpect(status().isOk)
        create(b, name = "Mensagens", messaging = true).andExpect(status().isCreated)
    }

    @Test
    fun `the database decides races on the messaging type, the name and the version number with a 409`() {
        val clinic = newClinic()
        val now = Instant.parse("2026-10-30T10:00:00Z")
        fun type(name: String, messaging: Boolean) = ConsentType.create(UUID.randomUUID(), name, "Texto", true, messaging, now)
        TenantScope.runAs(clinic.id) { tx.executeWithoutResult { store.insert(clinic.id, type("Mensagens", true)) } }
        assertThatThrownBy { TenantScope.runAs(clinic.id) { tx.executeWithoutResult { store.insert(clinic.id, type("Outras mensagens", true)) } } }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("CONSENT_MESSAGING_TYPE_EXISTS") }
        assertThatThrownBy { TenantScope.runAs(clinic.id) { tx.executeWithoutResult { store.insert(clinic.id, type("MENSAGENS", false)) } } }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("CONSENT_TYPE_NAME_TAKEN") }

        val base = type("Imagem", false)
        TenantScope.runAs(clinic.id) { tx.executeWithoutResult { store.insert(clinic.id, base) } }
        TenantScope.runAs(clinic.id) { tx.executeWithoutResult { store.addVersion(base.id, base.revise("Texto 2 do João", now).current) } }
        // outra revisão partindo da mesma versão 1: a versão 2 já existe e a segunda não pode passar em silêncio
        assertThatThrownBy { TenantScope.runAs(clinic.id) { tx.executeWithoutResult { store.addVersion(base.id, base.revise("Texto 2 da Maria", now).current) } } }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
                assertThat(it.code).isEqualTo("CONSENT_VERSION_CONFLICT")
            }
        val stored = TenantScope.runAs(clinic.id) { tx.execute { store.find(base.id) } }!!
        assertThat(stored.versions.map { it.text }).containsExactly("Texto", "Texto 2 do João")
    }

    @Test
    fun `invalid input is rejected without echoing it and nothing is stored`() {
        val clinic = newClinic()
        create(clinic, name = " ").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_NAME_REQUIRED"))
        create(clinic, text = " ").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_TEXT_REQUIRED"))
        create(clinic, name = "x".repeat(121)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_NAME_TOO_LONG"))
        create(clinic, text = "x".repeat(20_001)).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_TEXT_TOO_LONG"))
        mockMvc.perform(call(post("/api/v1/consent-types").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Sem opções", "text" to "Texto")), clinic)).andExpect(status().isBadRequest)
        mockMvc.perform(call(get("/api/v1/consent-types"), clinic)).andExpect(jsonPath("$.length()").value(0))

        val id = idOf(create(clinic).andExpect(status().isCreated))
        revise(clinic, id, text = "   ").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CONSENT_TEXT_REQUIRED"))
        mockMvc.perform(call(put("/api/v1/consent-types/$id").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Cadastro", "requiredOnSignup" to true, "controlsMessaging" to false)), clinic))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        mockMvc.perform(call(get("/api/v1/consent-types/$id"), clinic)).andExpect(jsonPath("$.versions.length()").value(1))
    }

    @Test
    fun `a type of another clinic answers 404 and is untouched`() {
        val a = newClinic()
        val b = newClinic()
        val ofB = idOf(create(b, name = "Só da B").andExpect(status().isCreated))
        mockMvc.perform(call(get("/api/v1/consent-types/$ofB"), a)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CONSENT_TYPE_NOT_FOUND"))
        revise(a, ofB, name = "Invadido", text = "Texto invadido").andExpect(status().isNotFound)
        mockMvc.perform(call(get("/api/v1/consent-types"), a)).andExpect(jsonPath("$.length()").value(0))
        mockMvc.perform(call(get("/api/v1/consent-types/$ofB"), b)).andExpect(jsonPath("$.name").value("Só da B")).andExpect(jsonPath("$.versions.length()").value(1))
    }

    @Test
    fun `managing needs MANAGE_CONSENT_TYPES, reading also opens to patient registrars, and denials are audited`() {
        val clinic = newClinic()
        val id = idOf(create(clinic).andExpect(status().isCreated))
        val manager = memberWithPermissions(clinic, "MANAGE_CONSENT_TYPES")
        val registrar = memberWithPermissions(clinic, "MANAGE_PATIENTS")
        val outsider = memberWithPermissions(clinic, "MANAGE_PROCEDURES")

        mockMvc.perform(call(get("/api/v1/consent-types"), clinic, registrar)).andExpect(status().isOk)
        mockMvc.perform(call(get("/api/v1/consent-types/$id"), clinic, registrar)).andExpect(status().isOk)
        create(clinic, name = "Do registrador", sub = registrar).andExpect(status().isForbidden)
        revise(clinic, id, text = "Texto do registrador", sub = registrar).andExpect(status().isForbidden)
        mockMvc.perform(call(get("/api/v1/consent-templates"), clinic, registrar)).andExpect(status().isForbidden)

        mockMvc.perform(call(get("/api/v1/consent-types"), clinic, outsider)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        mockMvc.perform(call(get("/api/v1/consent-types/$id"), clinic, outsider)).andExpect(status().isForbidden)
        create(clinic, name = "Do intruso", sub = outsider).andExpect(status().isForbidden)
        mockMvc.perform(call(post("/api/v1/consent-types/import-template").contentType(MediaType.APPLICATION_JSON).content(body("templateId" to UUID.randomUUID())), clinic, outsider)).andExpect(status().isForbidden)
        val denied = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForObject("select count(*) from platform.audit_event where outcome = 'DENIED' and entity_id = 'MANAGE_CONSENT_TYPES'", Int::class.java) }!!
        }
        assertThat(denied).isEqualTo(7)

        create(clinic, name = "Do gerente", sub = manager).andExpect(status().isCreated)
        mockMvc.perform(get("/api/v1/consent-types")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `creating, revising and importing are audited with names only and no consent text`() {
        val clinic = newClinic()
        val id = idOf(create(clinic, name = "Nome sigiloso", text = "Texto sigiloso do consentimento").andExpect(status().isCreated))
        revise(clinic, id, name = "Nome sigiloso", text = "Outro texto sigiloso").andExpect(status().isOk)
        revise(clinic, id, name = "Nome sigiloso 2", text = null).andExpect(status().isOk)
        val template = createTemplate("Modelo sigiloso", "Texto sigiloso do modelo")
        mockMvc.perform(call(post("/api/v1/consent-types/import-template").contentType(MediaType.APPLICATION_JSON).content(body("templateId" to template)), clinic)).andExpect(status().isCreated)

        assertThat(auditCount(clinic, "CREATE_CONSENT_TYPE")).isEqualTo(1)
        assertThat(auditCount(clinic, "REVISE_CONSENT_TYPE")).isEqualTo(1)
        assertThat(auditCount(clinic, "UPDATE_CONSENT_TYPE")).isEqualTo(1)
        assertThat(auditCount(clinic, "IMPORT_CONSENT_TEMPLATE")).isEqualTo(1)
        val stored = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForList("select action || ' ' || coalesce(entity_id, '') || ' ' || details::text from platform.audit_event where entity_type = 'CONSENT_TYPE'", String::class.java) }!!
        }.filterNotNull()
        assertThat(stored).hasSize(4)
        stored.forEach { assertThat(it).doesNotContain("sigiloso").doesNotContain("Sigiloso") }
    }

    @Test
    fun `importing copies the template as version 1 and later changes to the template do not touch the clinic's type`() {
        val clinic = newClinic()
        val template = createTemplate("Cadastro e mensagens", "Texto do modelo.", messaging = true)
        val inactive = createTemplate("Modelo antigo")
        mockMvc.perform(
            put("/api/v1/admin/consent-templates/$inactive").header("Authorization", bearer(platformAdminSub)).contentType(MediaType.APPLICATION_JSON)
                .content(body("name" to "Modelo antigo", "text" to "Texto do modelo.", "requiredOnSignup" to true, "controlsMessaging" to false, "active" to false)),
        ).andExpect(status().isOk)

        mockMvc.perform(call(get("/api/v1/consent-templates"), clinic)).andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.name == 'Cadastro e mensagens')].text").value("Texto do modelo."))
            .andExpect(jsonPath("$[?(@.name == 'Modelo antigo')]").doesNotExist())

        val imported = idOf(
            mockMvc.perform(call(post("/api/v1/consent-types/import-template").contentType(MediaType.APPLICATION_JSON).content(body("templateId" to template)), clinic))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("Cadastro e mensagens"))
                .andExpect(jsonPath("$.requiredOnSignup").value(true))
                .andExpect(jsonPath("$.controlsMessaging").value(true))
                .andExpect(jsonPath("$.currentVersion.number").value(1))
                .andExpect(jsonPath("$.currentVersion.text").value("Texto do modelo.")),
        )
        assertThat(imported).isNotEqualTo(template)

        mockMvc.perform(
            put("/api/v1/admin/consent-templates/$template").header("Authorization", bearer(platformAdminSub)).contentType(MediaType.APPLICATION_JSON)
                .content(body("name" to "Cadastro e mensagens", "text" to "Texto do modelo ALTERADO.", "requiredOnSignup" to false, "controlsMessaging" to true, "active" to true)),
        ).andExpect(status().isOk)
        mockMvc.perform(call(get("/api/v1/consent-types/$imported"), clinic)).andExpect(jsonPath("$.currentVersion.text").value("Texto do modelo.")).andExpect(jsonPath("$.requiredOnSignup").value(true))

        // não importa de novo (nome igual), nem um modelo inativo, nem um que não existe
        mockMvc.perform(call(post("/api/v1/consent-types/import-template").contentType(MediaType.APPLICATION_JSON).content(body("templateId" to template)), clinic))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONSENT_TYPE_NAME_TAKEN"))
        listOf(inactive, UUID.randomUUID()).forEach {
            mockMvc.perform(call(post("/api/v1/consent-types/import-template").contentType(MediaType.APPLICATION_JSON).content(body("templateId" to it)), clinic))
                .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CONSENT_TEMPLATE_NOT_FOUND"))
        }
        mockMvc.perform(call(post("/api/v1/consent-types/import-template").contentType(MediaType.APPLICATION_JSON).content("{}"), clinic)).andExpect(status().isBadRequest)
    }

    @Test
    fun `importing a second template that controls messaging is a conflict`() {
        val clinic = newClinic()
        val first = createTemplate("Mensagens A", messaging = true)
        val second = createTemplate("Mensagens B", messaging = true)
        mockMvc.perform(call(post("/api/v1/consent-types/import-template").contentType(MediaType.APPLICATION_JSON).content(body("templateId" to first)), clinic)).andExpect(status().isCreated)
        mockMvc.perform(call(post("/api/v1/consent-types/import-template").contentType(MediaType.APPLICATION_JSON).content(body("templateId" to second)), clinic))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONSENT_MESSAGING_TYPE_EXISTS"))
    }
}
