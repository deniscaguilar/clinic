package com.endoacademy.clinic.registry.domain

import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

enum class PractitionerType { DOCTOR, NURSE, OTHER }

/** Faixa de trabalho em um dia da semana, em minutos inteiros e dentro do mesmo dia. */
data class WorkInterval(val dayOfWeek: DayOfWeek, val start: LocalTime, val end: LocalTime)

/**
 * Horário de trabalho do profissional na clínica: faixas por dia da semana, sem sobreposição. Faixas encostadas
 * (fim de uma = início da outra) são permitidas. Fica ordenado por dia e início.
 */
@ConsistentCopyVisibility
data class WorkSchedule private constructor(val intervals: List<WorkInterval>) {

    companion object {
        fun of(intervals: List<WorkInterval>): WorkSchedule {
            intervals.forEach {
                if (!it.start.isBefore(it.end) || it.start.second != 0 || it.start.nano != 0 || it.end.second != 0 || it.end.nano != 0) {
                    throw RegistryException("WORK_INTERVAL_INVALID")
                }
            }
            val sorted = intervals.sortedWith(compareBy({ it.dayOfWeek }, { it.start }))
            sorted.zipWithNext().forEach { (a, b) ->
                if (a.dayOfWeek == b.dayOfWeek && b.start.isBefore(a.end)) throw RegistryException("WORK_SCHEDULE_OVERLAP")
            }
            return WorkSchedule(sorted)
        }
    }
}

/**
 * Profissional da clínica (RF4). Um médico em duas clínicas é um profissional **por clínica**, cada um com o seu
 * horário. `userId` liga ao usuário (login) que já é membro da clínica; é opcional. O `toString` não expõe o nome.
 */
@ConsistentCopyVisibility
data class Practitioner private constructor(
    val id: UUID,
    val name: String,
    val type: PractitionerType,
    val registrationNumber: String?,
    val userId: UUID?,
    val active: Boolean,
    val schedule: WorkSchedule,
) {

    fun update(name: String, type: PractitionerType, registrationNumber: String?, userId: UUID?, active: Boolean, schedule: WorkSchedule): Practitioner =
        of(id, name, type, registrationNumber, userId, active, schedule)

    override fun toString(): String = "Practitioner(id=$id)"

    companion object {
        private const val MAX_NAME_LENGTH = 150
        private const val MAX_REGISTRATION_LENGTH = 30

        fun register(id: UUID, name: String, type: PractitionerType, registrationNumber: String?, userId: UUID?, schedule: WorkSchedule): Practitioner =
            of(id, name, type, registrationNumber, userId, active = true, schedule)

        /** Reconstrói a partir do que está gravado; as mesmas regras valem. */
        fun restore(id: UUID, name: String, type: PractitionerType, registrationNumber: String?, userId: UUID?, active: Boolean, schedule: WorkSchedule): Practitioner =
            of(id, name, type, registrationNumber, userId, active, schedule)

        private fun of(
            id: UUID,
            name: String,
            type: PractitionerType,
            registrationNumber: String?,
            userId: UUID?,
            active: Boolean,
            schedule: WorkSchedule,
        ): Practitioner {
            val trimmedName = name.trim().replace(Regex(" +"), " ")
            if (trimmedName.isEmpty()) throw RegistryException("PRACTITIONER_NAME_REQUIRED")
            if (trimmedName.length > MAX_NAME_LENGTH) throw RegistryException("PRACTITIONER_NAME_TOO_LONG")
            val registration = registrationNumber?.trim()?.takeIf { it.isNotEmpty() }
            if (registration != null && registration.length > MAX_REGISTRATION_LENGTH) throw RegistryException("PRACTITIONER_REGISTRATION_TOO_LONG")
            return Practitioner(id, trimmedName, type, registration, userId, active, schedule)
        }
    }
}
