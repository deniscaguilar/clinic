package com.endoacademy.clinic.registry.api

import java.util.UUID

/**
 * Um paciente aceitou um tipo de consentimento. Só ids: o evento é persistido pelo registro do Spring Modulith (schema
 * `eventing`) e nunca leva dado pessoal (CLAUDE.md, seção 4). Quem ouve resolve o que precisa pela `api` do `registry`.
 */
data class ConsentAccepted(val tenantId: UUID, val patientId: UUID, val grantId: UUID)

/** Um paciente revogou um aceite. Só ids, como [ConsentAccepted]. */
data class ConsentRevoked(val tenantId: UUID, val patientId: UUID, val grantId: UUID)
