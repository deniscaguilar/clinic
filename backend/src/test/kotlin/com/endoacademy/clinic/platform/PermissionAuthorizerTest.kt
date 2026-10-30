package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.api.PermissionDefinition
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.platform.application.PermissionAuthorizer
import com.endoacademy.clinic.platform.application.PermissionCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class PermissionAuthorizerTest {

    private class RecordingAuditTrail : AuditTrail {
        val events = mutableListOf<Pair<CurrentUser?, AuditEvent>>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += user to event
        }
    }

    private val catalog = PermissionCatalog(
        listOf(
            object : PermissionContributor {
                override fun permissions() = listOf(PermissionDefinition("VIEW_PATIENTS", "ver"), PermissionDefinition("MANAGE_USERS", "usuários"))
            },
        ),
    )
    private val audit = RecordingAuditTrail()
    private val authorizer = PermissionAuthorizer(catalog, audit)
    private val user = CurrentUser(UUID.randomUUID(), "sub-1", UUID.randomUUID(), setOf("VIEW_PATIENTS"))

    @Test
    fun `allows a user who has the permission and records nothing`() {
        authorizer.require(user, "VIEW_PATIENTS")

        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `denies a user without the permission and records DENIED`() {
        assertThatThrownBy { authorizer.require(user, "MANAGE_USERS") }
            .isInstanceOf(PermissionDeniedException::class.java)
            .extracting { (it as PermissionDeniedException).permission }.isEqualTo("MANAGE_USERS")

        val (who, event) = audit.events.single()
        assertThat(who).isEqualTo(user)
        assertThat(event.outcome).isEqualTo(AuditOutcome.DENIED)
        assertThat(event.entityId).isEqualTo("MANAGE_USERS")
        assertThat(event.details).isEmpty()
    }

    @Test
    fun `requiring a permission that is not in the catalog is a programming error`() {
        assertThatThrownBy { authorizer.require(user, "FLY_PLANES") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("FLY_PLANES")
        assertThat(audit.events).isEmpty()
    }
}
