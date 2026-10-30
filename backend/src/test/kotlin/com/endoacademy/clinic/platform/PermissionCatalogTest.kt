package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.PermissionContributor
import com.endoacademy.clinic.platform.api.PermissionDefinition
import com.endoacademy.clinic.platform.application.PermissionCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PermissionCatalogTest {

    private fun contributor(permissions: List<PermissionDefinition>, grants: Map<String, Set<String>> = emptyMap()) =
        object : PermissionContributor {
            override fun permissions() = permissions
            override fun defaultGrants() = grants
        }

    private val platform = contributor(
        listOf(
            PermissionDefinition("MANAGE_USERS", "usuários"),
            PermissionDefinition("MANAGE_CLINICS", "clínicas", platformOnly = true),
        ),
    )
    private val registry = contributor(
        listOf(PermissionDefinition("VIEW_PATIENTS", "ver pacientes"), PermissionDefinition("MANAGE_PATIENTS", "gerir pacientes")),
        mapOf("DOCTOR" to setOf("VIEW_PATIENTS"), "STAFF" to setOf("VIEW_PATIENTS", "MANAGE_PATIENTS")),
    )

    @Test
    fun `gathers the contributions of every module`() {
        val catalog = PermissionCatalog(listOf(platform, registry))

        assertThat(catalog.all().map { it.code }).containsExactly("MANAGE_USERS", "MANAGE_CLINICS", "VIEW_PATIENTS", "MANAGE_PATIENTS")
        assertThat(catalog.contains("VIEW_PATIENTS")).isTrue()
        assertThat(catalog.contains("NOPE")).isFalse()
    }

    @Test
    fun `the clinic admin gets every clinic permission but no platform-only one`() {
        val catalog = PermissionCatalog(listOf(platform, registry))

        assertThat(catalog.defaultGrants.getValue("CLINIC_ADMIN")).containsExactlyInAnyOrder("MANAGE_USERS", "VIEW_PATIENTS", "MANAGE_PATIENTS")
        assertThat(catalog.defaultGrants.getValue("DOCTOR")).containsExactly("VIEW_PATIENTS")
        assertThat(catalog.defaultGrants.getValue("STAFF")).containsExactlyInAnyOrder("VIEW_PATIENTS", "MANAGE_PATIENTS")
        assertThat(catalog.assignable()).doesNotContain("MANAGE_CLINICS")
    }

    @Test
    fun `an unknown permission in a role is rejected`() {
        val catalog = PermissionCatalog(listOf(platform, registry))

        assertThatThrownBy { catalog.validateAssignable(listOf("VIEW_PATIENTS", "FLY_PLANES")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("FLY_PLANES")
    }

    @Test
    fun `a platform-only permission cannot be assigned to a clinic role`() {
        val catalog = PermissionCatalog(listOf(platform, registry))

        assertThatThrownBy { catalog.validateAssignable(listOf("MANAGE_CLINICS")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("MANAGE_CLINICS")
        catalog.validateAssignable(listOf("MANAGE_USERS", "VIEW_PATIENTS"))
    }

    @Test
    fun `a default grant that cites an unknown permission stops the startup`() {
        val bad = contributor(emptyList(), mapOf("NURSE" to setOf("VIEW_PATIENTS")))

        assertThatThrownBy { PermissionCatalog(listOf(platform, bad)) }.hasMessageContaining("desconhecida")
    }

    @Test
    fun `a default grant of a platform-only permission stops the startup`() {
        val bad = contributor(emptyList(), mapOf("MANAGER" to setOf("MANAGE_CLINICS")))

        assertThatThrownBy { PermissionCatalog(listOf(platform, bad)) }.hasMessageContaining("exclusiva da plataforma")
    }

    @Test
    fun `declaring grants for the clinic admin stops the startup`() {
        val bad = contributor(emptyList(), mapOf("CLINIC_ADMIN" to setOf("MANAGE_USERS")))

        assertThatThrownBy { PermissionCatalog(listOf(platform, bad)) }.hasMessageContaining("CLINIC_ADMIN")
    }

    @Test
    fun `the same permission declared twice with different definitions stops the startup`() {
        val other = contributor(listOf(PermissionDefinition("MANAGE_USERS", "outra descrição")))

        assertThatThrownBy { PermissionCatalog(listOf(platform, other)) }.hasMessageContaining("MANAGE_USERS")
    }

    @Test
    fun `the same permission declared twice with the same definition is accepted`() {
        val same = contributor(listOf(PermissionDefinition("MANAGE_USERS", "usuários")))

        assertThat(PermissionCatalog(listOf(platform, same)).all().count { it.code == "MANAGE_USERS" }).isEqualTo(1)
    }
}
