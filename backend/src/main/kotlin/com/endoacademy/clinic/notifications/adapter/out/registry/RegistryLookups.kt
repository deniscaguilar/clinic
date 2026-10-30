package com.endoacademy.clinic.notifications.adapter.out.registry

import com.endoacademy.clinic.notifications.application.ConsentInfo
import com.endoacademy.clinic.notifications.application.ConsentInfoLookup
import com.endoacademy.clinic.notifications.application.ContactDetails
import com.endoacademy.clinic.notifications.application.MessagingConsentLookup
import com.endoacademy.clinic.notifications.application.PatientContactLookup
import com.endoacademy.clinic.registry.api.RegistryApi
import java.util.UUID

/** Contato do paciente pela `registry.api` (único acesso permitido ao `registry`). */
class RegistryPatientContactLookup(private val registry: RegistryApi) : PatientContactLookup {
    override fun find(patientId: UUID): ContactDetails? = registry.getPatientContact(patientId)?.let { ContactDetails(it.name, it.whatsapp, it.email) }
}

/** Consentimento de mensagens vigente pela `registry.api`; a regra é do `registry`. */
class RegistryMessagingConsentLookup(private val registry: RegistryApi) : MessagingConsentLookup {
    override fun hasActive(patientId: UUID): Boolean = registry.hasActiveMessagingConsent(patientId)
}

/** Resumo do aceite pela `registry.api`. */
class RegistryConsentInfoLookup(private val registry: RegistryApi) : ConsentInfoLookup {
    override fun find(patientId: UUID, grantId: UUID): ConsentInfo? = registry.getConsentSummary(patientId, grantId)?.let { ConsentInfo(it.typeName, it.acceptedAt) }
}
