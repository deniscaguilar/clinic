package com.endoacademy.clinic.platform.api

import java.util.UUID

/** Evento publicado depois que a clínica foi gravada. Só o id: nunca dado pessoal. */
data class ClinicCreated(val clinicId: UUID)
