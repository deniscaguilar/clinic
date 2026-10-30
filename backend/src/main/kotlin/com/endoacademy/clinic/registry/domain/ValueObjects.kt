package com.endoacademy.clinic.registry.domain

/** Violação de regra de cadastro, com `code` estável (sem eco do valor recebido, que pode ser dado pessoal). */
class RegistryException(val code: String) : RuntimeException(code)

/** CPF guardado como 11 dígitos. O `toString` não expõe o número. */
@ConsistentCopyVisibility
data class Cpf private constructor(val value: String) {

    fun formatted(): String = "${value.substring(0, 3)}.${value.substring(3, 6)}.${value.substring(6, 9)}-${value.substring(9)}"

    override fun toString(): String = "Cpf(***)"

    companion object {
        private val PATTERN = Regex("[0-9]{11}|[0-9]{3}\\.[0-9]{3}\\.[0-9]{3}-[0-9]{2}")

        fun parse(text: String): Cpf {
            val trimmed = text.trim()
            if (!PATTERN.matches(trimmed)) throw RegistryException("CPF_INVALID")
            val digits = trimmed.filter { it in '0'..'9' }
            if (digits.all { it == digits[0] }) throw RegistryException("CPF_INVALID")
            if (checkDigit(digits, 9) != digits[9] - '0' || checkDigit(digits, 10) != digits[10] - '0') throw RegistryException("CPF_INVALID")
            return Cpf(digits)
        }

        /** Dígito verificador sobre os primeiros `length` dígitos, com pesos de `length + 1` até 2. */
        private fun checkDigit(digits: String, length: Int): Int {
            val sum = (0 until length).sumOf { (digits[it] - '0') * (length + 1 - it) }
            val rest = (sum * 10) % 11
            return if (rest == 10) 0 else rest
        }
    }
}

/** E-mail em minúsculas. O `toString` não expõe o endereço. */
@ConsistentCopyVisibility
data class Email private constructor(val value: String) {

    override fun toString(): String = "Email(***)"

    companion object {
        private const val MAX_LENGTH = 254
        private const val MAX_LOCAL_LENGTH = 64
        private const val ATOM = "[a-z0-9!#\$%&'*+/=?^_`{|}~-]+"
        private const val LABEL = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
        private val PATTERN = Regex("$ATOM(?:\\.$ATOM)*@$LABEL(?:\\.$LABEL)+")

        fun parse(text: String): Email {
            val normalized = text.trim().lowercase()
            if (normalized.length > MAX_LENGTH || normalized.substringBefore('@').length > MAX_LOCAL_LENGTH || !PATTERN.matches(normalized)) {
                throw RegistryException("EMAIL_INVALID")
            }
            return Email(normalized)
        }
    }
}

/**
 * Telefone brasileiro guardado só com DDD e número (10 dígitos fixo, 11 celular). Aceita máscara e o prefixo `55`.
 * O `toString` não expõe o número.
 */
@ConsistentCopyVisibility
data class Phone private constructor(val value: String) {

    val isMobile: Boolean get() = value.length == 11

    override fun toString(): String = "Phone(***)"

    companion object {
        private val PATTERN = Regex("\\+?[0-9 ()-]+")

        fun parse(text: String): Phone {
            val trimmed = text.trim()
            if (!PATTERN.matches(trimmed)) throw RegistryException("PHONE_INVALID")
            var digits = trimmed.filter { it in '0'..'9' }
            if (digits.length in 12..13 && digits.startsWith("55")) digits = digits.substring(2)
            val validAreaCode = digits.length >= 2 && digits[0] in '1'..'9' && digits[1] in '1'..'9'
            val validNumber = when (digits.length) {
                11 -> digits[2] == '9'
                10 -> digits[2] in '2'..'5'
                else -> false
            }
            if (!validAreaCode || !validNumber) throw RegistryException("PHONE_INVALID")
            return Phone(digits)
        }
    }
}

/** Endereço completo (RF5). CEP guardado com 8 dígitos e UF em maiúsculas. O `toString` não expõe o conteúdo. */
@ConsistentCopyVisibility
data class Address private constructor(
    val zipCode: String,
    val street: String,
    val number: String,
    val complement: String?,
    val district: String,
    val city: String,
    val state: String,
) {

    override fun toString(): String = "Address(***)"

    companion object {
        private const val MAX_LENGTH = 120
        private const val MAX_NUMBER_LENGTH = 20
        private val ZIP_CODE = Regex("[0-9]{8}|[0-9]{5}-[0-9]{3}")
        private val STATES = setOf(
            "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA",
            "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO",
        )

        fun of(zipCode: String, street: String, number: String, complement: String?, district: String, city: String, state: String): Address {
            val zip = zipCode.trim()
            if (!ZIP_CODE.matches(zip)) throw RegistryException("CEP_INVALID")
            val uf = state.trim().uppercase()
            if (uf !in STATES) throw RegistryException("UF_INVALID")
            return Address(
                zipCode = zip.replace("-", ""),
                street = required(street, "ADDRESS_STREET_REQUIRED", MAX_LENGTH),
                number = required(number, "ADDRESS_NUMBER_REQUIRED", MAX_NUMBER_LENGTH),
                complement = complement?.trim()?.takeIf { it.isNotEmpty() }?.also { limit(it, MAX_LENGTH) },
                district = required(district, "ADDRESS_DISTRICT_REQUIRED", MAX_LENGTH),
                city = required(city, "ADDRESS_CITY_REQUIRED", MAX_LENGTH),
                state = uf,
            )
        }

        private fun required(text: String, code: String, max: Int): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) throw RegistryException(code)
            return limit(trimmed, max)
        }

        private fun limit(text: String, max: Int): String {
            if (text.length > max) throw RegistryException("ADDRESS_FIELD_TOO_LONG")
            return text
        }
    }
}
