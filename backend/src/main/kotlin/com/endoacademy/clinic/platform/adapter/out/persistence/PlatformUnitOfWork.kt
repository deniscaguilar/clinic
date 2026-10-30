package com.endoacademy.clinic.platform.adapter.out.persistence

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.platform.application.UnitOfWork
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Abre uma transação no escopo `platform`, já no tenant da clínica afetada quando houver. */
class PlatformUnitOfWork(transactionManager: PlatformTransactionManager) : UnitOfWork {

    private val transaction = TransactionTemplate(transactionManager)

    override fun <T> inPlatformScope(tenantId: UUID?, block: () -> T): T =
        TenantScope.runAsPlatformIn(tenantId) {
            @Suppress("UNCHECKED_CAST")
            transaction.execute { block() } as T
        }
}
