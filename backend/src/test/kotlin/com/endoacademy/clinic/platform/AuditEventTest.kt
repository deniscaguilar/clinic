package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.api.AuditEvent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AuditEventTest {
    @Test
    fun `details accept field names`() {
        assertThat(AuditEvent("PATIENT_UPDATED", "PATIENT", "1", details = setOf("cpf", "address.zip_code")).details)
            .containsExactlyInAnyOrder("cpf", "address.zip_code")
    }

    @Test
    fun `details reject anything that looks like a value`() {
        listOf("123.456.789-09", "maria@example.com", "Maria da Silva", "", "1abc", "a".repeat(65)).forEach { value ->
            assertThatThrownBy { AuditEvent("A", "B", details = setOf(value)) }
                .describedAs("detalhe '%s'", value)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `action and entity type are required`() {
        assertThatThrownBy { AuditEvent(" ", "B") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AuditEvent("A", "") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
