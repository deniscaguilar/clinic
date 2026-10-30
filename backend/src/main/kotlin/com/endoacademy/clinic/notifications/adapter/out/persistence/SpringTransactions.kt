package com.endoacademy.clinic.notifications.adapter.out.persistence

import com.endoacademy.clinic.notifications.application.Transactions
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Transação simples, no contexto de clínica que o chamador definiu antes de abri-la. */
class SpringTransactions(transactionManager: PlatformTransactionManager) : Transactions {

    private val template = TransactionTemplate(transactionManager)

    override fun <T> inTransaction(block: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return template.execute { block() } as T
    }
}
