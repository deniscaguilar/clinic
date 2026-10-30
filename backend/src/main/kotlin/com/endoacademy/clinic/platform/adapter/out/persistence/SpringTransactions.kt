package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.application.Transactions
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Transação simples, no contexto de tenant que a requisição já definiu. */
class SpringTransactions(transactionManager: PlatformTransactionManager) : Transactions {

    private val template = TransactionTemplate(transactionManager)

    override fun <T> inTransaction(block: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return template.execute { block() } as T
    }
}
