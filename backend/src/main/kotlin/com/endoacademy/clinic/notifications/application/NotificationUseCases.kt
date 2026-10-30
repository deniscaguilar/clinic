package com.endoacademy.clinic.notifications.application

import com.endoacademy.clinic.notifications.domain.Notification
import com.endoacademy.clinic.notifications.domain.NotificationChannel
import com.endoacademy.clinic.notifications.domain.NotificationKind
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Transação no contexto de clínica que o chamador abriu (`TenantScope.runAs`); a RLS limita tudo ao tenant. */
interface Transactions {
    fun <T> inTransaction(block: () -> T): T
}

/** Fila de notificações (ADR-0011). Roda na transação do [Transactions]. */
interface NotificationStore {
    /**
     * Grava a notificação, a menos que já exista uma para o mesmo aceite, canal e tipo (o Spring Modulith pode
     * reenviar um evento). Devolve `true` se gravou.
     */
    fun insertIfAbsent(tenantId: UUID, notification: Notification): Boolean

    /** Passa a `CANCELLED` as notificações **pendentes** do paciente; devolve quantas. O resto não muda. */
    fun cancelPending(patientId: UUID): Int

    /**
     * Pega **uma** notificação pendente e devida da clínica do contexto, travando a linha (`FOR UPDATE SKIP LOCKED`):
     * dois workers nunca recebem a mesma. A trava dura até o fim da transação do chamador.
     */
    fun claimDue(now: Instant): Notification?

    /** Grava o andamento (status, tentativas, próxima tentativa, último erro) de uma notificação já pega. */
    fun save(notification: Notification)
}

/**
 * Ouve `ConsentAccepted` (RF13): cria uma notificação pendente por canal que o paciente tem cadastrado. Só ids ficam
 * gravados; o contato e o texto são resolvidos no envio. Se o paciente não tem canal, não há o que enviar. O
 * consentimento de mensagens é conferido **no envio** (CA17), não aqui.
 */
class OnConsentAccepted(
    private val contacts: PatientContactLookup,
    private val store: NotificationStore,
    private val transactions: Transactions,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    /** Devolve quantas notificações novas foram criadas. */
    fun handle(tenantId: UUID, patientId: UUID, grantId: UUID): Int = transactions.inTransaction {
        val contact = contacts.find(patientId) ?: return@inTransaction 0
        val channels = buildList {
            if (contact.whatsapp != null) add(NotificationChannel.WHATSAPP)
            if (contact.email != null) add(NotificationChannel.EMAIL)
        }
        val now = clock.instant()
        channels.count { channel ->
            store.insertIfAbsent(tenantId, Notification.create(newId(), patientId, grantId, NotificationKind.CONSENT_ACCEPTED, channel, now))
        }
    }
}

/** Ouve `ConsentRevoked` (RF12): o que estava pendente para o paciente deixa de ser enviado. */
class OnConsentRevoked(private val store: NotificationStore, private val transactions: Transactions) {
    /** Devolve quantas notificações foram canceladas. */
    fun handle(patientId: UUID): Int = transactions.inTransaction { store.cancelPending(patientId) }
}
