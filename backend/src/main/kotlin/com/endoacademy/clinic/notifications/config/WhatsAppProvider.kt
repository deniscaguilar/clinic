package com.endoacademy.clinic.notifications.config

/**
 * Provedores de WhatsApp aceitos. Por ora só o falso (`memory`): o provedor real e o custo ainda não foram decididos. Quando
 * forem, o novo adaptador entra aqui e produção deixa de aceitar `memory`.
 */
enum class WhatsAppProvider(val value: String) {
    MEMORY("memory");

    companion object {
        /** Aceita só os valores conhecidos: provedor desconhecido ou vazio derruba a subida da aplicação. */
        fun parse(text: String): WhatsAppProvider =
            entries.firstOrNull { it.value == text } ?: throw IllegalArgumentException("clinic.notifications.whatsapp.provider precisa ser memory")
    }
}
