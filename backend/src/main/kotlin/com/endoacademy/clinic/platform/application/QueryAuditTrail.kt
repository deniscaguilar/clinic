package com.endoacademy.clinic.platform.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import java.time.Instant
import java.util.UUID

/** Filtros opcionais da consulta; `from` é inclusivo e `to` é exclusivo. */
data class AuditFilter(
    val from: Instant? = null,
    val to: Instant? = null,
    val userId: UUID? = null,
    val action: String? = null,
    val entityType: String? = null,
)

/** Um evento como a consulta o devolve: só ids e nomes de campos (a trilha nunca guarda dado de paciente). */
data class AuditRecord(
    val id: UUID,
    val occurredAt: Instant,
    val userId: UUID?,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val outcome: String,
    val details: List<String>,
)

data class PageOf<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)

/** Leitura da trilha da clínica ativa (a RLS limita ao tenant), do mais recente para o mais antigo. */
interface AuditQueryStore {
    fun query(filter: AuditFilter, page: Int, size: Int): PageOf<AuditRecord>
}

/** `QueryAuditTrail` (`VIEW_AUDIT_TRAIL`): paginação padrão 20, máximo 100. */
class QueryAuditTrail(
    private val authorizer: Authorizer,
    private val store: AuditQueryStore,
    private val transactions: Transactions,
) {
    fun query(user: CurrentUser, filter: AuditFilter, page: Int?, size: Int?): PageOf<AuditRecord> {
        authorizer.require(user, "VIEW_AUDIT_TRAIL")
        val pageNumber = page ?: 0
        val pageSize = size ?: DEFAULT_SIZE
        val invalid = pageNumber < 0 || pageSize !in 1..MAX_SIZE ||
            (filter.from != null && filter.to != null && !filter.from.isBefore(filter.to))
        if (invalid) throw ApiException(ErrorKind.BAD_REQUEST, "VALIDATION_FAILED")
        return transactions.inTransaction { store.query(filter, pageNumber, pageSize) }
    }

    private companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100
    }
}
