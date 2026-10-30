package com.endoacademy.clinic.notifications.application

import com.endoacademy.clinic.notifications.domain.NotificationChannel
import com.endoacademy.clinic.notifications.domain.NotificationKind
import com.endoacademy.clinic.notifications.domain.NotificationStatus
import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import java.time.Instant
import java.util.UUID

/**
 * Uma notificação como a tela a mostra: ids, canal, status, tentativas, próxima tentativa, último código de erro e
 * quando foi criada. **Nunca o destinatário nem o texto**: eles não ficam gravados.
 */
data class NotificationView(
    val id: UUID,
    val patientId: UUID,
    val kind: NotificationKind,
    val channel: NotificationChannel,
    val status: NotificationStatus,
    val attempts: Int,
    val nextAttemptAt: Instant?,
    val lastErrorCode: String?,
    val createdAt: Instant,
)

data class NotificationFilter(val status: NotificationStatus? = null, val channel: NotificationChannel? = null, val patientId: UUID? = null)

data class NotificationPage(val items: List<NotificationView>, val page: Int, val size: Int, val total: Long)

/** Leitura da fila da clínica do contexto, da mais recente para a mais antiga. Roda na transação do [Transactions]. */
interface NotificationQueryStore {
    fun list(filter: NotificationFilter, offset: Int, limit: Int): List<NotificationView>

    fun count(filter: NotificationFilter): Long
}

private const val VIEW_NOTIFICATIONS = "VIEW_NOTIFICATIONS"
private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100

/** `GET /notifications` (RF13): o andamento dos envios da clínica. Cada consulta é um evento de auditoria com a contagem. */
class ListNotifications(
    private val authorizer: Authorizer,
    private val store: NotificationQueryStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun list(user: CurrentUser, filter: NotificationFilter, page: Int?, size: Int?): NotificationPage {
        authorizer.require(user, VIEW_NOTIFICATIONS)
        val pageNumber = page ?: 0
        val pageSize = size ?: DEFAULT_PAGE_SIZE
        if (pageNumber < 0 || pageSize !in 1..MAX_PAGE_SIZE) throw ApiException(ErrorKind.BAD_REQUEST, "VALIDATION_FAILED")
        return transactions.inTransaction {
            val items = store.list(filter, pageNumber * pageSize, pageSize)
            val total = store.count(filter)
            auditTrail.record(user, AuditEvent("LIST_NOTIFICATIONS", "NOTIFICATION", details = setOf("count.${items.size}")))
            NotificationPage(items, pageNumber, pageSize, total)
        }
    }
}
