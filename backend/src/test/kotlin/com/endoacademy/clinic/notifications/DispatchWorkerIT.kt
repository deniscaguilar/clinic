package com.endoacademy.clinic.notifications

import com.endoacademy.clinic.notifications.adapter.out.email.InMemoryEmailSender
import com.endoacademy.clinic.notifications.adapter.out.whatsapp.FakeWhatsAppSender
import com.endoacademy.clinic.support.ClinicApiIT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * O agendador de verdade ligado (T5.9): do aceite ao e-mail e ao WhatsApp sem ninguém chamar o worker.
 * `@DirtiesContext`: o agendador varre **todas** as clínicas do banco compartilhado; se o contexto ficasse em cache, ele
 * continuaria enviando as notificações dos outros testes. Fecha o contexto (e o agendador) ao fim da classe.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(
    properties = [
        "clinic.notifications.worker.enabled=true",
        "clinic.notifications.worker.interval=PT0.3S",
        "clinic.notifications.worker.initial-delay=PT0S",
    ],
)
class DispatchWorkerIT : ClinicApiIT() {

    @Autowired lateinit var email: InMemoryEmailSender

    @Autowired lateinit var whatsApp: FakeWhatsAppSender

    @Autowired lateinit var context: ApplicationContext

    private fun idOf(result: ResultActions) = UUID.fromString(json.readTree(result.andReturn().response.contentAsString).get("id").asString())

    @Test
    fun `the scheduled worker exists when it is switched on`() {
        assertThat(context.containsBean("notificationWorker")).isTrue()
    }

    @Test
    fun `the scheduled worker confirms an acceptance on both channels by itself`() {
        val clinic = newClinic()
        val type = idOf(
            mockMvc.perform(
                call(post("/api/v1/consent-types").contentType(MediaType.APPLICATION_JSON).content(body("name" to "Cadastro e mensagens", "text" to "Autorizo.", "requiredOnSignup" to true, "controlsMessaging" to true)), clinic),
            ).andExpect(status().isCreated),
        )
        val address = "p-${UUID.randomUUID()}@example.com"
        mockMvc.perform(
            call(
                post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(
                    json.writeValueAsString(
                        mapOf(
                            "cpf" to "529.982.247-25", "fullName" to "Maria da Silva", "birthDate" to "1985-03-12", "whatsapp" to "(11) 98765-4321", "email" to address,
                            "address" to mapOf("zipCode" to "01310-100", "street" to "Avenida Paulista", "number" to "1578", "district" to "Bela Vista", "city" to "São Paulo", "state" to "SP"),
                            "consents" to listOf(mapOf("typeId" to type)),
                        ),
                    ),
                ),
                clinic,
            ),
        ).andExpect(status().isCreated)

        awaitUntil("o e-mail de confirmação") { email.sent.any { it.to == address } }
        val mail = email.sent.single { it.to == address }
        assertThat(mail.body).contains("Cadastro e mensagens")
        awaitUntil("a confirmação por WhatsApp") { whatsApp.sent.any { it.body == mail.body } }
    }
}
