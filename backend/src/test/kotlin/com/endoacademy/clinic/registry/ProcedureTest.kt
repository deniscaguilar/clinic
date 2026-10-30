package com.endoacademy.clinic.registry

import com.endoacademy.clinic.registry.domain.Procedure
import com.endoacademy.clinic.registry.domain.RegistryException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ProcedureTest {

    private val id = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    private fun assertCode(code: String, block: () -> Unit) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(RegistryException::class.java) { assertThat(it.code).isEqualTo(code) }
    }

    @Test
    fun `a new procedure starts active with a trimmed name and description`() {
        val procedure = Procedure.create(id, "  Consulta endocrinológica  ", "  Primeira consulta  ")
        assertThat(procedure.id).isEqualTo(id)
        assertThat(procedure.name).isEqualTo("Consulta endocrinológica")
        assertThat(procedure.description).isEqualTo("Primeira consulta")
        assertThat(procedure.active).isTrue()
    }

    @Test
    fun `a blank description becomes null`() {
        assertThat(Procedure.create(id, "Retorno", null).description).isNull()
        assertThat(Procedure.create(id, "Retorno", "   ").description).isNull()
    }

    @Test
    fun `the name is required and limited to 120 characters`() {
        listOf("", "   ").forEach { assertCode("PROCEDURE_NAME_REQUIRED") { Procedure.create(id, it, null) } }
        assertThat(Procedure.create(id, "x".repeat(120), null).name).hasSize(120)
        assertCode("PROCEDURE_NAME_TOO_LONG") { Procedure.create(id, "x".repeat(121), null) }
    }

    @Test
    fun `the description is limited to 500 characters`() {
        assertThat(Procedure.create(id, "Retorno", "x".repeat(500)).description).hasSize(500)
        assertCode("PROCEDURE_DESCRIPTION_TOO_LONG") { Procedure.create(id, "Retorno", "x".repeat(501)) }
    }

    @Test
    fun `update changes the fields, keeps the id and applies the same rules`() {
        val procedure = Procedure.create(id, "Retorno", "Antigo")
        val updated = procedure.update("Retorno em 30 dias", null, false)
        assertThat(updated.id).isEqualTo(id)
        assertThat(updated.name).isEqualTo("Retorno em 30 dias")
        assertThat(updated.description).isNull()
        assertThat(updated.active).isFalse()
        assertThat(procedure.name).isEqualTo("Retorno")
        assertCode("PROCEDURE_NAME_REQUIRED") { procedure.update(" ", null, true) }
        assertCode("PROCEDURE_NAME_TOO_LONG") { procedure.update("x".repeat(121), null, true) }
        assertCode("PROCEDURE_DESCRIPTION_TOO_LONG") { procedure.update("Retorno", "x".repeat(501), true) }
    }
}
