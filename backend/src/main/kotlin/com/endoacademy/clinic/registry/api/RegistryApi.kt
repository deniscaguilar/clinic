package com.endoacademy.clinic.registry.api

import java.time.Instant
import java.util.UUID

/**
 * Contato do paciente para o envio de mensagens: o mínimo (nome, WhatsApp e e-mail). Sem CPF, endereço nem nascimento.
 * O `toString` não expõe os dados.
 */
data class PatientContact(val name: String, val whatsapp: String?, val email: String?) {
    override fun toString(): String = "PatientContact(***)"
}

/** O mínimo de um aceite para montar a mensagem de confirmação: o nome do tipo e quando foi aceito. */
data class ConsentSummary(val typeName: String, val acceptedAt: Instant)

/**
 * Fachada pública do módulo `registry` para os outros módulos (`notifications`, depois `inventory`). Roda no contexto de
 * clínica do chamador (`TenantScope.runAs`): sem clínica ativa ou com paciente de outra clínica, a RLS não deixa ver
 * nada e as respostas são `false` e `null` (falha fechada).
 */
interface RegistryApi {

    /**
     * O paciente tem consentimento de mensagens vigente? Vale quando existe um aceite **não revogado** do tipo **ativo**
     * da clínica que controla o envio de mensagens. Um aceite de versão antiga continua valendo; só a revogação o encerra.
     */
    fun hasActiveMessagingConsent(patientId: UUID): Boolean

    /**
     * Nome do tipo e instante do aceite `grantId` **do paciente** `patientId`, ou `null` se o aceite não existe ou é de
     * outro paciente. Vale mesmo para aceite revogado ou tipo desativado: serve ao texto da confirmação.
     */
    fun getConsentSummary(patientId: UUID, grantId: UUID): ConsentSummary?

    /** Nome e canais de contato do paciente, ou `null` se ele não existe na clínica ativa. */
    fun getPatientContact(patientId: UUID): PatientContact?
}
