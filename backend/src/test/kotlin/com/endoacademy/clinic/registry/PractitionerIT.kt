package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.registry.application.PractitionerStore
import com.endoacademy.clinic.registry.domain.Practitioner
import com.endoacademy.clinic.registry.domain.PractitionerType
import com.endoacademy.clinic.registry.domain.WorkSchedule
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

/** Profissionais e horários de ponta a ponta (T4.4; CA11, CA1 e CA2 aplicados ao profissional). */
class PractitionerIT : ClinicApiIT() {

    @Autowired
    lateinit var store: PractitionerStore

    private fun interval(day: String, start: String, end: String) = mapOf("dayOfWeek" to day, "start" to start, "end" to end)

    private fun register(
        clinic: Clinic,
        name: String = "Dra. Ana Souza",
        type: String = "DOCTOR",
        userId: UUID? = null,
        schedule: List<Map<String, String>> = emptyList(),
        sub: String = clinic.adminSub,
    ): ResultActions = mockMvc.perform(
        call(
            post("/api/v1/practitioners").contentType(MediaType.APPLICATION_JSON)
                .content(body("name" to name, "type" to type, "registrationNumber" to "CRM 1", "userId" to userId, "workSchedule" to schedule)),
            clinic,
            sub,
        ),
    )

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    private fun userIdOf(sub: String): UUID = TenantScope.runAsPlatform {
        tx.execute { jdbc.queryForObject("select id from platform.app_user where sub = ?", UUID::class.java, sub) }!!
    }

    /** Dá ao usuário um vínculo com a clínica (a lista de usuários e o convite por API são da S2). */
    private fun addMembership(clinic: Clinic, userId: UUID, status: String = "ACTIVE") {
        TenantScope.runAsPlatform {
            tx.executeWithoutResult {
                jdbc.update("insert into platform.membership (id, tenant_id, user_id, status) values (?, ?, ?, ?)", UUID.randomUUID(), clinic.id, userId, status)
            }
        }
    }

    @Test
    fun `the clinic admin registers, reads, updates, inactivates and filters practitioners with their schedule`() {
        val clinic = newClinic()
        val doctorSub = memberWithPermissions(clinic, "MANAGE_PROCEDURES")
        val ana = idOf(
            register(clinic, userId = userIdOf(doctorSub), schedule = listOf(interval("MONDAY", "14:00", "18:00"), interval("MONDAY", "08:00", "12:00"), interval("friday", "09:00", "13:00")))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.type").value("DOCTOR"))
                .andExpect(jsonPath("$.workSchedule.length()").value(3))
                .andExpect(jsonPath("$.workSchedule[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.workSchedule[0].start").value("08:00"))
                .andExpect(jsonPath("$.workSchedule[2].dayOfWeek").value("FRIDAY")),
        )
        val bia = idOf(register(clinic, name = "Enf. Bia Lima", type = "NURSE").andExpect(status().isCreated).andExpect(jsonPath("$.userId").doesNotExist()))

        mockMvc.perform(call(get("/api/v1/practitioners/$ana"), clinic)).andExpect(status().isOk)
            .andExpect(jsonPath("$.registrationNumber").value("CRM 1")).andExpect(jsonPath("$.userId").value(userIdOf(doctorSub).toString()))
            .andExpect(jsonPath("$.workSchedule[1].start").value("14:00"))
        mockMvc.perform(
            call(
                put("/api/v1/practitioners/$bia").contentType(MediaType.APPLICATION_JSON)
                    .content(body("name" to "Enf. Bia Lima", "type" to "NURSE", "active" to false, "workSchedule" to listOf(interval("TUESDAY", "07:00", "11:00")))),
                clinic,
            ),
        ).andExpect(status().isOk).andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.workSchedule.length()").value(1)).andExpect(jsonPath("$.workSchedule[0].dayOfWeek").value("TUESDAY"))

        mockMvc.perform(call(get("/api/v1/practitioners"), clinic)).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].name").value("Dra. Ana Souza"))
        mockMvc.perform(call(get("/api/v1/practitioners?active=true"), clinic)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Dra. Ana Souza"))
        mockMvc.perform(call(get("/api/v1/practitioners?active=false"), clinic)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Enf. Bia Lima"))
    }

    @Test
    fun `a doctor in two clinics is one practitioner per clinic, each with its own schedule (CA11)`() {
        val a = newClinic()
        val b = newClinic()
        val doctorSub = memberWithPermissions(a, "MANAGE_PROCEDURES")
        val doctor = userIdOf(doctorSub)
        addMembership(b, doctor)

        val inA = idOf(register(a, userId = doctor, schedule = listOf(interval("MONDAY", "08:00", "12:00"))).andExpect(status().isCreated))
        val inB = idOf(register(b, userId = doctor, schedule = listOf(interval("THURSDAY", "14:00", "19:00"))).andExpect(status().isCreated))
        assertThat(inA).isNotEqualTo(inB)

        mockMvc.perform(call(get("/api/v1/practitioners/$inA"), a)).andExpect(jsonPath("$.workSchedule.length()").value(1)).andExpect(jsonPath("$.workSchedule[0].dayOfWeek").value("MONDAY"))
        mockMvc.perform(call(get("/api/v1/practitioners/$inB"), b)).andExpect(jsonPath("$.workSchedule.length()").value(1)).andExpect(jsonPath("$.workSchedule[0].dayOfWeek").value("THURSDAY"))
        mockMvc.perform(call(get("/api/v1/practitioners"), a)).andExpect(jsonPath("$.length()").value(1))
        mockMvc.perform(call(get("/api/v1/practitioners"), b)).andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `only an active member of the clinic can be linked, and only to one practitioner`() {
        val a = newClinic()
        val b = newClinic()
        val ofB = userIdOf(memberWithPermissions(b, "MANAGE_PROCEDURES"))
        val pending = userIdOf(memberWithPermissions(b, "MANAGE_PROCEDURES")).also { addMembership(a, it, "PENDING") }
        val inactive = userIdOf(memberWithPermissions(b, "MANAGE_PROCEDURES")).also { addMembership(a, it, "INACTIVE") }
        val member = userIdOf(memberWithPermissions(a, "MANAGE_PROCEDURES"))

        listOf(ofB, pending, inactive, UUID.randomUUID()).forEach {
            register(a, userId = it).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PRACTITIONER_USER_NOT_MEMBER"))
        }
        mockMvc.perform(call(get("/api/v1/practitioners"), a)).andExpect(jsonPath("$.length()").value(0))

        val first = idOf(register(a, userId = member).andExpect(status().isCreated))
        register(a, name = "Outra Pessoa", userId = member).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PRACTITIONER_USER_ALREADY_LINKED"))
        mockMvc.perform(
            call(put("/api/v1/practitioners/$first").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Dra. Ana Souza Lima", "type" to "DOCTOR", "userId" to member, "active" to true)), a),
        ).andExpect(status().isOk).andExpect(jsonPath("$.userId").value(member.toString()))
    }

    @Test
    fun `an overlapping schedule is rejected by the API and by the database`() {
        val clinic = newClinic()
        register(clinic, schedule = listOf(interval("MONDAY", "08:00", "12:00"), interval("MONDAY", "11:00", "13:00")))
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("WORK_SCHEDULE_OVERLAP"))
        register(clinic, schedule = listOf(interval("MONDAY", "08:00", "12:00"), interval("MONDAY", "12:00", "13:00"), interval("TUESDAY", "08:00", "12:00"))).andExpect(status().isCreated)
        mockMvc.perform(call(get("/api/v1/practitioners"), clinic)).andExpect(jsonPath("$.length()").value(1))

        // direto no banco, sem passar pelo domínio
        val id = idOf(register(clinic, name = "Dr. Bruno Dias").andExpect(status().isCreated))
        fun insert(day: Int, start: String, end: String) = TenantScope.runAs(clinic.id) {
            tx.executeWithoutResult {
                jdbc.update(
                    "insert into registry.practitioner_work_schedule (tenant_id, practitioner_id, day_of_week, start_time, end_time) values (?, ?, ?, cast(? as time), cast(? as time))",
                    clinic.id, id, day, start, end,
                )
            }
        }
        insert(1, "08:00", "12:00")
        insert(1, "12:00", "13:00")
        insert(2, "08:00", "12:00")
        assertThatThrownBy { insert(1, "11:00", "12:30") }.rootCause().hasMessageContaining("overlaps")
        assertThatThrownBy { insert(1, "07:00", "08:01") }.rootCause().hasMessageContaining("overlaps")
        assertThatThrownBy { insert(3, "12:00", "08:00") }.rootCause().hasMessageContaining("violates check constraint")
        assertThatThrownBy { insert(8, "08:00", "09:00") }.rootCause().hasMessageContaining("violates check constraint")
        assertThatThrownBy {
            TenantScope.runAs(clinic.id) { tx.executeWithoutResult { jdbc.update("update registry.practitioner_work_schedule set end_time = '23:00' where practitioner_id = ?", id) } }
        }.rootCause().hasMessageContaining("permission denied")
    }

    @Test
    fun `invalid input is rejected without echoing values and nothing is stored`() {
        val clinic = newClinic()
        register(clinic, name = "  ").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PRACTITIONER_NAME_REQUIRED"))
        register(clinic, type = "WIZARD").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PRACTITIONER_TYPE_INVALID"))
        register(clinic, schedule = listOf(interval("FUNDAY", "08:00", "12:00"))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_INTERVAL_INVALID"))
        register(clinic, schedule = listOf(interval("MONDAY", "8h", "12:00"))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_INTERVAL_INVALID"))
        register(clinic, schedule = listOf(interval("MONDAY", "12:00", "08:00"))).andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("WORK_INTERVAL_INVALID"))
        mockMvc.perform(call(post("/api/v1/practitioners").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Dra. Ana")), clinic)).andExpect(status().isBadRequest)
        mockMvc.perform(call(get("/api/v1/practitioners"), clinic)).andExpect(jsonPath("$.length()").value(0))

        val id = idOf(register(clinic).andExpect(status().isCreated))
        mockMvc.perform(call(put("/api/v1/practitioners/$id").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Dra. Ana", "type" to "DOCTOR")), clinic))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        mockMvc.perform(call(get("/api/v1/practitioners/$id"), clinic)).andExpect(jsonPath("$.name").value("Dra. Ana Souza"))
    }

    @Test
    fun `a practitioner of another clinic answers 404 and is untouched (CA2)`() {
        val a = newClinic()
        val b = newClinic()
        val ofB = idOf(register(b, name = "Dr. Só da B", schedule = listOf(interval("MONDAY", "08:00", "12:00"))).andExpect(status().isCreated))

        mockMvc.perform(call(get("/api/v1/practitioners/$ofB"), a)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PRACTITIONER_NOT_FOUND"))
        mockMvc.perform(
            call(put("/api/v1/practitioners/$ofB").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Invadido", "type" to "OTHER", "active" to false)), a),
        ).andExpect(status().isNotFound)
        mockMvc.perform(call(get("/api/v1/practitioners/$ofB"), b)).andExpect(jsonPath("$.name").value("Dr. Só da B")).andExpect(jsonPath("$.workSchedule.length()").value(1))
        mockMvc.perform(call(get("/api/v1/practitioners"), a)).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `only MANAGE_PRACTITIONERS opens the endpoints and the denials are audited`() {
        val clinic = newClinic()
        val id = idOf(register(clinic).andExpect(status().isCreated))
        val outsider = memberWithPermissions(clinic, "MANAGE_PROCEDURES")
        val manager = memberWithPermissions(clinic, "MANAGE_PRACTITIONERS")

        register(clinic, name = "Dr. Intruso", sub = outsider).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        mockMvc.perform(call(get("/api/v1/practitioners"), clinic, outsider)).andExpect(status().isForbidden)
        mockMvc.perform(call(get("/api/v1/practitioners/$id"), clinic, outsider)).andExpect(status().isForbidden)
        mockMvc.perform(call(put("/api/v1/practitioners/$id").contentType(MediaType.APPLICATION_JSON).content(body("name" to "X Y", "type" to "OTHER", "active" to true)), clinic, outsider))
            .andExpect(status().isForbidden)
        val denied = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForObject("select count(*) from platform.audit_event where outcome = 'DENIED' and entity_id = 'MANAGE_PRACTITIONERS'", Int::class.java) }!!
        }
        assertThat(denied).isEqualTo(4)

        register(clinic, name = "Dr. do Gerente", sub = manager).andExpect(status().isCreated)
        mockMvc.perform(call(get("/api/v1/practitioners"), clinic, manager)).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(2))
        mockMvc.perform(get("/api/v1/practitioners")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `registering and updating are audited with field names only`() {
        val clinic = newClinic()
        val id = idOf(register(clinic, name = "Nome sigiloso do profissional").andExpect(status().isCreated))
        mockMvc.perform(
            call(put("/api/v1/practitioners/$id").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Outro nome sigiloso", "type" to "DOCTOR", "active" to false)), clinic),
        ).andExpect(status().isOk)

        assertThat(auditCount(clinic, "REGISTER_PRACTITIONER")).isEqualTo(1)
        assertThat(auditCount(clinic, "UPDATE_PRACTITIONER")).isEqualTo(1)
        val stored = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForList("select details::text || coalesce(entity_id, '') from platform.audit_event where entity_type = 'PRACTITIONER'", String::class.java) }!!
        }
        assertThat(stored).hasSize(2)
        stored.forEach { assertThat(it).doesNotContain("sigiloso").doesNotContain("CRM 1") }
    }

    @Test
    fun `updating replaces the whole schedule, the old intervals do not stay`() {
        val clinic = newClinic()
        val id = idOf(register(clinic, schedule = listOf(interval("MONDAY", "08:00", "12:00"), interval("WEDNESDAY", "14:00", "18:00"))).andExpect(status().isCreated))
        mockMvc.perform(
            call(
                put("/api/v1/practitioners/$id").contentType(MediaType.APPLICATION_JSON)
                    .content(body("name" to "Dra. Ana Souza", "type" to "DOCTOR", "active" to true, "workSchedule" to listOf(interval("FRIDAY", "09:00", "13:00")))),
                clinic,
            ),
        ).andExpect(status().isOk).andExpect(jsonPath("$.workSchedule.length()").value(1)).andExpect(jsonPath("$.workSchedule[0].dayOfWeek").value("FRIDAY"))
        mockMvc.perform(call(get("/api/v1/practitioners/$id"), clinic)).andExpect(jsonPath("$.workSchedule.length()").value(1)).andExpect(jsonPath("$.workSchedule[0].dayOfWeek").value("FRIDAY"))
        val rows = TenantScope.runAs(clinic.id) {
            tx.execute { jdbc.queryForObject("select count(*) from registry.practitioner_work_schedule where practitioner_id = ?", Int::class.java, id) }!!
        }
        assertThat(rows).isEqualTo(1)

        mockMvc.perform(
            call(put("/api/v1/practitioners/$id").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Dra. Ana Souza", "type" to "DOCTOR", "active" to true)), clinic),
        ).andExpect(status().isOk).andExpect(jsonPath("$.workSchedule.length()").value(0))
    }

    @Test
    fun `the database unique index turns a race on the user link into a conflict, not a server error`() {
        val clinic = newClinic()
        val user = UUID.randomUUID()
        fun practitioner(name: String) = Practitioner.register(UUID.randomUUID(), name, PractitionerType.DOCTOR, null, user, WorkSchedule.of(emptyList()))
        TenantScope.runAs(clinic.id) { tx.executeWithoutResult { store.insert(clinic.id, practitioner("Primeiro Profissional")) } }
        assertThatThrownBy {
            TenantScope.runAs(clinic.id) { tx.executeWithoutResult { store.insert(clinic.id, practitioner("Segundo Profissional")) } }
        }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
            assertThat(it.code).isEqualTo("PRACTITIONER_USER_ALREADY_LINKED")
        }
    }
}
