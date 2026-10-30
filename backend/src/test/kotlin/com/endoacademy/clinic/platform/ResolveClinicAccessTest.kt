package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.application.ClinicAccessResult
import com.endoacademy.clinic.platform.application.DenialReason
import com.endoacademy.clinic.platform.application.MembershipAccess
import com.endoacademy.clinic.platform.application.MembershipAccessReader
import com.endoacademy.clinic.platform.application.ResolveClinicAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ResolveClinicAccessTest {
    private val userId = UUID.randomUUID()
    private val clinic = UUID.randomUUID()

    private fun resolver(access: MembershipAccess?) = ResolveClinicAccess(MembershipAccessReader { _, _ -> access })

    @Test
    fun `grants access with the permissions of the membership`() {
        val result = resolver(MembershipAccess(userId, true, true, setOf("VIEW_PATIENTS"))).resolve("sub-1", clinic)

        val user = (result as ClinicAccessResult.Granted).user
        assertThat(user.userId).isEqualTo(userId)
        assertThat(user.userSub).isEqualTo("sub-1")
        assertThat(user.clinicId).isEqualTo(clinic)
        assertThat(user.has("VIEW_PATIENTS")).isTrue()
        assertThat(user.has("MANAGE_USERS")).isFalse()
    }

    @Test
    fun `denies when there is no membership`() {
        assertThat(resolver(null).resolve("sub-1", clinic)).isEqualTo(ClinicAccessResult.Denied(DenialReason.NO_MEMBERSHIP))
    }

    @Test
    fun `denies when the membership is inactive`() {
        val result = resolver(MembershipAccess(userId, false, true, emptySet())).resolve("sub-1", clinic)
        assertThat(result).isEqualTo(ClinicAccessResult.Denied(DenialReason.INACTIVE_MEMBERSHIP))
    }

    @Test
    fun `denies when the clinic is inactive`() {
        val result = resolver(MembershipAccess(userId, true, false, setOf("VIEW_PATIENTS"))).resolve("sub-1", clinic)
        assertThat(result).isEqualTo(ClinicAccessResult.Denied(DenialReason.INACTIVE_CLINIC))
    }
}
