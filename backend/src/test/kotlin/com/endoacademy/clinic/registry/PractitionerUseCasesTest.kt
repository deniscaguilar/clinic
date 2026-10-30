package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.ClinicMembers
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.PermissionDeniedException
import com.endoacademy.clinic.registry.application.GetPractitioner
import com.endoacademy.clinic.registry.application.ListPractitioners
import com.endoacademy.clinic.registry.application.PractitionerData
import com.endoacademy.clinic.registry.application.PractitionerStore
import com.endoacademy.clinic.registry.application.RegisterPractitioner
import com.endoacademy.clinic.registry.application.Transactions
import com.endoacademy.clinic.registry.application.UpdatePractitioner
import com.endoacademy.clinic.registry.domain.Practitioner
import com.endoacademy.clinic.registry.domain.PractitionerType
import com.endoacademy.clinic.registry.domain.WorkInterval
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

class PractitionerUseCasesTest {

    private class FakePractitionerStore : PractitionerStore {
        val rows = linkedMapOf<UUID, Practitioner>()
        override fun list(active: Boolean?) = rows.values.filter { active == null || it.active == active }.sortedBy { it.name.lowercase() }
        override fun find(id: UUID) = rows[id]
        override fun insert(clinicId: UUID, practitioner: Practitioner) {
            rows[practitioner.id] = practitioner
        }

        override fun update(practitioner: Practitioner) {
            rows[practitioner.id] = practitioner
        }

        override fun userLinked(userId: UUID, exceptId: UUID?) = rows.values.any { it.userId == userId && it.id != exceptId }
    }

    private class RecordingAudit : AuditTrail {
        val events = mutableListOf<AuditEvent>()
        override fun record(user: CurrentUser?, event: AuditEvent) {
            events += event
        }
    }

    private val clinic = UUID.randomUUID()
    private val manager = CurrentUser(UUID.randomUUID(), "sub-1", clinic, setOf("MANAGE_PRACTITIONERS"))
    private val outsider = CurrentUser(UUID.randomUUID(), "sub-2", clinic, setOf("MANAGE_PROCEDURES"))
    private val member = UUID.randomUUID()
    private val otherMember = UUID.randomUUID()
    private val stranger = UUID.randomUUID()
    private val store = FakePractitionerStore()
    private val audit = RecordingAudit()
    private val authorizer = object : Authorizer {
        override fun require(user: CurrentUser, permission: String) {
            if (!user.has(permission)) throw PermissionDeniedException(permission)
        }
    }
    private val members = object : ClinicMembers {
        override fun isActiveMember(clinicId: UUID, userId: UUID) = clinicId == clinic && userId in setOf(member, otherMember)
    }
    private val transactions = object : Transactions {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
    private var nextId = 0
    private val register = RegisterPractitioner(authorizer, store, members, audit, transactions) { UUID(0, (++nextId).toLong()) }
    private val update = UpdatePractitioner(authorizer, store, members, audit, transactions)
    private val list = ListPractitioners(authorizer, store, transactions)
    private val get = GetPractitioner(authorizer, store, transactions)

    private fun data(
        name: String = "Dra. Ana Souza",
        type: PractitionerType = PractitionerType.DOCTOR,
        registration: String? = "CRM 1",
        userId: UUID? = member,
        intervals: List<WorkInterval> = listOf(WorkInterval(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(12, 0))),
    ) = PractitionerData(name, type, registration, userId, intervals)

    private fun assertApi(kind: ErrorKind, code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(kind)
            assertThat(it.code).isEqualTo(code)
        }
    }

    @Test
    fun `registering stores an active practitioner and audits field names only`() {
        val created = register.register(manager, data())
        assertThat(created.active).isTrue()
        assertThat(created.userId).isEqualTo(member)
        assertThat(store.rows).containsKey(created.id)
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("REGISTER_PRACTITIONER")
            assertThat(it.entityType).isEqualTo("PRACTITIONER")
            assertThat(it.entityId).isEqualTo(created.id.toString())
            assertThat(it.details).containsExactlyInAnyOrder("name", "type", "registrationNumber", "userId", "workSchedule")
        })
    }

    @Test
    fun `without MANAGE_PRACTITIONERS nothing is registered, listed, read or updated`() {
        val existing = register.register(manager, data())
        audit.events.clear()
        assertThatThrownBy { register.register(outsider, data(userId = null)) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { update.update(outsider, existing.id, data(name = "Outro nome")) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { list.list(outsider, null) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { get.get(outsider, existing.id) }.isInstanceOf(PermissionDeniedException::class.java)
        assertThat(store.rows).hasSize(1)
        assertThat(store.rows.getValue(existing.id).name).isEqualTo("Dra. Ana Souza")
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `a practitioner may exist without a linked user`() {
        val created = register.register(manager, data(userId = null))
        assertThat(created.userId).isNull()
        register.register(manager, data(name = "Enf. Bia", type = PractitionerType.NURSE, userId = null))
        assertThat(store.rows).hasSize(2)
    }

    @Test
    fun `a user that is not an active member of the clinic cannot be linked and nothing is stored`() {
        assertApi(ErrorKind.UNPROCESSABLE, "PRACTITIONER_USER_NOT_MEMBER") { register.register(manager, data(userId = stranger)) }
        assertThat(store.rows).isEmpty()
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `a user is linked to one practitioner per clinic`() {
        register.register(manager, data())
        audit.events.clear()
        assertApi(ErrorKind.CONFLICT, "PRACTITIONER_USER_ALREADY_LINKED") { register.register(manager, data(name = "Outra pessoa")) }
        assertThat(store.rows).hasSize(1)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `domain violations become 422 with the stable code, including an overlapping schedule`() {
        assertApi(ErrorKind.UNPROCESSABLE, "PRACTITIONER_NAME_REQUIRED") { register.register(manager, data(name = " ")) }
        assertApi(ErrorKind.UNPROCESSABLE, "WORK_SCHEDULE_OVERLAP") {
            register.register(
                manager,
                data(
                    intervals = listOf(
                        WorkInterval(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(12, 0)),
                        WorkInterval(DayOfWeek.MONDAY, LocalTime.of(11, 0), LocalTime.of(13, 0)),
                    ),
                ),
            )
        }
        assertThat(store.rows).isEmpty()
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `updating replaces the fields and the schedule and audits`() {
        val created = register.register(manager, data())
        audit.events.clear()
        val newIntervals = listOf(WorkInterval(DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(13, 0)))
        val updated = update.update(manager, created.id, data(name = "Dra. Ana Lima", registration = null, userId = otherMember, intervals = newIntervals).copy(active = false))
        assertThat(updated.name).isEqualTo("Dra. Ana Lima")
        assertThat(updated.active).isFalse()
        assertThat(updated.userId).isEqualTo(otherMember)
        assertThat(updated.schedule.intervals).containsExactlyElementsOf(newIntervals)
        assertThat(store.rows.getValue(created.id)).isEqualTo(updated)
        assertThat(audit.events).singleElement().satisfies({
            assertThat(it.action).isEqualTo("UPDATE_PRACTITIONER")
            assertThat(it.entityId).isEqualTo(created.id.toString())
            assertThat(it.details).containsExactlyInAnyOrder("name", "type", "registrationNumber", "userId", "active", "workSchedule")
        })
    }

    @Test
    fun `an update may keep its own user but not take the user of another practitioner`() {
        val first = register.register(manager, data(userId = member))
        val second = register.register(manager, data(name = "Dr. Bruno", userId = otherMember))
        assertThat(update.update(manager, first.id, data(name = "Dra. Ana Souza Lima", userId = member)).userId).isEqualTo(member)
        audit.events.clear()
        assertApi(ErrorKind.CONFLICT, "PRACTITIONER_USER_ALREADY_LINKED") { update.update(manager, second.id, data(name = "Dr. Bruno", userId = member)) }
        assertThat(store.rows.getValue(second.id).userId).isEqualTo(otherMember)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `an update with a stranger user or an overlapping schedule keeps the stored practitioner`() {
        val created = register.register(manager, data())
        audit.events.clear()
        assertApi(ErrorKind.UNPROCESSABLE, "PRACTITIONER_USER_NOT_MEMBER") { update.update(manager, created.id, data(userId = stranger)) }
        assertApi(ErrorKind.UNPROCESSABLE, "WORK_SCHEDULE_OVERLAP") {
            update.update(
                manager,
                created.id,
                data(intervals = listOf(WorkInterval(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(10, 0)), WorkInterval(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(11, 0)))),
            )
        }
        assertThat(store.rows.getValue(created.id)).isEqualTo(created)
        assertThat(audit.events).isEmpty()
    }

    @Test
    fun `an unknown practitioner answers 404 on read and update`() {
        val unknown = UUID.randomUUID()
        assertApi(ErrorKind.NOT_FOUND, "PRACTITIONER_NOT_FOUND") { get.get(manager, unknown) }
        assertApi(ErrorKind.NOT_FOUND, "PRACTITIONER_NOT_FOUND") { update.update(manager, unknown, data()) }
    }

    @Test
    fun `listing filters by active and orders by name`() {
        val bruno = register.register(manager, data(name = "bruno Dias", userId = null))
        register.register(manager, data(name = "Ana Souza", userId = null))
        update.update(manager, bruno.id, data(name = "bruno Dias", userId = null).copy(active = false))
        assertThat(list.list(manager, null).map { it.name }).containsExactly("Ana Souza", "bruno Dias")
        assertThat(list.list(manager, true).map { it.name }).containsExactly("Ana Souza")
        assertThat(list.list(manager, false).map { it.name }).containsExactly("bruno Dias")
    }
}
