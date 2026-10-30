package com.endoacademy.clinic.registry

import com.endoacademy.clinic.registry.domain.Address
import com.endoacademy.clinic.registry.domain.Cpf
import com.endoacademy.clinic.registry.domain.Email
import com.endoacademy.clinic.registry.domain.Phone
import com.endoacademy.clinic.registry.domain.RegistryException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ValueObjectsTest {

    private fun assertCode(code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(RegistryException::class.java) { assertThat(it.code).isEqualTo(code) }
    }

    // --- Cpf ---

    @Test
    fun `valid CPFs are accepted with or without formatting and stored as 11 digits`() {
        assertThat(Cpf.parse("52998224725").value).isEqualTo("52998224725")
        assertThat(Cpf.parse("529.982.247-25").value).isEqualTo("52998224725")
        assertThat(Cpf.parse("  529.982.247-25 ").value).isEqualTo("52998224725")
        assertThat(Cpf.parse("11144477735").value).isEqualTo("11144477735")
    }

    @Test
    fun `a check digit whose remainder is 10 is zero`() {
        // resto 10 no 1º dígito, no 2º e em ambos
        listOf("00000000604", "00000001830", "00000028100").forEach { assertThat(Cpf.parse(it).value).isEqualTo(it) }
        // o mesmo prefixo com o dígito errado (10 não vira 0) continua inválido
        listOf("00000000614", "00000001831", "00000028111").forEach { assertCode("CPF_INVALID") { Cpf.parse(it) } }
    }

    @Test
    fun `a CPF with a check digit that does not match is rejected`() {
        listOf("52998224724", "52998224735", "52998224625", "11144477736").forEach { assertCode("CPF_INVALID") { Cpf.parse(it) } }
    }

    @Test
    fun `repeated-digit sequences are rejected even when the check digits compute`() {
        (0..9).map { it.toString().repeat(11) }.forEach { assertCode("CPF_INVALID") { Cpf.parse(it) } }
    }

    @Test
    fun `CPFs with the wrong length or foreign characters are rejected`() {
        listOf("", "5299822472", "529982247255", "abcdefghijk", "529.982.247-2x", "529 982 247 25", "529.982.247-25; drop").forEach {
            assertCode("CPF_INVALID") { Cpf.parse(it) }
        }
    }

    @Test
    fun `a CPF is formatted for display and never leaks its value through toString`() {
        val cpf = Cpf.parse("52998224725")
        assertThat(cpf.formatted()).isEqualTo("529.982.247-25")
        assertThat(cpf.toString()).doesNotContain("52998224725").doesNotContain("529")
    }

    // --- Email ---

    @Test
    fun `emails are trimmed and lower-cased`() {
        assertThat(Email.parse("  Maria.Silva+Clinica@Example.COM ").value).isEqualTo("maria.silva+clinica@example.com")
    }

    @Test
    fun `malformed emails are rejected`() {
        listOf("", "maria", "maria@", "@example.com", "maria@example", "maria@@example.com", "ma ria@example.com", "maria@exa mple.com", "maria@.com", "maria@example..com", ".maria@example.com", "maria.@example.com", "a".repeat(250) + "@example.com")
            .forEach { assertCode("EMAIL_INVALID") { Email.parse(it) } }
    }

    @Test
    fun `an email does not leak its value through toString`() {
        assertThat(Email.parse("maria@example.com").toString()).doesNotContain("maria")
    }

    // --- Phone ---

    @Test
    fun `mobile numbers keep area code plus nine digits and accept common formatting`() {
        listOf("11987654321", "(11) 98765-4321", "+55 11 98765-4321", "55 (11) 9 8765-4321", " 11 98765 4321 ").forEach {
            val phone = Phone.parse(it)
            assertThat(phone.value).isEqualTo("11987654321")
            assertThat(phone.isMobile).isTrue()
        }
    }

    @Test
    fun `landlines have ten digits and are not mobile`() {
        val phone = Phone.parse("(11) 3456-7890")
        assertThat(phone.value).isEqualTo("1134567890")
        assertThat(phone.isMobile).isFalse()
    }

    @Test
    fun `numbers with an invalid area code, length or leading digit are rejected`() {
        listOf("", "1198765432", "119876543210", "10987654321", "01987654321", "11887654321", "1114567890", "1104567890", "abc", "(11) 98765-43x1", "+1 11 98765-4321")
            .forEach { assertCode("PHONE_INVALID") { Phone.parse(it) } }
    }

    @Test
    fun `a phone does not leak its value through toString`() {
        assertThat(Phone.parse("11987654321").toString()).doesNotContain("98765")
    }

    // --- Address ---

    private fun address(
        zipCode: String = "01310-100",
        street: String = "Avenida Paulista",
        number: String = "1578",
        complement: String? = "Sala 12",
        district: String = "Bela Vista",
        city: String = "São Paulo",
        state: String = "sp",
    ) = Address.of(zipCode, street, number, complement, district, city, state)

    @Test
    fun `a complete address is normalized`() {
        val a = address()
        assertThat(a.zipCode).isEqualTo("01310100")
        assertThat(a.state).isEqualTo("SP")
        assertThat(a.street).isEqualTo("Avenida Paulista")
        assertThat(a.complement).isEqualTo("Sala 12")
        assertThat(address(complement = null).complement).isNull()
        assertThat(address(complement = "   ").complement).isNull()
        assertThat(address(street = "  Rua A  ", city = " Recife ").street).isEqualTo("Rua A")
    }

    @Test
    fun `the zip code needs exactly eight digits`() {
        listOf("", "0131010", "013101000", "0131-0100", "abcdefgh", "01310-10x", "01.310-100").forEach { assertCode("CEP_INVALID") { address(zipCode = it) } }
        assertThat(address(zipCode = "01310100").zipCode).isEqualTo("01310100")
    }

    @Test
    fun `every Brazilian state is accepted and anything else is rejected`() {
        val all = "AC AL AP AM BA CE DF ES GO MA MT MS MG PA PB PR PE PI RJ RN RS RO RR SC SP SE TO".split(" ")
        assertThat(all).hasSize(27)
        all.forEach { assertThat(address(state = it).state).isEqualTo(it) }
        listOf("", "S", "SPP", "XX", "BR", "12").forEach { assertCode("UF_INVALID") { address(state = it) } }
    }

    @Test
    fun `required address fields cannot be blank and have a length limit`() {
        assertCode("ADDRESS_STREET_REQUIRED") { address(street = " ") }
        assertCode("ADDRESS_NUMBER_REQUIRED") { address(number = "") }
        assertCode("ADDRESS_DISTRICT_REQUIRED") { address(district = "") }
        assertCode("ADDRESS_CITY_REQUIRED") { address(city = "  ") }
        assertCode("ADDRESS_FIELD_TOO_LONG") { address(street = "x".repeat(121)) }
        assertCode("ADDRESS_FIELD_TOO_LONG") { address(complement = "x".repeat(121)) }
        assertThat(address(street = "x".repeat(120)).street).hasSize(120)
    }

    @Test
    fun `an address does not leak its content through toString`() {
        val text = address().toString()
        assertThat(text).doesNotContain("Paulista").doesNotContain("01310")
    }
}
