package com.endoacademy.clinic.platform.adapter.`in`.web

import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.application.AuditFilter
import com.endoacademy.clinic.platform.application.QueryAuditTrail
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Trilha de auditoria da clínica ativa (`X-Clinic-Id`). */
@RestController
@RequestMapping("/api/v1")
class AuditController(private val queryAuditTrail: QueryAuditTrail) {

    data class AuditEventResponse(
        val id: UUID,
        val occurredAt: Instant,
        val userId: UUID?,
        val action: String,
        val entityType: String,
        val entityId: String?,
        val outcome: String,
        val details: List<String>,
    )

    data class AuditPageResponse(val items: List<AuditEventResponse>, val page: Int, val size: Int, val total: Long)

    @GetMapping("/audit-events")
    fun list(
        request: HttpServletRequest,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) userId: UUID?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) entityType: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): AuditPageResponse {
        val user = request.getAttribute(ClinicContextFilter.CURRENT_USER) as CurrentUser
        val result = queryAuditTrail.query(user, AuditFilter(from, to, userId, action, entityType), page, size)
        return AuditPageResponse(
            items = result.items.map { AuditEventResponse(it.id, it.occurredAt, it.userId, it.action, it.entityType, it.entityId, it.outcome, it.details) },
            page = result.page,
            size = result.size,
            total = result.total,
        )
    }
}
