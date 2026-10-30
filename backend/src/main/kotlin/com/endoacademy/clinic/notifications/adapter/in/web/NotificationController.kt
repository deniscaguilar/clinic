package com.endoacademy.clinic.notifications.adapter.`in`.web

import com.endoacademy.clinic.notifications.application.ListNotifications
import com.endoacademy.clinic.notifications.application.NotificationFilter
import com.endoacademy.clinic.notifications.application.NotificationView
import com.endoacademy.clinic.notifications.domain.NotificationChannel
import com.endoacademy.clinic.notifications.domain.NotificationStatus
import com.endoacademy.clinic.platform.api.CurrentUser
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Andamento dos envios da clínica ativa (`X-Clinic-Id`). A resposta tem ids, canal, status e contadores: **nunca** o
 * destinatário nem o texto, que nem ficam gravados. A autorização e a auditoria ficam no caso de uso.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val listNotifications: ListNotifications) {

    data class NotificationResponse(
        val id: UUID,
        val patientId: UUID,
        val kind: String,
        val channel: String,
        val status: String,
        val attempts: Int,
        val nextAttemptAt: Instant?,
        val lastErrorCode: String?,
        val createdAt: Instant,
    )

    data class NotificationPageResponse(val items: List<NotificationResponse>, val page: Int, val size: Int, val total: Long)

    private fun NotificationView.toResponse() = NotificationResponse(id, patientId, kind.name, channel.name, status.name, attempts, nextAttemptAt, lastErrorCode, createdAt)

    @GetMapping
    fun list(
        request: HttpServletRequest,
        @RequestParam(required = false) status: NotificationStatus?,
        @RequestParam(required = false) channel: NotificationChannel?,
        @RequestParam(required = false) patientId: UUID?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): NotificationPageResponse {
        val user = request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE) as CurrentUser
        val result = listNotifications.list(user, NotificationFilter(status, channel, patientId), page, size)
        return NotificationPageResponse(result.items.map { it.toResponse() }, result.page, result.size, result.total)
    }
}
