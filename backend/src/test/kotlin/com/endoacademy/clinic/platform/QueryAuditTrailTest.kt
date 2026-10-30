package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditOutcome
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.api.PermissionDefinition
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.platform.application.AuditFilter
import com.endoacademy.clinic.platform.application.AuditQueryStore
import com.endoacademy.clinic.platform.application.AuditRecord
import com.endoacademy.clinic.platform.application.PageOf
import com.endoacademy.clinic.platform.application.PermissionAuthorizer
import com.endoacademy.clinic.platform.application.PermissionCatalog
import com.endoacademy.clinic.platform.application.QueryAuditTrail
import com.endoacademy.clinic.platform.application.Transactions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class QueryAuditTrailTest {

    private class FakeStore : AuditQueryStore {
        var lastFilter: AuditFilter? = null
        var lastPage = -1
        var lastSize = -1
        override fun query(filter: AuditFilter, page: Int, size: Int): PageOf<AuditRecord> {
            lastFilter = filter
            lastPage = page
            lastSize = size
            return PageOf(emptyList(), page, size, 0)
        }
    }

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<AuditEvent>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += event
        }
    }

    private val catalog = PermissionCatalog(
        listOf(object : PermissionContributor {
            override fun permissions() = listOf(PermissionDefinition("VIEW_AUDIT_TRAIL", "auditoria"))
        }),
    )
    private val store = FakeStore()
    private val audit = RecordingAudit()
    private val useCase = QueryAuditTrail(PermissionAuthorizer(catalog, audit), store, object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    })
    private val viewer = CurrentUser(UUID.randomUUID(), "sub", UUID.randomUUID(), setOf("VIEW_AUDIT_TRAIL"))

    @Test
    fun `defaults to the first page of 20 and passes the filter through`() {
        val filter = AuditFilter(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z"), UUID.randomUUID(), "LOGIN", "SESSION")

        useCase.query(viewer, filter, null, null)

        assertThat(store.lastFilter).isEqualTo(filter)
        assertThat(store.lastPage).isZero()
        assertThat(store.lastSize).isEqualTo(20)
    }

    @Test
    fun `accepts sizes from 1 to 100 and rejects the rest`() {
        useCase.query(viewer, AuditFilter(), 3, 100)
        assertThat(store.lastPage).isEqualTo(3)
        assertThat(store.lastSize).isEqualTo(100)
        useCase.query(viewer, AuditFilter(), 0, 1)
        assertThat(store.lastSize).isEqualTo(1)

        listOf(0 to 0, 0 to 101, -1 to 20, 0 to -5).forEach { (page, size) ->
            assertThatThrownBy { useCase.query(viewer, AuditFilter(), page, size) }
                .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `a period that ends before it starts is rejected`() {
        assertThatThrownBy { useCase.query(viewer, AuditFilter(from = Instant.parse("2026-10-02T00:00:00Z"), to = Instant.parse("2026-10-01T00:00:00Z")), null, null) }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("VALIDATION_FAILED") }
    }

    @Test
    fun `without VIEW_AUDIT_TRAIL the query is refused, audited as DENIED and the store is not touched`() {
        val nobody = viewer.copy(permissions = emptySet())

        assertThatThrownBy { useCase.query(nobody, AuditFilter(), null, null) }.isInstanceOf(PermissionDeniedException::class.java)

        assertThat(store.lastPage).isEqualTo(-1)
        assertThat(audit.events.single().outcome).isEqualTo(AuditOutcome.DENIED)
    }
}
