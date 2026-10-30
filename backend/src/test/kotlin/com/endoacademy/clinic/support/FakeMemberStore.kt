package com.endoacademy.clinic.support

import com.endoacademy.clinic.platform.application.MemberStore
import com.endoacademy.clinic.platform.application.MemberView
import com.endoacademy.clinic.platform.application.MembershipStatus
import com.endoacademy.clinic.platform.application.PermissionCatalog
import com.endoacademy.clinic.platform.application.RoleRef
import java.util.UUID

/** `MemberStore` em memória de uma clínica. `knownUsers` guarda também usuários de outras clínicas (invisíveis, como na RLS). */
class FakeMemberStore : MemberStore {
    data class User(val email: String, val name: String, var sub: String?)
    data class Member(val id: UUID, val userId: UUID, var status: MembershipStatus, val roleIds: MutableSet<UUID> = linkedSetOf())

    val knownUsers = mutableMapOf<UUID, User>()
    val members = linkedMapOf<UUID, Member>()
    val roles = mutableMapOf<UUID, String>()

    fun addRole(name: String): UUID = UUID.randomUUID().also { roles[it] = name }

    /** Vínculo já existente (ex.: o administrador da clínica). */
    fun addMember(email: String, name: String, status: MembershipStatus, sub: String?, vararg roleIds: UUID): UUID {
        val userId = com.endoacademy.clinic.platform.application.UserIds.forEmail(email)
        knownUsers[userId] = User(email, name, sub)
        val id = UUID.randomUUID()
        members[id] = Member(id, userId, status, roleIds.toMutableSet())
        return id
    }

    private fun view(member: Member): MemberView {
        val user = knownUsers.getValue(member.userId)
        return MemberView(
            member.id,
            member.userId,
            user.email,
            user.name,
            user.sub,
            member.status,
            member.roleIds.map { RoleRef(it, roles.getValue(it)) }.sortedBy { it.name },
        )
    }

    override fun ensureUser(id: UUID, email: String, name: String) {
        knownUsers.putIfAbsent(id, User(email, name, null))
    }

    override fun findByUser(userId: UUID) = members.values.firstOrNull { it.userId == userId }?.let(::view)

    override fun find(membershipId: UUID) = members[membershipId]?.let(::view)

    override fun list() = members.values.map(::view).sortedBy { it.name }

    override fun insertMembership(id: UUID, clinicId: UUID, userId: UUID, status: MembershipStatus, userSub: String?) {
        members[id] = Member(id, userId, status)
    }

    override fun rolesById(ids: Collection<UUID>) = ids.mapNotNull { id -> roles[id]?.let { RoleRef(id, it) } }

    override fun replaceRoles(clinicId: UUID, membershipId: UUID, roleIds: Set<UUID>) {
        members.getValue(membershipId).roleIds.apply {
            clear()
            addAll(roleIds)
        }
    }

    override fun setUserSub(userId: UUID, sub: String) {
        val user = knownUsers.getValue(userId)
        if (user.sub == null) user.sub = sub
    }

    override fun activate(clinicId: UUID, membershipId: UUID, sub: String) {
        members.getValue(membershipId).status = MembershipStatus.ACTIVE
    }

    override fun setStatus(membershipId: UUID, status: MembershipStatus) {
        members.getValue(membershipId).status = status
    }

    override fun activeClinicAdmins(): Set<UUID> =
        members.values.filter { m -> m.status == MembershipStatus.ACTIVE && m.roleIds.any { roles[it] == PermissionCatalog.CLINIC_ADMIN } }.mapTo(linkedSetOf()) { it.id }
}
