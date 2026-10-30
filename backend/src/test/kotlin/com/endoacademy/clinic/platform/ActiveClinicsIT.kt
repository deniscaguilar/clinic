package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.ActiveClinics
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.ClinicApiIT
import com.endoacademy.clinic.support.TenantFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** As clínicas ativas para o trabalho de fundo (T5.9): só ids, só as ativas, sem abrir mais nada. */
class ActiveClinicsIT : ClinicApiIT() {

    @Autowired
    lateinit var activeClinics: ActiveClinics

    @Test
    fun `lists the active clinics and leaves out the deactivated ones`() {
        val active = newClinic()
        val inactive = newClinic()
        mockMvc.perform(post("/api/v1/admin/clinics/${inactive.id}/deactivate").header("Authorization", bearer(platformAdminSub))).andExpect(status().isNoContent)

        val ids = activeClinics.ids()
        assertThat(ids).contains(active.id).doesNotContain(inactive.id)
        assertThat(ids).doesNotHaveDuplicates()

        mockMvc.perform(post("/api/v1/admin/clinics/${inactive.id}/activate").header("Authorization", bearer(platformAdminSub))).andExpect(status().isNoContent)
        assertThat(activeClinics.ids()).contains(inactive.id)
    }

    @Test
    fun `needs no context from the caller and still opens nothing else`() {
        val clinic = newClinic()
        TenantScope.runAs(clinic.id) { tx.executeWithoutResult { TenantFixtures.newPatient(jdbc, clinic.id) } }
        assertThat(activeClinics.ids()).contains(clinic.id)
        val patientsSeenByPlatform = TenantScope.runAsPlatform { tx.execute { jdbc.queryForObject("select count(*) from registry.patient", Int::class.java) }!! }
        assertThat(patientsSeenByPlatform).isZero()
        assertThat(TenantScope.current()).describedAs("o contexto não vaza para depois da chamada").isNull()
    }
}
