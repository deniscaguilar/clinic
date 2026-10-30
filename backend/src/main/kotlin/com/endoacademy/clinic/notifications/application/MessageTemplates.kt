package com.endoacademy.clinic.notifications.application

import com.endoacademy.clinic.notifications.domain.NotificationKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Assunto e texto de uma mensagem. */
data class Message(val subject: String, val body: String)

/**
 * Textos das mensagens (pt-BR), com o **mínimo** de dado: o nome do tipo de consentimento e a data do aceite, na data da
 * clínica (America/Sao_Paulo). Nunca o nome do paciente, o CPF nem o conteúdo do consentimento (RF13).
 */
object MessageTemplates {
    private val clinicZone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val date: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun build(kind: NotificationKind, info: ConsentInfo): Message = when (kind) {
        NotificationKind.CONSENT_ACCEPTED -> Message(
            subject = "Confirmação de consentimento",
            body = "Confirmamos o seu aceite do consentimento \"${info.typeName}\" em ${date.format(info.acceptedAt.atZone(clinicZone))}. " +
                "Se não foi você, entre em contato com a clínica.",
        )
    }
}
