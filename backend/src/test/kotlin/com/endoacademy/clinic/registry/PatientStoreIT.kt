package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.registry.application.PatientStore
import com.endoacademy.clinic.registry.domain.Address
import com.endoacademy.clinic.registry.domain.Cpf
import com.endoacademy.clinic.registry.domain.Email
import com.endoacademy.clinic.registry.domain.Patient
import com.endoacademy.clinic.registry.domain.Phone
import com.endoacademy.clinic.support.DatabaseIT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Persistência do paciente contra o Postgres real: mapeamento, RLS, unicidade do CPF e CHECKs (T4.5; CA1, CA2, CA14). */
class PatientStoreIT : DatabaseIT() {

    @Autowired
    lateinit var store: PatientStore

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val tx get() = TransactionTemplate(transactionManager)
    private val clock = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC)
    private var counter = 0

    /** CPF válido a partir de um número de 9 dígitos. */
    private fun validCpf(seed: Int): Cpf {
        val base = (100_000_000L + seed.toLong() * 7_919L % 800_000_000L).toString()
        fun digit(digits: String, length: Int): Int {
            val rest = (digits.take(length).withIndex().sumOf { (i, c) -> (c - '0') * (length + 1 - i) } * 10) % 11
            return if (rest == 10) 0 else rest
        }
        val first = digit(base, 9)
        val second = digit(base + first, 10)
        return Cpf.parse("$base$first$second")
    }

    private fun address(city: String = "São Paulo") = Address.of("01310-100", "Avenida Paulista", "1578", "Sala 12", "Bela Vista", city, "SP")

    private fun patient(
        name: String = "Maria da Silva",
        cpf: Cpf = validCpf(++counter),
        whatsapp: Phone? = Phone.parse("(11) 98765-4321"),
        email: Email? = Email.parse("maria@example.com"),
        landline: Phone? = Phone.parse("(11) 3456-7890"),
        address: Address = address(),
        birthDate: LocalDate = LocalDate.of(1985, 3, 12),
    ) = Patient.register(UUID.randomUUID(), name, cpf, birthDate, whatsapp, email, landline, address, clock)

    @Suppress("UNCHECKED_CAST")
    private fun <T> inClinic(clinic: UUID, block: () -> T): T = TenantScope.runAs(clinic) { tx.execute { block() } as T }

    @Test
    fun `a patient survives a round trip with every field`() {
        val clinic = UUID.randomUUID()
        val full = patient()
        val minimal = patient(name = "João Pereira", whatsapp = null, landline = null, address = Address.of("20040020", "Rua da Assembleia", "10", null, "Centro", "Rio de Janeiro", "RJ"))
        inClinic(clinic) {
            store.insert(clinic, full)
            store.insert(clinic, minimal)
        }
        assertThat(inClinic(clinic) { store.find(full.id) }).isEqualTo(full)
        assertThat(inClinic(clinic) { store.find(minimal.id) }).isEqualTo(minimal)
        assertThat(inClinic(clinic) { store.findByCpf(full.cpf) }).isEqualTo(full)
        assertThat(inClinic(clinic) { store.find(UUID.randomUUID()) }).isNull()
        assertThat(inClinic(clinic) { store.findByCpf(validCpf(999_999)) }).isNull()
    }

    @Test
    fun `another clinic sees nothing and changes nothing (CA1, CA2)`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val ofA = patient()
        inClinic(a) { store.insert(a, ofA) }

        assertThat(inClinic(b) { store.find(ofA.id) }).isNull()
        assertThat(inClinic(b) { store.findByCpf(ofA.cpf) }).isNull()
        assertThat(inClinic(b) { store.cpfTaken(ofA.cpf, null) }).isFalse()
        assertThat(inClinic(b) { store.list(null, 0, 50) }).isEmpty()
        assertThat(inClinic(b) { store.count(null) }).isZero()
        // sem contexto de clínica nada aparece
        assertThat(tx.execute { store.find(ofA.id) }).isNull()

        inClinic(b) { store.update(ofA.update("Nome Invadido", ofA.birthDate, ofA.whatsapp, ofA.email, ofA.landline, ofA.address, clock)) }
        assertThat(inClinic(a) { store.find(ofA.id) }).isEqualTo(ofA)
    }

    @Test
    fun `the CPF is unique per clinic and a duplicate becomes a 409 that does not echo it`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val cpf = validCpf(4242)
        inClinic(a) { store.insert(a, patient(cpf = cpf)) }
        inClinic(b) { store.insert(b, patient(cpf = cpf)) }

        assertThat(inClinic(a) { store.cpfTaken(cpf, null) }).isTrue()
        val second = patient(name = "Outra Pessoa", cpf = cpf)
        assertThatThrownBy { inClinic(a) { store.insert(a, second) } }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.kind).isEqualTo(ErrorKind.CONFLICT)
            assertThat(it.code).isEqualTo("CPF_ALREADY_REGISTERED")
            assertThat(it.message).doesNotContain(cpf.value)
        }

        val other = patient(name = "Terceira Pessoa")
        inClinic(a) { store.insert(a, other) }
        assertThatThrownBy { inClinic(a) { store.update(other.changeCpf(cpf)) } }.isInstanceOfSatisfying(ApiException::class.java) {
            assertThat(it.code).isEqualTo("CPF_ALREADY_REGISTERED")
        }
        assertThat(inClinic(a) { store.find(other.id) }).isEqualTo(other)
    }

    @Test
    fun `cpfTaken ignores the patient itself`() {
        val clinic = UUID.randomUUID()
        val p = patient()
        inClinic(clinic) { store.insert(clinic, p) }
        assertThat(inClinic(clinic) { store.cpfTaken(p.cpf, null) }).isTrue()
        assertThat(inClinic(clinic) { store.cpfTaken(p.cpf, p.id) }).isFalse()
        assertThat(inClinic(clinic) { store.cpfTaken(p.cpf, UUID.randomUUID()) }).isTrue()
    }

    @Test
    fun `update writes every field, including the CPF, and can clear the optional ones`() {
        val clinic = UUID.randomUUID()
        val original = patient()
        inClinic(clinic) { store.insert(clinic, original) }

        val newAddress = Address.of("30130-010", "Avenida Afonso Pena", "100", null, "Centro", "Belo Horizonte", "MG")
        val changed = original.update("Maria da Silva Souza", LocalDate.of(1985, 3, 13), null, Email.parse("nova@example.com"), null, newAddress, clock).changeCpf(validCpf(777))
        inClinic(clinic) { store.update(changed) }

        val stored = inClinic(clinic) { store.find(original.id) }!!
        assertThat(stored).isEqualTo(changed)
        assertThat(stored.whatsapp).isNull()
        assertThat(stored.landline).isNull()
        assertThat(stored.address.complement).isNull()
        assertThat(inClinic(clinic) { store.findByCpf(original.cpf) }).isNull()
    }

    @Test
    fun `listing is ordered by name ignoring case, filtered by a piece of the name, paged and counted`() {
        val clinic = UUID.randomUUID()
        listOf("beatriz Silva Lima", "Ana Souza", "Carlos da SILVA", "Daniel Prado").forEach {
            inClinic(clinic) { store.insert(clinic, patient(name = it)) }
        }
        // nome com curingas do SQL: o domínio não aceitaria, então entra direto; ler dado gravado não revalida
        insertRaw(clinic, "full_name" to "Eva_Rocha 100%")
        assertThat(inClinic(clinic) { store.list(null, 0, 50) }.map { it.fullName })
            .containsExactly("Ana Souza", "beatriz Silva Lima", "Carlos da SILVA", "Daniel Prado", "Eva_Rocha 100%")
        assertThat(inClinic(clinic) { store.count(null) }).isEqualTo(5)

        assertThat(inClinic(clinic) { store.list("silva", 0, 50) }.map { it.fullName }).containsExactly("beatriz Silva Lima", "Carlos da SILVA")
        assertThat(inClinic(clinic) { store.count("SILVA") }).isEqualTo(2)
        assertThat(inClinic(clinic) { store.list("inexistente", 0, 50) }).isEmpty()
        assertThat(inClinic(clinic) { store.count("inexistente") }).isZero()

        assertThat(inClinic(clinic) { store.list(null, 1, 2) }.map { it.fullName }).containsExactly("beatriz Silva Lima", "Carlos da SILVA")
        assertThat(inClinic(clinic) { store.list(null, 4, 2) }.map { it.fullName }).containsExactly("Eva_Rocha 100%")

        // curingas do SQL valem como texto comum
        assertThat(inClinic(clinic) { store.list("%", 0, 50) }.map { it.fullName }).containsExactly("Eva_Rocha 100%")
        assertThat(inClinic(clinic) { store.list("_", 0, 50) }.map { it.fullName }).containsExactly("Eva_Rocha 100%")
        assertThat(inClinic(clinic) { store.count("a_r") }).isEqualTo(1)
    }

    private fun insertRaw(clinic: UUID, vararg overrides: Pair<String, Any?>) {
        val columns = linkedMapOf<String, Any?>(
            "id" to UUID.randomUUID(), "tenant_id" to clinic, "full_name" to "Paciente de Teste", "cpf" to validCpf(++counter).value,
            "birth_date" to LocalDate.of(1990, 1, 1), "whatsapp" to "11987654321", "email" to null, "landline" to null, "zip_code" to "01310100",
            "street" to "Rua A", "street_number" to "1", "complement" to null, "district" to "Centro", "city" to "São Paulo", "state" to "SP",
        )
        overrides.forEach { columns[it.first] = it.second }
        TenantScope.runAs(clinic) {
            tx.executeWithoutResult {
                jdbc.update("insert into registry.patient (${columns.keys.joinToString()}) values (${columns.keys.joinToString { "?" }})", *columns.values.toTypedArray())
            }
        }
    }

    @Test
    fun `the database refuses data that breaks the registration rules`() {
        val clinic = UUID.randomUUID()
        insertRaw(clinic) // a linha-base é aceita
        val checks = mapOf(
            "sem nenhum contato" to arrayOf("whatsapp" to null, "email" to null),
            "CPF com letras" to arrayOf("cpf" to "1234567890a"),
            "CPF curto" to arrayOf("cpf" to "1234567890"),
            "WhatsApp que não é celular" to arrayOf("whatsapp" to "1134567890"),
            "telefone fixo que é celular" to arrayOf("landline" to "11987654321"),
            "CEP curto" to arrayOf("zip_code" to "0131010"),
            "UF inexistente" to arrayOf("state" to "XX"),
            "nascimento antes de 1900" to arrayOf("birth_date" to LocalDate.of(1899, 12, 31)),
            "nome em branco" to arrayOf("full_name" to "   "),
            "rua em branco" to arrayOf("street" to " "),
        )
        checks.forEach { (case, overrides) ->
            assertThatThrownBy { insertRaw(clinic, *overrides) }.describedAs(case).rootCause().hasMessageContaining("violates check constraint")
        }
        // só e-mail como contato é aceito
        insertRaw(clinic, "whatsapp" to null, "email" to "so-email@example.com")
    }

    @Test
    fun `the application cannot delete patients`() {
        val clinic = UUID.randomUUID()
        val p = patient()
        inClinic(clinic) { store.insert(clinic, p) }
        assertThatThrownBy { inClinic(clinic) { jdbc.update("delete from registry.patient where id = ?", p.id) } }.rootCause().hasMessageContaining("permission denied")
        assertThat(inClinic(clinic) { store.find(p.id) }).isEqualTo(p)
    }
}
