package com.endoacademy.clinic.notifications.config

/** Provedores de e-mail aceitos. */
enum class EmailProvider(val value: String) {
    MEMORY("memory"),
    SES("ses");

    companion object {
        /** Aceita só os valores conhecidos: provedor desconhecido ou vazio derruba a subida da aplicação. */
        fun parse(text: String): EmailProvider =
            entries.firstOrNull { it.value == text } ?: throw IllegalArgumentException("clinic.notifications.email.provider precisa ser memory ou ses")
    }
}
