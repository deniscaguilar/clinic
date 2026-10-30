package com.endoacademy.clinic.notifications.application

import java.time.Instant
import java.util.UUID

/**
 * Falha de envio com um código estável (`EMAIL_REJECTED`, `EMAIL_UNAVAILABLE`...). A mensagem é só o código, nunca o
 * texto do provedor, que pode citar o destinatário. `retryable` diz se vale tentar de novo (rede, limite do provedor)
 * ou não (endereço ou remetente recusado).
 */
class NotificationSendException(val code: String, val retryable: Boolean) : RuntimeException(code)

/** E-mail a enviar. O destinatário existe só na memória do envio: não é gravado nem logado. O `toString` não o expõe. */
data class OutgoingEmail(val to: String, val subject: String, val body: String) {
    override fun toString(): String = "OutgoingEmail(***)"
}

/** Mensagem de WhatsApp a enviar, com a mesma regra do [OutgoingEmail]. */
data class OutgoingWhatsApp(val to: String, val body: String) {
    override fun toString(): String = "OutgoingWhatsApp(***)"
}

/** Envia o e-mail ou lança [NotificationSendException]. Adaptadores: SES (LocalStack local) e em memória (testes). */
interface EmailSender {
    fun send(email: OutgoingEmail)
}

/** Envia a mensagem de WhatsApp ou lança [NotificationSendException]. No M0 só há o adaptador falso (provedor a definir). */
interface WhatsAppSender {
    fun send(message: OutgoingWhatsApp)
}

/** Contato do paciente como o `notifications` precisa dele. O `toString` não expõe os dados. */
data class ContactDetails(val name: String, val whatsapp: String?, val email: String?) {
    override fun toString(): String = "ContactDetails(***)"
}

/** Contato do paciente, vindo do `registry` pela `registry.api`, no contexto de clínica do chamador. */
fun interface PatientContactLookup {
    fun find(patientId: UUID): ContactDetails?
}

/** O paciente tem consentimento de mensagens vigente? Resposta do `registry`; sem contexto de clínica, `false`. */
fun interface MessagingConsentLookup {
    fun hasActive(patientId: UUID): Boolean
}

/** O que a mensagem de confirmação precisa do aceite: o nome do tipo e quando foi aceito. Sem dado pessoal. */
data class ConsentInfo(val typeName: String, val acceptedAt: Instant)

/** Resumo do aceite `grantId` do paciente, pela `registry.api`; `null` se não existe ou é de outro paciente. */
fun interface ConsentInfoLookup {
    fun find(patientId: UUID, grantId: UUID): ConsentInfo?
}

/** Clínicas em que o worker deve procurar notificações: só as **ativas**. Vem da `platform.api`. */
fun interface ClinicDirectory {
    fun activeClinicIds(): List<UUID>
}
