package com.endoacademy.clinic.registry.domain

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Paciente da clínica (RF5). Os invariantes valem no cadastro e em toda alteração: nome completo, CPF, nascimento no
 * passado, endereço e pelo menos um contato (WhatsApp ou e-mail). O consentimento é verificado fora daqui (T5.5).
 * O `toString` não expõe dado pessoal.
 */
@ConsistentCopyVisibility
data class Patient private constructor(
    val id: UUID,
    val fullName: String,
    val cpf: Cpf,
    val birthDate: LocalDate,
    val whatsapp: Phone?,
    val email: Email?,
    val landline: Phone?,
    val address: Address,
) {

    /** Atualiza os dados cadastrais com as mesmas regras do cadastro. O CPF só muda por [changeCpf]. */
    fun update(fullName: String, birthDate: LocalDate, whatsapp: Phone?, email: Email?, landline: Phone?, address: Address, clock: Clock): Patient =
        register(id, fullName, cpf, birthDate, whatsapp, email, landline, address, clock)

    fun changeCpf(newCpf: Cpf): Patient = copy(cpf = newCpf)

    override fun toString(): String = "Patient(id=$id)"

    companion object {
        private const val MAX_NAME_LENGTH = 150
        private val NAME = Regex("\\p{L}[\\p{L} .'’-]*")
        private val OLDEST_BIRTH_DATE = LocalDate.of(1900, 1, 1)
        private val CLINIC_ZONE = ZoneId.of("America/Sao_Paulo")

        fun register(
            id: UUID,
            fullName: String,
            cpf: Cpf,
            birthDate: LocalDate,
            whatsapp: Phone?,
            email: Email?,
            landline: Phone?,
            address: Address,
            clock: Clock,
        ): Patient {
            val name = normalizeName(fullName)
            val today = LocalDate.now(clock.withZone(CLINIC_ZONE))
            if (!birthDate.isBefore(today) || birthDate.isBefore(OLDEST_BIRTH_DATE)) throw RegistryException("PATIENT_BIRTH_DATE_INVALID")
            if (whatsapp != null && !whatsapp.isMobile) throw RegistryException("PATIENT_WHATSAPP_NOT_MOBILE")
            if (landline != null && landline.isMobile) throw RegistryException("PATIENT_LANDLINE_IS_MOBILE")
            if (whatsapp == null && email == null) throw RegistryException("PATIENT_CONTACT_REQUIRED")
            return Patient(id, name, cpf, birthDate, whatsapp, email, landline, address)
        }

        /** Reconstrói a partir do que está gravado. O dado gravado é confiável: sem relógio e sem revalidar. */
        fun restore(
            id: UUID,
            fullName: String,
            cpf: Cpf,
            birthDate: LocalDate,
            whatsapp: Phone?,
            email: Email?,
            landline: Phone?,
            address: Address,
        ): Patient = Patient(id, fullName, cpf, birthDate, whatsapp, email, landline, address)

        private fun normalizeName(text: String): String {
            val name = text.trim().replace(Regex(" +"), " ")
            if (name.isEmpty()) throw RegistryException("PATIENT_FULL_NAME_REQUIRED")
            if (!NAME.matches(name)) throw RegistryException("PATIENT_NAME_INVALID")
            if (name.split(' ').size < 2) throw RegistryException("PATIENT_FULL_NAME_REQUIRED")
            if (name.length > MAX_NAME_LENGTH) throw RegistryException("PATIENT_NAME_TOO_LONG")
            return name
        }
    }
}
