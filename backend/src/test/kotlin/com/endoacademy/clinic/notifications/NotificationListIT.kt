package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.ClinicApiIT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** `GET /notifications` de ponta a ponta (T5.10): o andamento sem destinatário nem texto, e isolado por clínica. */
class NotificationListIT : ClinicApiIT() {

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    private fun newType(clinic: Clinic, messaging: Boolean = true): UUID = idOf(
        mockMvc.perform(
            call(post("/api/v1/consent-types").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Tipo ${UUID.randomUUID()}", "text" to "Texto sigiloso do consentimento", "requiredOnSignup" to true, "controlsMessaging" to messaging)), clinic),
        ).andExpect(status().isCreated),
    )

    private fun register(clinic: Clinic, type: UUID, cpf: String, whatsapp: String? = "(11) 98765-4321", email: String? = "maria.sigilosa@example.com"): UUID = idOf(
        mockMvc.perform(
            call(
                post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(
                    json.writeValueAsString(
                        mapOf(
                            "cpf" to cpf, "fullName" to "Maria Sigilosa da Silva", "birthDate" to "1985-03-12", "whatsapp" to whatsapp, "email" to email,
                            "address" to mapOf("zipCode" to "01310-100", "street" to "Avenida Paulista", "number" to "1578", "district" to "Bela Vista", "city" to "São Paulo", "state" to "SP"),
                            "consents" to listOf(mapOf("typeId" to type)),
                        ),
                    ),
                ),
                clinic,
            ),
        ).andExpect(status().isCreated),
    )

    private fun count(clinic: Clinic, patient: UUID) = TenantScope.runAs(clinic.id) {
        tx.execute { jdbc.queryForObject("select count(*) from notifications.notification where patient_id = ?", Int::class.java, patient) }!!
    }

    private fun clinicWithNotifications(): Triple<Clinic, UUID, UUID> {
        val clinic = newClinic()
        val type = newType(clinic)
        val maria = register(clinic, type, "529.982.247-25")
        val joao = register(clinic, type, "111.444.777-35", whatsapp = null)
        awaitUntil("as notificações") { count(clinic, maria) == 2 && count(clinic, joao) == 1 }
        return Triple(clinic, maria, joao)
    }

    @Test
    fun `lists the notifications with channel, status and attempts, and nothing about the recipient or the text`() {
        val (clinic, maria, _) = clinicWithNotifications()
        val response = mockMvc.perform(call(get("/api/v1/notifications?patientId=$maria"), clinic)).andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].status").value("PENDING")).andExpect(jsonPath("$.items[0].attempts").value(0))
            .andExpect(jsonPath("$.items[0].kind").value("CONSENT_ACCEPTED")).andExpect(jsonPath("$.items[0].patientId").value(maria.toString()))
            .andExpect(jsonPath("$.items[?(@.channel == 'EMAIL')]").exists()).andExpect(jsonPath("$.items[?(@.channel == 'WHATSAPP')]").exists())
            .andReturn().response.contentAsString
        val fields = json.readTree(response).get("items").get(0).propertyNames().toSet()
        assertThat(fields).containsExactlyInAnyOrder("id", "patientId", "kind", "channel", "status", "attempts", "nextAttemptAt", "lastErrorCode", "createdAt")
        assertThat(response).doesNotContain("sigilosa").doesNotContain("Sigilosa").doesNotContain("98765").doesNotContain("example.com").doesNotContain("sigiloso").doesNotContain("52998224725")
    }

    @Test
    fun `filters by status, channel and patient, and pages`() {
        val (clinic, maria, joao) = clinicWithNotifications()
        mockMvc.perform(call(get("/api/v1/notifications"), clinic)).andExpect(jsonPath("$.total").value(3))
        mockMvc.perform(call(get("/api/v1/notifications?channel=WHATSAPP"), clinic)).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].patientId").value(maria.toString()))
        mockMvc.perform(call(get("/api/v1/notifications?channel=EMAIL&patientId=$joao"), clinic)).andExpect(jsonPath("$.total").value(1))
        mockMvc.perform(call(get("/api/v1/notifications?status=PENDING"), clinic)).andExpect(jsonPath("$.total").value(3))
        mockMvc.perform(call(get("/api/v1/notifications?status=SENT"), clinic)).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items.length()").value(0))
        mockMvc.perform(call(get("/api/v1/notifications?size=2&page=0"), clinic)).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.total").value(3))
        mockMvc.perform(call(get("/api/v1/notifications?size=2&page=1"), clinic))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.total").value(3))
    }

    @Test
    fun `lists the newest first`() {
        val (clinic, maria, _) = clinicWithNotifications()
        val ids = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForList("select id from notifications.notification where patient_id = ? order by channel", UUID::class.java, maria) }!!.filterNotNull()
        }
        // dá a cada uma um instante de criação diferente: a segunda é a mais antiga, a primeira a mais nova
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult {
                jdbc.update("update notifications.notification set created_at = now() + interval '1 hour' where id = ?", ids[0])
                jdbc.update("update notifications.notification set created_at = now() - interval '1 hour' where id = ?", ids[1])
            }
        }
        val order = json.readTree(mockMvc.perform(call(get("/api/v1/notifications?patientId=$maria"), clinic)).andReturn().response.contentAsString).get("items")
        assertThat(order.get(0).get("id").asString()).isEqualTo(ids[0].toString())
        assertThat(order.get(1).get("id").asString()).isEqualTo(ids[1].toString())
    }

    @Test
    fun `reflects what the worker did`() {
        val (clinic, maria, _) = clinicWithNotifications()
        TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult {
                jdbc.update("update notifications.notification set status = 'SENT', attempts = 1, next_attempt_at = null where patient_id = ? and channel = 'EMAIL'", maria)
                jdbc.update(
                    "update notifications.notification set attempts = 2, last_error_code = 'EMAIL_UNAVAILABLE', next_attempt_at = now() + interval '5 minutes' where patient_id = ? and channel = 'WHATSAPP'",
                    maria,
                )
            }
        }
        mockMvc.perform(call(get("/api/v1/notifications?status=SENT&patientId=$maria"), clinic)).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].attempts").value(1)).andExpect(jsonPath("$.items[0].nextAttemptAt").doesNotExist())
        mockMvc.perform(call(get("/api/v1/notifications?status=PENDING&patientId=$maria"), clinic))
            .andExpect(jsonPath("$.items[0].attempts").value(2)).andExpect(jsonPath("$.items[0].lastErrorCode").value("EMAIL_UNAVAILABLE")).andExpect(jsonPath("$.items[0].nextAttemptAt").exists())
    }

    @Test
    fun `bad filters and paging are 400`() {
        val clinic = newClinic()
        listOf("status=EXPLODED", "channel=PIGEON", "patientId=nao-e-uuid", "size=0", "size=101", "page=-1").forEach {
            mockMvc.perform(call(get("/api/v1/notifications?$it"), clinic)).andExpect(status().isBadRequest)
        }
        mockMvc.perform(call(get("/api/v1/notifications?size=100"), clinic)).andExpect(status().isOk).andExpect(jsonPath("$.size").value(100))
    }

    @Test
    fun `another clinic sees none of them`() {
        val (_, maria, _) = clinicWithNotifications()
        val other = newClinic()
        mockMvc.perform(call(get("/api/v1/notifications"), other)).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items.length()").value(0))
        mockMvc.perform(call(get("/api/v1/notifications?patientId=$maria"), other)).andExpect(jsonPath("$.total").value(0))
    }

    @Test
    fun `needs VIEW_NOTIFICATIONS, denials are audited and each listing is audited with the count`() {
        val (clinic, _, _) = clinicWithNotifications()
        val viewer = memberWithPermissions(clinic, "VIEW_NOTIFICATIONS")
        val outsider = memberWithPermissions(clinic, "VIEW_PATIENTS", "MANAGE_PATIENTS")

        mockMvc.perform(call(get("/api/v1/notifications"), clinic, viewer)).andExpect(status().isOk)
        mockMvc.perform(call(get("/api/v1/notifications"), clinic, outsider)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        val denied = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForObject("select count(*) from platform.audit_event where outcome = 'DENIED' and entity_id = 'VIEW_NOTIFICATIONS'", Int::class.java) }!!
        }
        assertThat(denied).isEqualTo(1)
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized)

        assertThat(auditCount(clinic, "LIST_NOTIFICATIONS")).isEqualTo(1)
        val detail = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForObject("select details::text from platform.audit_event where action = 'LIST_NOTIFICATIONS'", String::class.java) }!!
        }
        assertThat(detail).contains("count.3").doesNotContain("sigilos")
    }

    @Test
    fun `the clinic admin has the permission by default and the other templates do not`() {
        val clinic = newClinic()
        val granted = TenantScope.runAs(clinic.id) {
            tx.execute {
                jdbc.query("select r.name, rp.permission from platform.role r join platform.role_permission rp on rp.role_id = r.id", { rs, _ -> rs.getString(1)!! to rs.getString(2)!! })
                    .groupBy({ it.first }, { it.second })
            }!!
        }
        assertThat(granted.getValue("CLINIC_ADMIN")).contains("VIEW_NOTIFICATIONS")
        listOf("DOCTOR", "NURSE", "MANAGER", "STAFF").forEach { assertThat(granted.getValue(it)).doesNotContain("VIEW_NOTIFICATIONS") }
    }
}
