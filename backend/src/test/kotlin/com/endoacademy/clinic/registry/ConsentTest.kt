package com.endoacademy.clinic.registry

import com.endoacademy.clinic.registry.domain.ConsentChannel
import com.endoacademy.clinic.registry.domain.ConsentGrant
import com.endoacademy.clinic.registry.domain.ConsentRules
import com.endoacademy.clinic.registry.domain.ConsentTemplate
import com.endoacademy.clinic.registry.domain.ConsentType
import com.endoacademy.clinic.registry.domain.ConsentVersion
import com.endoacademy.clinic.registry.domain.RegistryException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ConsentTest {

    private val t0 = Instant.parse("2026-10-30T10:00:00Z")
    private val t1 = Instant.parse("2026-10-30T11:00:00Z")
    private val t2 = Instant.parse("2026-10-30T12:00:00Z")
    private val typeId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val patientId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val user = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
    private val otherUser = UUID.fromString("00000000-0000-0000-0000-0000000000f2")

    private fun assertCode(code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(RegistryException::class.java) { assertThat(it.code).isEqualTo(code) }
    }

    private fun type(
        id: UUID = typeId,
        name: String = "Cadastro e mensagens",
        text: String = "Autorizo o cadastro e o envio de mensagens.",
        required: Boolean = true,
        messaging: Boolean = false,
    ) = ConsentType.create(id, name, text, required, messaging, t0)

    // --- ConsentType e ConsentVersion ---

    @Test
    fun `a new consent type starts active with version 1 holding the text and the creation time`() {
        val type = type(name = "  Cadastro e mensagens  ", text = "  Autorizo o cadastro.  ", messaging = true)
        assertThat(type.id).isEqualTo(typeId)
        assertThat(type.name).isEqualTo("Cadastro e mensagens")
        assertThat(type.requiredOnSignup).isTrue()
        assertThat(type.controlsMessaging).isTrue()
        assertThat(type.active).isTrue()
        assertThat(type.versions).containsExactly(ConsentVersion(1, "Autorizo o cadastro.", t0))
        assertThat(type.current.number).isEqualTo(1)
    }

    @Test
    fun `the name and the text are required and limited`() {
        listOf("", "   ").forEach { assertCode("CONSENT_NAME_REQUIRED") { type(name = it) } }
        assertThat(type(name = "x".repeat(120)).name).hasSize(120)
        assertCode("CONSENT_NAME_TOO_LONG") { type(name = "x".repeat(121)) }
        listOf("", "   ").forEach { assertCode("CONSENT_TEXT_REQUIRED") { type(text = it) } }
        assertThat(type(text = "x".repeat(20_000)).current.text).hasSize(20_000)
        assertCode("CONSENT_TEXT_TOO_LONG") { type(text = "x".repeat(20_001)) }
    }

    @Test
    fun `revising the text adds a new version and never edits the previous one (CA15)`() {
        val v1 = type(text = "Texto original")
        val v2 = v1.revise("Texto novo", t1)
        val v3 = v2.revise("Texto novíssimo", t2)

        assertThat(v3.versions.map { it.number }).containsExactly(1, 2, 3)
        assertThat(v3.versions.map { it.text }).containsExactly("Texto original", "Texto novo", "Texto novíssimo")
        assertThat(v3.versions.map { it.createdAt }).containsExactly(t0, t1, t2)
        assertThat(v3.current).isEqualTo(ConsentVersion(3, "Texto novíssimo", t2))
        // os objetos anteriores seguem como eram
        assertThat(v1.versions).hasSize(1)
        assertThat(v2.versions).hasSize(2)
        assertThat(v2.id).isEqualTo(v1.id)
    }

    @Test
    fun `a revision needs a different, non blank text within the limit`() {
        val v1 = type(text = "Texto original")
        assertCode("CONSENT_TEXT_UNCHANGED") { v1.revise("Texto original", t1) }
        assertCode("CONSENT_TEXT_UNCHANGED") { v1.revise("  Texto original  ", t1) }
        assertCode("CONSENT_TEXT_REQUIRED") { v1.revise("   ", t1) }
        assertCode("CONSENT_TEXT_TOO_LONG") { v1.revise("x".repeat(20_001), t1) }
        assertThat(v1.versions).hasSize(1)
    }

    @Test
    fun `changing the name or the flags does not create a version, and the type can be deactivated`() {
        val original = type(name = "Mensagens", messaging = true)
        val changed = original.update("Mensagens e imagem", requiredOnSignup = false, controlsMessaging = false, active = false)
        assertThat(changed.id).isEqualTo(original.id)
        assertThat(changed.name).isEqualTo("Mensagens e imagem")
        assertThat(changed.requiredOnSignup).isFalse()
        assertThat(changed.controlsMessaging).isFalse()
        assertThat(changed.active).isFalse()
        assertThat(changed.versions).isEqualTo(original.versions)
        assertThat(original.name).isEqualTo("Mensagens")
        assertCode("CONSENT_NAME_REQUIRED") { original.update(" ", true, true, true) }
        assertCode("CONSENT_NAME_TOO_LONG") { original.update("x".repeat(121), true, true, true) }
    }

    @Test
    fun `stored versions are restored as they are, but must be numbered 1 to n in order`() {
        val v1 = ConsentVersion(1, "a", t0)
        val v2 = ConsentVersion(2, "b", t1)
        val restored = ConsentType.restore(typeId, "Tipo", true, false, true, listOf(v1, v2))
        assertThat(restored.current).isEqualTo(v2)
        assertCode("CONSENT_VERSIONS_INVALID") { ConsentType.restore(typeId, "Tipo", true, false, true, emptyList()) }
        assertCode("CONSENT_VERSIONS_INVALID") { ConsentType.restore(typeId, "Tipo", true, false, true, listOf(v2)) }
        assertCode("CONSENT_VERSIONS_INVALID") { ConsentType.restore(typeId, "Tipo", true, false, true, listOf(v2, v1)) }
        assertCode("CONSENT_VERSIONS_INVALID") { ConsentType.restore(typeId, "Tipo", true, false, true, listOf(v1, ConsentVersion(3, "c", t2))) }
    }

    // --- regra do tipo único que controla mensagens ---

    @Test
    fun `only one active type of the clinic may control messaging`() {
        val existing = type(id = UUID.randomUUID(), name = "Mensagens", messaging = true)
        val candidate = type(name = "Outro de mensagens", messaging = true)
        assertCode("CONSENT_MESSAGING_TYPE_EXISTS") { ConsentRules.requireSingleMessagingControl(listOf(existing), candidate) }
    }

    @Test
    fun `the messaging rule ignores the type itself, inactive types and types that do not control messaging`() {
        val existing = type(id = UUID.randomUUID(), name = "Mensagens", messaging = true)
        val inactive = existing.update("Mensagens antigo", true, true, active = false)
        val plain = type(id = UUID.randomUUID(), name = "Imagem", messaging = false)
        val candidate = type(name = "Novo de mensagens", messaging = true)

        // o próprio tipo na lista (edição)
        ConsentRules.requireSingleMessagingControl(listOf(candidate), candidate)
        // o antigo foi desativado: pode haver outro
        ConsentRules.requireSingleMessagingControl(listOf(inactive), candidate)
        // quem não controla mensagens nunca conflita
        ConsentRules.requireSingleMessagingControl(listOf(existing), plain)
        // candidato inativo nunca conflita
        ConsentRules.requireSingleMessagingControl(listOf(existing), candidate.update(candidate.name, true, true, active = false))
        // tipos comuns na lista não atrapalham
        ConsentRules.requireSingleMessagingControl(listOf(plain, inactive), candidate)
    }

    // --- ConsentGrant ---

    @Test
    fun `an acceptance records the patient, the type, the current version, the time, the channel and the user`() {
        val type = type().revise("Texto novo", t1)
        val grant = ConsentGrant.accept(UUID.randomUUID(), patientId, type, user, t2)
        assertThat(grant.patientId).isEqualTo(patientId)
        assertThat(grant.typeId).isEqualTo(typeId)
        assertThat(grant.versionNumber).isEqualTo(2)
        assertThat(grant.acceptedAt).isEqualTo(t2)
        assertThat(grant.channel).isEqualTo(ConsentChannel.IN_CLINIC)
        assertThat(grant.acceptedBy).isEqualTo(user)
        assertThat(grant.revokedAt).isNull()
        assertThat(grant.revokedBy).isNull()
        assertThat(grant.active).isTrue()
    }

    @Test
    fun `an acceptance stays tied to the version the patient saw even after the text is revised (CA15)`() {
        val v1 = type(text = "Texto original")
        val grant = ConsentGrant.accept(UUID.randomUUID(), patientId, v1, user, t1)
        val v2 = v1.revise("Texto novo", t2)
        assertThat(v2.current.number).isEqualTo(2)
        assertThat(grant.versionNumber).isEqualTo(1)
        assertThat(v2.versions.first { it.number == grant.versionNumber }.text).isEqualTo("Texto original")
    }

    @Test
    fun `an inactive type cannot be accepted`() {
        val inactive = type().update("Cadastro e mensagens", true, false, active = false)
        assertCode("CONSENT_TYPE_INACTIVE") { ConsentGrant.accept(UUID.randomUUID(), patientId, inactive, user, t1) }
    }

    @Test
    fun `revoking fills who and when, keeps the acceptance data and does not erase anything`() {
        val grant = ConsentGrant.accept(UUID.randomUUID(), patientId, type(), user, t1)
        val revoked = grant.revoke(otherUser, t2)
        assertThat(revoked.revokedAt).isEqualTo(t2)
        assertThat(revoked.revokedBy).isEqualTo(otherUser)
        assertThat(revoked.active).isFalse()
        assertThat(revoked.id).isEqualTo(grant.id)
        assertThat(revoked.acceptedAt).isEqualTo(t1)
        assertThat(revoked.acceptedBy).isEqualTo(user)
        assertThat(revoked.versionNumber).isEqualTo(grant.versionNumber)
        assertThat(grant.active).isTrue()
    }

    @Test
    fun `a revoked acceptance cannot be revoked again and the revocation cannot precede the acceptance`() {
        val grant = ConsentGrant.accept(UUID.randomUUID(), patientId, type(), user, t1)
        assertCode("CONSENT_REVOCATION_INVALID") { grant.revoke(user, t0) }
        val revoked = grant.revoke(user, t2)
        assertCode("CONSENT_ALREADY_REVOKED") { revoked.revoke(otherUser, Instant.parse("2026-10-30T13:00:00Z")) }
        assertThat(revoked.revokedBy).isEqualTo(user)
        assertThat(grant.revoke(user, t1).revokedAt).isEqualTo(t1)
    }

    @Test
    fun `a stored acceptance is restored as it is`() {
        val grant = ConsentGrant.accept(UUID.randomUUID(), patientId, type(), user, t1).revoke(otherUser, t2)
        val restored = ConsentGrant.restore(grant.id, grant.patientId, grant.typeId, grant.versionNumber, grant.acceptedAt, grant.channel, grant.acceptedBy, grant.revokedAt, grant.revokedBy)
        assertThat(restored).isEqualTo(grant)
    }
    // --- ConsentTemplate (modelo oferecido pela plataforma) ---

    @Test
    fun `a template has no versions, starts active and is normalized`() {
        val template = ConsentTemplate.create(typeId, "  Cadastro e mensagens  ", "  Autorizo.  ", requiredOnSignup = true, controlsMessaging = true)
        assertThat(template.name).isEqualTo("Cadastro e mensagens")
        assertThat(template.text).isEqualTo("Autorizo.")
        assertThat(template.requiredOnSignup).isTrue()
        assertThat(template.controlsMessaging).isTrue()
        assertThat(template.active).isTrue()
        listOf("", "  ").forEach { assertCode("CONSENT_NAME_REQUIRED") { ConsentTemplate.create(typeId, it, "Texto", false, false) } }
        assertCode("CONSENT_NAME_TOO_LONG") { ConsentTemplate.create(typeId, "x".repeat(121), "Texto", false, false) }
        listOf("", "  ").forEach { assertCode("CONSENT_TEXT_REQUIRED") { ConsentTemplate.create(typeId, "Nome", it, false, false) } }
        assertCode("CONSENT_TEXT_TOO_LONG") { ConsentTemplate.create(typeId, "Nome", "x".repeat(20_001), false, false) }
    }

    @Test
    fun `updating a template applies the same rules and keeps the id`() {
        val template = ConsentTemplate.create(typeId, "Imagem", "Autorizo o uso.", false, false)
        val updated = template.update("Imagem e voz", "Autorizo o uso de imagem e voz.", requiredOnSignup = true, controlsMessaging = false, active = false)
        assertThat(updated.id).isEqualTo(typeId)
        assertThat(updated.name).isEqualTo("Imagem e voz")
        assertThat(updated.text).isEqualTo("Autorizo o uso de imagem e voz.")
        assertThat(updated.requiredOnSignup).isTrue()
        assertThat(updated.active).isFalse()
        assertThat(template.name).isEqualTo("Imagem")
        assertCode("CONSENT_NAME_REQUIRED") { template.update(" ", "Texto", false, false, true) }
        assertCode("CONSENT_TEXT_REQUIRED") { template.update("Nome", " ", false, false, true) }
    }

    @Test
    fun `importing a template creates a type with version 1 holding the template text`() {
        val template = ConsentTemplate.create(typeId, "Mensagens", "Autorizo mensagens.", requiredOnSignup = true, controlsMessaging = true)
        val newId = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
        val type = template.toType(newId, t1)
        assertThat(type.id).isEqualTo(newId)
        assertThat(type.name).isEqualTo("Mensagens")
        assertThat(type.requiredOnSignup).isTrue()
        assertThat(type.controlsMessaging).isTrue()
        assertThat(type.active).isTrue()
        assertThat(type.versions).containsExactly(ConsentVersion(1, "Autorizo mensagens.", t1))
        // alterar o modelo depois não muda o tipo já importado
        template.update("Mensagens 2", "Outro texto.", false, false, true)
        assertThat(type.current.text).isEqualTo("Autorizo mensagens.")
    }
}
