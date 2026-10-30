package com.endoacademy.clinic.support

import com.endoacademy.clinic.platform.application.ClinicAdmin
import com.endoacademy.clinic.platform.application.ClinicStatus
import com.endoacademy.clinic.platform.application.ClinicOverview
import com.endoacademy.clinic.platform.application.ClinicStore
import com.endoacademy.clinic.platform.application.MembershipInfo
import com.endoacademy.clinic.platform.application.MembershipStatus
import com.endoacademy.clinic.platform.application.StoredUser
import java.util.UUID

/** `ClinicStore` em memória para os testes unitários dos casos de uso de clínica. */
class FakeClinicStore : ClinicStore {
    val clinics = mutableMapOf<UUID, String>()
    val clinicStatus = mutableMapOf<UUID, ClinicStatus>()
    val users = mutableMapOf<UUID, Triple<String, String, String?>>() // id -> (email, name, sub)
    val memberships = mutableMapOf<UUID, Triple<UUID, UUID, MembershipStatus>>() // id -> (clinic, user, status)
    val roles = mutableMapOf<UUID, Triple<UUID, String, Set<String>>>() // id -> (clinic, name, permissions)
    val membershipSubs = mutableMapOf<UUID, String?>() // membership -> user_sub
    val assignments = mutableListOf<Pair<UUID, UUID>>() // (membership, role)

    override fun insertClinic(id: UUID, name: String) {
        clinics[id] = name
        clinicStatus[id] = ClinicStatus.ACTIVE
    }

    override fun findUserByEmail(email: String) = users.entries.firstOrNull { it.value.first == email }?.let { StoredUser(it.key, it.value.third) }

    override fun insertUser(id: UUID, email: String, name: String) {
        users[id] = Triple(email, name, null)
    }

    override fun insertMembership(id: UUID, clinicId: UUID, userId: UUID, status: MembershipStatus, userSub: String?) {
        memberships[id] = Triple(clinicId, userId, status)
        membershipSubs[id] = userSub
    }

    override fun insertRole(id: UUID, clinicId: UUID, name: String, permissions: Set<String>) {
        roles[id] = Triple(clinicId, name, permissions)
    }

    override fun assignRole(clinicId: UUID, membershipId: UUID, roleId: UUID) {
        assignments += membershipId to roleId
    }

    override fun setUserSub(userId: UUID, sub: String) {
        users[userId] = users.getValue(userId).copy(third = sub)
    }

    override fun activateMembership(clinicId: UUID, membershipId: UUID, sub: String) {
        memberships[membershipId] = memberships.getValue(membershipId).copy(third = MembershipStatus.ACTIVE)
        membershipSubs[membershipId] = sub
    }

    override fun updateClinicName(id: UUID, name: String): Boolean {
        if (id !in clinics) return false
        clinics[id] = name
        return true
    }

    override fun setClinicStatus(id: UUID, status: ClinicStatus): ClinicStatus? {
        val previous = clinicStatus[id] ?: return null
        clinicStatus[id] = status
        return previous
    }

    override fun findMembership(clinicId: UUID, membershipId: UUID): MembershipInfo? {
        val (clinic, userId, status) = memberships[membershipId] ?: return null
        if (clinic != clinicId) return null
        val (email, name, sub) = users.getValue(userId)
        return MembershipInfo(membershipId, userId, email, name, sub, status)
    }

    override fun listClinics(): List<ClinicOverview> = clinics.map { (id, name) -> ClinicOverview(id, name, clinicStatus.getValue(id)) }.sortedBy { it.name }

    override fun findClinic(id: UUID): ClinicOverview? = clinics[id]?.let { ClinicOverview(id, it, clinicStatus.getValue(id)) }

    override fun listClinicAdmins(clinicId: UUID): List<ClinicAdmin> {
        val adminRoles = roles.filter { it.value.first == clinicId && it.value.second == "CLINIC_ADMIN" }.keys
        return assignments.filter { it.second in adminRoles }.map { it.first }.distinct().mapNotNull { membership ->
            val (clinic, userId, status) = memberships.getValue(membership)
            if (clinic != clinicId) return@mapNotNull null
            val (email, name, _) = users.getValue(userId)
            ClinicAdmin(membership, email, name, status)
        }.sortedBy { it.email }
    }
}
