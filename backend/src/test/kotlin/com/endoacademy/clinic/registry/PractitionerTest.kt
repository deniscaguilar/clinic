package com.endoacademy.clinic.registry

import com.endoacademy.clinic.registry.domain.Practitioner
import com.endoacademy.clinic.registry.domain.PractitionerType
import com.endoacademy.clinic.registry.domain.RegistryException
import com.endoacademy.clinic.registry.domain.WorkInterval
import com.endoacademy.clinic.registry.domain.WorkSchedule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

class PractitionerTest {

    private val id = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")

    private fun assertCode(code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(RegistryException::class.java) { assertThat(it.code).isEqualTo(code) }
    }

    private fun interval(day: DayOfWeek, start: String, end: String) = WorkInterval(day, LocalTime.parse(start), LocalTime.parse(end))

    // --- WorkSchedule ---

    @Test
    fun `an empty schedule is allowed`() {
        assertThat(WorkSchedule.of(emptyList()).intervals).isEmpty()
    }

    @Test
    fun `intervals are kept sorted by day and start`() {
        val schedule = WorkSchedule.of(
            listOf(
                interval(DayOfWeek.WEDNESDAY, "14:00", "18:00"),
                interval(DayOfWeek.MONDAY, "14:00", "18:00"),
                interval(DayOfWeek.MONDAY, "08:00", "12:00"),
            ),
        )
        assertThat(schedule.intervals).containsExactly(
            interval(DayOfWeek.MONDAY, "08:00", "12:00"),
            interval(DayOfWeek.MONDAY, "14:00", "18:00"),
            interval(DayOfWeek.WEDNESDAY, "14:00", "18:00"),
        )
    }

    @Test
    fun `the same hours on different days and back-to-back intervals do not overlap`() {
        WorkSchedule.of(listOf(interval(DayOfWeek.MONDAY, "08:00", "12:00"), interval(DayOfWeek.TUESDAY, "08:00", "12:00")))
        WorkSchedule.of(listOf(interval(DayOfWeek.MONDAY, "08:00", "12:00"), interval(DayOfWeek.MONDAY, "12:00", "18:00")))
    }

    @Test
    fun `overlapping intervals on the same day are rejected in every arrangement`() {
        val base = interval(DayOfWeek.MONDAY, "08:00", "12:00")
        listOf(
            interval(DayOfWeek.MONDAY, "11:59", "13:00"), // invade o fim
            interval(DayOfWeek.MONDAY, "07:00", "08:01"), // invade o começo
            interval(DayOfWeek.MONDAY, "09:00", "10:00"), // contido
            interval(DayOfWeek.MONDAY, "07:00", "13:00"), // contém
            interval(DayOfWeek.MONDAY, "08:00", "12:00"), // idêntico
        ).forEach { other ->
            assertCode("WORK_SCHEDULE_OVERLAP") { WorkSchedule.of(listOf(base, other)) }
            assertCode("WORK_SCHEDULE_OVERLAP") { WorkSchedule.of(listOf(other, base)) }
        }
    }

    @Test
    fun `an interval must start before it ends and use whole minutes`() {
        assertCode("WORK_INTERVAL_INVALID") { WorkSchedule.of(listOf(interval(DayOfWeek.MONDAY, "12:00", "08:00"))) }
        assertCode("WORK_INTERVAL_INVALID") { WorkSchedule.of(listOf(interval(DayOfWeek.MONDAY, "08:00", "08:00"))) }
        assertCode("WORK_INTERVAL_INVALID") { WorkSchedule.of(listOf(WorkInterval(DayOfWeek.MONDAY, LocalTime.of(8, 0, 30), LocalTime.of(9, 0)))) }
        assertCode("WORK_INTERVAL_INVALID") { WorkSchedule.of(listOf(WorkInterval(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0, 0, 1)))) }
    }

    // --- Practitioner ---

    private fun register(
        name: String = "Dra. Ana Souza",
        type: PractitionerType = PractitionerType.DOCTOR,
        registration: String? = "CRM-SP 123456",
        user: UUID? = userId,
        schedule: WorkSchedule = WorkSchedule.of(listOf(interval(DayOfWeek.MONDAY, "08:00", "12:00"))),
    ) = Practitioner.register(id, name, type, registration, user, schedule)

    @Test
    fun `a new practitioner starts active with normalized fields`() {
        val p = register(name = "  Dra.   Ana Souza ", registration = "  CRM-SP 123456 ")
        assertThat(p.id).isEqualTo(id)
        assertThat(p.name).isEqualTo("Dra. Ana Souza")
        assertThat(p.type).isEqualTo(PractitionerType.DOCTOR)
        assertThat(p.registrationNumber).isEqualTo("CRM-SP 123456")
        assertThat(p.userId).isEqualTo(userId)
        assertThat(p.active).isTrue()
        assertThat(p.schedule.intervals).hasSize(1)
    }

    @Test
    fun `the registration number and the user are optional and a blank registration becomes null`() {
        assertThat(register(registration = null).registrationNumber).isNull()
        assertThat(register(registration = "   ").registrationNumber).isNull()
        assertThat(register(user = null).userId).isNull()
    }

    @Test
    fun `the name is required and limited to 150 characters, the registration to 30`() {
        listOf("", "   ").forEach { assertCode("PRACTITIONER_NAME_REQUIRED") { register(name = it) } }
        assertThat(register(name = "x".repeat(150)).name).hasSize(150)
        assertCode("PRACTITIONER_NAME_TOO_LONG") { register(name = "x".repeat(151)) }
        assertThat(register(registration = "1".repeat(30)).registrationNumber).hasSize(30)
        assertCode("PRACTITIONER_REGISTRATION_TOO_LONG") { register(registration = "1".repeat(31)) }
    }

    @Test
    fun `update changes the fields, keeps the id and applies the same rules`() {
        val p = register()
        val newSchedule = WorkSchedule.of(listOf(interval(DayOfWeek.FRIDAY, "09:00", "13:00")))
        val updated = p.update("Enf. Bia Lima", PractitionerType.NURSE, null, null, false, newSchedule)
        assertThat(updated.id).isEqualTo(id)
        assertThat(updated.name).isEqualTo("Enf. Bia Lima")
        assertThat(updated.type).isEqualTo(PractitionerType.NURSE)
        assertThat(updated.registrationNumber).isNull()
        assertThat(updated.userId).isNull()
        assertThat(updated.active).isFalse()
        assertThat(updated.schedule).isEqualTo(newSchedule)
        assertThat(p.name).isEqualTo("Dra. Ana Souza")
        assertCode("PRACTITIONER_NAME_REQUIRED") { p.update(" ", PractitionerType.DOCTOR, null, null, true, newSchedule) }
        assertCode("PRACTITIONER_REGISTRATION_TOO_LONG") { p.update("Ana", PractitionerType.DOCTOR, "1".repeat(31), null, true, newSchedule) }
    }

    @Test
    fun `a practitioner does not leak the name through toString`() {
        assertThat(register().toString()).doesNotContain("Ana").doesNotContain("123456")
    }
}
