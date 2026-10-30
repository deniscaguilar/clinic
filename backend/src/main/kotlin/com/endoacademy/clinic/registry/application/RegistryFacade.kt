package com.endoacademy.clinic.registry.application

import com.endoacademy.clinic.registry.api.ConsentSummary
import com.endoacademy.clinic.registry.api.PatientContact
import com.endoacademy.clinic.registry.api.RegistryApi
import java.util.UUID

/**
 * Implementação da [RegistryApi]. Consulta pelos mesmos ports dos casos de uso, na transação do chamador (ou numa nova,
 * se não houver), sob a RLS do contexto de clínica. É leitura de sistema, sem usuário: não grava auditoria de leitura de
 * paciente; o rastro do envio fica no módulo `notifications`.
 */
class RegistryFacade(
    private val patients: PatientStore,
    private val types: ConsentTypeStore,
    private val grants: ConsentGrantStore,
    private val transactions: Transactions,
) : RegistryApi {

    override fun hasActiveMessagingConsent(patientId: UUID): Boolean = transactions.inTransaction {
        types.list(true).filter { it.controlsMessaging }.any { grants.hasActive(patientId, it.id) }
    }

    override fun getConsentSummary(patientId: UUID, grantId: UUID): ConsentSummary? = transactions.inTransaction {
        grants.find(patientId, grantId)?.let { grant -> types.find(grant.typeId)?.let { ConsentSummary(it.name, grant.acceptedAt) } }
    }

    override fun getPatientContact(patientId: UUID): PatientContact? = transactions.inTransaction {
        patients.find(patientId)?.let { PatientContact(it.fullName, it.whatsapp?.value, it.email?.value) }
    }
}
