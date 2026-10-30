package com.endoacademy.clinic.registry

import com.endoacademy.clinic.registry.domain.Address
import com.endoacademy.clinic.registry.domain.Cpf
import com.endoacademy.clinic.registry.domain.Email
import com.endoacademy.clinic.registry.domain.Patient
import com.endoacademy.clinic.registry.domain.Phone
import com.endoacademy.clinic.registry.domain.RegistryException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PatientTest {

    // 30/10/2026 02:00 UTC = 29/10/2026 23:00 em São Paulo: "hoje" para a clínica ainda é dia 29.
    private val clock = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneOffset.UTC)
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val cpf = Cpf.parse("529.982.247-25")
    private val mobile = Phone.parse("(11) 98765-4321")
    private val landline = Phone.parse("(11) 3456-7890")
    private val email = Email.parse("maria@example.com")
    private val address = Address.of("01310-100", "Avenida Paulista", "1578", null, "Bela Vista", "São Paulo", "SP")

    private fun assertCode(code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(RegistryException::class.java) { assertThat(it.code).isEqualTo(code) }
    }

    private fun register(
        fullName: String = "Maria da Silva",
        birthDate: LocalDate = LocalDate.of(1985, 3, 12),
        whatsapp: Phone? = mobile,
        email: Email? = this.email,
        landline: Phone? = this.landline,
    ) = Patient.register(id, fullName, cpf, birthDate, whatsapp, email, landline, address, clock)

    @Test
    fun `a complete patient is registered`() {
        val patient = register()
        assertThat(patient.id).isEqualTo(id)
        assertThat(patient.fullName).isEqualTo("Maria da Silva")
        assertThat(patient.cpf).isEqualTo(cpf)
        assertThat(patient.birthDate).isEqualTo(LocalDate.of(1985, 3, 12))
        assertThat(patient.whatsapp).isEqualTo(mobile)
        assertThat(patient.email).isEqualTo(email)
        assertThat(patient.landline).isEqualTo(landline)
        assertThat(patient.address).isEqualTo(address)
    }

    @Test
    fun `either WhatsApp or e-mail is enough as the contact`() {
        assertThat(register(whatsapp = mobile, email = null, landline = null).email).isNull()
        assertThat(register(whatsapp = null, email = email, landline = null).whatsapp).isNull()
    }

    @Test
    fun `a patient with no WhatsApp and no e-mail is rejected even with a landline`() {
        assertCode("PATIENT_CONTACT_REQUIRED") { register(whatsapp = null, email = null) }
        assertCode("PATIENT_CONTACT_REQUIRED") { register(whatsapp = null, email = null, landline = null) }
    }

    @Test
    fun `the WhatsApp must be a mobile number and the landline must not be one`() {
        assertCode("PATIENT_WHATSAPP_NOT_MOBILE") { register(whatsapp = landline) }
        assertCode("PATIENT_LANDLINE_IS_MOBILE") { register(landline = mobile) }
    }

    @Test
    fun `the name needs at least two words and is normalized`() {
        assertThat(register(fullName = "  Maria   da   Silva ").fullName).isEqualTo("Maria da Silva")
        assertThat(register(fullName = "Ana-Lúcia D'Ávila Jr.").fullName).isEqualTo("Ana-Lúcia D'Ávila Jr.")
        listOf("", "   ", "Maria").forEach { assertCode("PATIENT_FULL_NAME_REQUIRED") { register(fullName = it) } }
    }

    @Test
    fun `names with digits, symbols or control characters are rejected`() {
        listOf("Maria 2 Silva", "Maria <b>Silva", "Maria;Silva", "Maria\nSilva", "Maria_Silva", "123 456").forEach {
            assertCode("PATIENT_NAME_INVALID") { register(fullName = it) }
        }
    }

    @Test
    fun `the name has a length limit`() {
        assertThat(register(fullName = "Maria " + "a".repeat(144)).fullName).hasSize(150)
        assertCode("PATIENT_NAME_TOO_LONG") { register(fullName = "Maria " + "a".repeat(145)) }
    }

    @Test
    fun `the birth date must be in the past in the clinic time zone`() {
        // pelo relógio UTC já seria dia 30, mas em São Paulo ainda é 29
        assertThat(register(birthDate = LocalDate.of(2026, 10, 28)).birthDate).isEqualTo(LocalDate.of(2026, 10, 28))
        assertCode("PATIENT_BIRTH_DATE_INVALID") { register(birthDate = LocalDate.of(2026, 10, 29)) }
        assertCode("PATIENT_BIRTH_DATE_INVALID") { register(birthDate = LocalDate.of(2026, 10, 30)) }
        assertCode("PATIENT_BIRTH_DATE_INVALID") { register(birthDate = LocalDate.of(2027, 1, 1)) }
    }

    @Test
    fun `an implausibly old birth date is rejected`() {
        assertThat(register(birthDate = LocalDate.of(1900, 1, 1)).birthDate).isEqualTo(LocalDate.of(1900, 1, 1))
        assertCode("PATIENT_BIRTH_DATE_INVALID") { register(birthDate = LocalDate.of(1899, 12, 31)) }
    }

    @Test
    fun `update keeps the id and the CPF and applies the same rules`() {
        val patient = register()
        val newAddress = Address.of("20040-020", "Rua da Assembleia", "10", "sala 3", "Centro", "Rio de Janeiro", "RJ")
        val updated = patient.update("Maria da Silva Souza", LocalDate.of(1985, 3, 13), null, email, null, newAddress, clock)
        assertThat(updated.id).isEqualTo(id)
        assertThat(updated.cpf).isEqualTo(cpf)
        assertThat(updated.fullName).isEqualTo("Maria da Silva Souza")
        assertThat(updated.whatsapp).isNull()
        assertThat(updated.address).isEqualTo(newAddress)
        assertThat(patient.fullName).isEqualTo("Maria da Silva")

        assertCode("PATIENT_CONTACT_REQUIRED") { patient.update("Maria da Silva", LocalDate.of(1985, 3, 12), null, null, landline, address, clock) }
        assertCode("PATIENT_FULL_NAME_REQUIRED") { patient.update("Maria", LocalDate.of(1985, 3, 12), mobile, email, landline, address, clock) }
        assertCode("PATIENT_BIRTH_DATE_INVALID") { patient.update("Maria da Silva", LocalDate.of(2026, 10, 29), mobile, email, landline, address, clock) }
    }

    @Test
    fun `changing the CPF is a separate operation that keeps the rest`() {
        val patient = register()
        val other = Cpf.parse("111.444.777-35")
        val changed = patient.changeCpf(other)
        assertThat(changed.cpf).isEqualTo(other)
        assertThat(changed.id).isEqualTo(id)
        assertThat(changed.fullName).isEqualTo(patient.fullName)
        assertThat(patient.cpf).isEqualTo(cpf)
    }

    @Test
    fun `a patient does not leak personal data through toString`() {
        val text = register().toString()
        assertThat(text).doesNotContain("Maria").doesNotContain("529").doesNotContain("1985").doesNotContain("example.com").doesNotContain("98765")
    }

    @Test
    fun `a stored patient is restored as is, without the clock and without re-validating`() {
        val original = register()
        val restored = Patient.restore(original.id, original.fullName, original.cpf, original.birthDate, original.whatsapp, original.email, original.landline, original.address)
        assertThat(restored).isEqualTo(original)
        // dado já gravado é confiável: leitura não falha por regra que depende da data de hoje
        val future = Patient.restore(id, "Maria da Silva", cpf, LocalDate.of(2999, 1, 1), null, email, null, address)
        assertThat(future.birthDate).isEqualTo(LocalDate.of(2999, 1, 1))
    }
}
