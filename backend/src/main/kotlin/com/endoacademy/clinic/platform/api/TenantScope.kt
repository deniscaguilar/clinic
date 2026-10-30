package com.endoacademy.clinic.platform.api

import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Contexto de banco da transação (ADR-0010). Os três valores viram `app.tenant_id`, `app.user_sub` e `app.scope`
 * no início de cada transação (`set_config(..., true)`), e só valem nela.
 *
 * - `tenantId`: clínica ativa.
 * - `userSub`: resolve os vínculos do próprio usuário antes de existir clínica ativa.
 * - `platformScope`: só dentro de casos de uso do `PLATFORM_ADMIN`; nunca libera tabelas de paciente.
 */
data class TenantContext(
    val tenantId: UUID? = null,
    val userSub: String? = null,
    val platformScope: Boolean = false,
)

/**
 * Define o contexto da thread. A transação precisa **começar dentro** do bloco: mudar o contexto com uma
 * transação já aberta seria ignorado pelo banco, então é recusado.
 */
object TenantScope {
    private val holder = ThreadLocal<TenantContext?>()

    fun current(): TenantContext? = holder.get()

    fun <T> runAs(tenantId: UUID, block: () -> T): T = within(TenantContext(tenantId = tenantId), block)

    fun <T> runAsUser(userSub: String, block: () -> T): T = within(TenantContext(userSub = userSub), block)

    fun <T> runAsPlatform(block: () -> T): T = within(TenantContext(platformScope = true), block)

    /**
     * Escopo `platform` já dentro do tenant da clínica afetada: o caso de uso do `PLATFORM_ADMIN` que cria a clínica
     * grava a clínica (só `platform`) e seus perfis (só o tenant) na mesma transação. `tenantId` nulo = só `platform`.
     */
    fun <T> runAsPlatformIn(tenantId: UUID?, block: () -> T): T = within(TenantContext(tenantId = tenantId, platformScope = true), block)

    private fun <T> within(context: TenantContext, block: () -> T): T {
        val previous = holder.get()
        check(previous == context || !TransactionSynchronizationManager.isActualTransactionActive()) {
            "O contexto de tenant não pode mudar com uma transação ativa"
        }
        holder.set(context)
        try {
            return block()
        } finally {
            if (previous == null) holder.remove() else holder.set(previous)
        }
    }
}
