package com.endoacademy.clinic.registry.domain

import java.time.Instant
import java.util.UUID

/** Uma versão do texto de um tipo de consentimento. Imutável: alterar o texto cria outra versão, nunca edita esta. */
data class ConsentVersion(val number: Int, val text: String, val createdAt: Instant)

/** Por onde o aceite foi registrado. No M0 só na clínica, pela equipe. */
enum class ConsentChannel { IN_CLINIC }

/**
 * Tipo de consentimento da clínica (RF11): nome, se é obrigatório no cadastro, se controla o envio de mensagens e a
 * lista de versões do texto (1 a n, em ordem). Ativo ou não; um tipo inativo não recebe novos aceites.
 */
@ConsistentCopyVisibility
data class ConsentType private constructor(
    val id: UUID,
    val name: String,
    val requiredOnSignup: Boolean,
    val controlsMessaging: Boolean,
    val active: Boolean,
    val versions: List<ConsentVersion>,
) {

    /** A versão vigente: a última. */
    val current: ConsentVersion get() = versions.last()

    /** Alterar o texto cria a versão seguinte; as anteriores ficam como estavam (CA15). */
    fun revise(text: String, now: Instant): ConsentType {
        val newText = normalizeText(text)
        if (newText == current.text) throw RegistryException("CONSENT_TEXT_UNCHANGED")
        return copy(versions = versions + ConsentVersion(current.number + 1, newText, now))
    }

    /** Muda nome e opções sem criar versão (o texto que o paciente viu não muda). */
    fun update(name: String, requiredOnSignup: Boolean, controlsMessaging: Boolean, active: Boolean): ConsentType =
        copy(name = normalizeName(name), requiredOnSignup = requiredOnSignup, controlsMessaging = controlsMessaging, active = active)

    companion object {
        private const val MAX_NAME_LENGTH = 120
        private const val MAX_TEXT_LENGTH = 20_000

        fun create(id: UUID, name: String, text: String, requiredOnSignup: Boolean, controlsMessaging: Boolean, now: Instant): ConsentType =
            ConsentType(id, normalizeName(name), requiredOnSignup, controlsMessaging, true, listOf(ConsentVersion(1, normalizeText(text), now)))

        /** Reconstrói a partir do gravado; só confere que as versões vão de 1 a n, em ordem. */
        fun restore(
            id: UUID,
            name: String,
            requiredOnSignup: Boolean,
            controlsMessaging: Boolean,
            active: Boolean,
            versions: List<ConsentVersion>,
        ): ConsentType {
            if (versions.isEmpty() || versions.map { it.number } != (1..versions.size).toList()) throw RegistryException("CONSENT_VERSIONS_INVALID")
            return ConsentType(id, name, requiredOnSignup, controlsMessaging, active, versions.toList())
        }

        internal fun normalizeName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) throw RegistryException("CONSENT_NAME_REQUIRED")
            if (trimmed.length > MAX_NAME_LENGTH) throw RegistryException("CONSENT_NAME_TOO_LONG")
            return trimmed
        }

        internal fun normalizeText(text: String): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) throw RegistryException("CONSENT_TEXT_REQUIRED")
            if (trimmed.length > MAX_TEXT_LENGTH) throw RegistryException("CONSENT_TEXT_TOO_LONG")
            return trimmed
        }
    }
}

/**
 * Modelo de consentimento oferecido pelo `PLATFORM_ADMIN` (RF11). Sem versões e sem clínica: a clínica o **copia** para um
 * tipo seu ([toType]), e a cópia não muda se o modelo mudar depois. Não se apaga: sai de oferta ficando inativo.
 */
@ConsistentCopyVisibility
data class ConsentTemplate private constructor(
    val id: UUID,
    val name: String,
    val text: String,
    val requiredOnSignup: Boolean,
    val controlsMessaging: Boolean,
    val active: Boolean,
) {

    fun update(name: String, text: String, requiredOnSignup: Boolean, controlsMessaging: Boolean, active: Boolean): ConsentTemplate =
        copy(name = ConsentType.normalizeName(name), text = ConsentType.normalizeText(text), requiredOnSignup = requiredOnSignup, controlsMessaging = controlsMessaging, active = active)

    /** O tipo que a clínica recebe ao importar: versão 1 com o texto do modelo. */
    fun toType(typeId: UUID, now: Instant): ConsentType = ConsentType.create(typeId, name, text, requiredOnSignup, controlsMessaging, now)

    companion object {
        fun create(id: UUID, name: String, text: String, requiredOnSignup: Boolean, controlsMessaging: Boolean): ConsentTemplate =
            ConsentTemplate(id, ConsentType.normalizeName(name), ConsentType.normalizeText(text), requiredOnSignup, controlsMessaging, true)

        /** Reconstrói a partir do gravado. */
        fun restore(id: UUID, name: String, text: String, requiredOnSignup: Boolean, controlsMessaging: Boolean, active: Boolean): ConsentTemplate =
            ConsentTemplate(id, name, text, requiredOnSignup, controlsMessaging, active)
    }
}

/** Regras que dependem de mais de um tipo da mesma clínica. */
object ConsentRules {

    /**
     * Só um tipo **ativo** da clínica pode controlar o envio de mensagens (plano, tabela `consent_type`). `others` são
     * os tipos da clínica; o próprio `candidate` (mesmo id) é ignorado, o que permite editá-lo.
     */
    fun requireSingleMessagingControl(others: Collection<ConsentType>, candidate: ConsentType) {
        if (!candidate.active || !candidate.controlsMessaging) return
        if (others.any { it.id != candidate.id && it.active && it.controlsMessaging }) throw RegistryException("CONSENT_MESSAGING_TYPE_EXISTS")
    }
}

/**
 * Aceite de um tipo de consentimento por um paciente (RF12), preso à versão que valia quando foi dado. A revogação
 * preenche `revokedAt` e `revokedBy`; nada é apagado. Novo aceite depois de revogar é outro registro.
 */
@ConsistentCopyVisibility
data class ConsentGrant private constructor(
    val id: UUID,
    val patientId: UUID,
    val typeId: UUID,
    val versionNumber: Int,
    val acceptedAt: Instant,
    val channel: ConsentChannel,
    val acceptedBy: UUID,
    val revokedAt: Instant?,
    val revokedBy: UUID?,
) {

    val active: Boolean get() = revokedAt == null

    fun revoke(by: UUID, now: Instant): ConsentGrant {
        if (revokedAt != null) throw RegistryException("CONSENT_ALREADY_REVOKED")
        if (now.isBefore(acceptedAt)) throw RegistryException("CONSENT_REVOCATION_INVALID")
        return copy(revokedAt = now, revokedBy = by)
    }

    companion object {
        /** Liga o aceite à versão vigente do tipo, que precisa estar ativo. */
        fun accept(id: UUID, patientId: UUID, type: ConsentType, acceptedBy: UUID, now: Instant): ConsentGrant {
            if (!type.active) throw RegistryException("CONSENT_TYPE_INACTIVE")
            return ConsentGrant(id, patientId, type.id, type.current.number, now, ConsentChannel.IN_CLINIC, acceptedBy, null, null)
        }

        /** Reconstrói a partir do gravado. */
        fun restore(
            id: UUID,
            patientId: UUID,
            typeId: UUID,
            versionNumber: Int,
            acceptedAt: Instant,
            channel: ConsentChannel,
            acceptedBy: UUID,
            revokedAt: Instant?,
            revokedBy: UUID?,
        ): ConsentGrant = ConsentGrant(id, patientId, typeId, versionNumber, acceptedAt, channel, acceptedBy, revokedAt, revokedBy)
    }
}
