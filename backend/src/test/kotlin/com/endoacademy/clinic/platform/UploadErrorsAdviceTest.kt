package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.`in`.web.ProblemDetailsAdvice
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * O MockMvc não aplica o limite de multipart do contêiner, então os dois erros que só o contêiner levanta são
 * conferidos direto no tratador.
 */
class UploadErrorsAdviceTest {
    private val advice = ProblemDetailsAdvice()

    @Test
    fun `a file above the container limit answers 413 with a stable code`() {
        val response = advice.tooLarge()
        assertThat(response.statusCode.value()).isEqualTo(413)
        assertThat(response.body!!.properties).containsEntry("code", "FILE_TOO_LARGE")
    }

    @Test
    fun `a missing file part answers 400 with a stable code`() {
        val response = advice.missingPart()
        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body!!.properties).containsEntry("code", "FILE_REQUIRED")
    }
}
