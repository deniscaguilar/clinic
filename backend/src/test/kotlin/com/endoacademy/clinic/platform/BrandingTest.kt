package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.domain.Branding
import com.endoacademy.clinic.platform.domain.BrandingException
import com.endoacademy.clinic.platform.domain.ContrastPolicy
import com.endoacademy.clinic.platform.domain.HexColor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class BrandingTest {

    private fun color(hex: String) = HexColor.parse(hex)

    @Test
    fun `hex colors are normalized to upper case with a leading hash`() {
        assertThat(color("#0f62fe").value).isEqualTo("#0F62FE")
        assertThat(color(" #0F62FE ").value).isEqualTo("#0F62FE")
    }

    @Test
    fun `invalid hex colors are rejected`() {
        listOf("", "0F62FE", "#0F62F", "#0F62FEA", "#GGGGGG", "#12 456", "rgb(1,2,3)", "#0F62FE; x").forEach {
            assertThatThrownBy { HexColor.parse(it) }.isInstanceOfSatisfying(BrandingException::class.java) { e -> assertThat(e.code).isEqualTo("COLOR_INVALID") }
        }
    }

    @Test
    fun `the WCAG contrast ratio matches the known values`() {
        assertThat(ContrastPolicy.ratio(color("#000000"), color("#FFFFFF"))).isCloseTo(21.0, Offset.offset(0.001))
        assertThat(ContrastPolicy.ratio(color("#FFFFFF"), color("#FFFFFF"))).isCloseTo(1.0, Offset.offset(0.001))
        assertThat(ContrastPolicy.ratio(color("#767676"), color("#FFFFFF"))).isCloseTo(4.54, Offset.offset(0.01))
        assertThat(ContrastPolicy.ratio(color("#0F62FE"), color("#FFFFFF"))).isCloseTo(5.0, Offset.offset(0.02))
        assertThat(ContrastPolicy.ratio(color("#FFFFFF"), color("#000000"))).isEqualTo(ContrastPolicy.ratio(color("#000000"), color("#FFFFFF")))
    }

    @Test
    fun `the text on the primary is the one with more contrast, white on dark and black on light`() {
        assertThat(ContrastPolicy.evaluate(color("#0F62FE")).onPrimary.value).isEqualTo("#FFFFFF")
        assertThat(ContrastPolicy.evaluate(color("#000000")).onPrimary.value).isEqualTo("#FFFFFF")
        assertThat(ContrastPolicy.evaluate(color("#B71C1C")).onPrimary.value).isEqualTo("#FFFFFF")
        assertThat(ContrastPolicy.evaluate(color("#E65100")).onPrimary.value).isEqualTo("#000000")
        assertThat(ContrastPolicy.evaluate(color("#949494")).onPrimary.value).isEqualTo("#000000")
    }

    @Test
    fun `the best text always reaches 4_5, so the rule that can fail is the contrast with the white background`() {
        (0..255 step 5).forEach { v ->
            val gray = HexColor.parse("#%02X%02X%02X".format(v, v, v))
            assertThat(ContrastPolicy.evaluate(gray).textRatio).describedAs("gray %s", gray.value).isGreaterThanOrEqualTo(4.5)
        }
    }

    @Test
    fun `the primary needs 3 to 1 against the white background, with the boundary exactly at 3`() {
        assertThat(ContrastPolicy.accepts(color("#949494"))).isTrue() // 3,03:1
        assertThat(ContrastPolicy.accepts(color("#959595"))).isFalse() // 2,99:1
        assertThat(ContrastPolicy.evaluate(color("#949494")).backgroundRatio).isGreaterThanOrEqualTo(3.0)
        assertThat(ContrastPolicy.evaluate(color("#959595")).backgroundRatio).isLessThan(3.0)
    }

    @Test
    fun `legitimate dark and medium colors pass and washed out ones fail`() {
        listOf("#0F62FE", "#198038", "#E65100", "#8A3FFC", "#000000", "#6B7280").forEach { assertThat(ContrastPolicy.accepts(color(it))).describedAs(it).isTrue() }
        listOf("#FFFFFF", "#FFFF00", "#FFC0CB", "#FFB000", "#E0E0E0", "#A7F0BA").forEach { assertThat(ContrastPolicy.accepts(color(it))).describedAs(it).isFalse() }
    }

    @Test
    fun `branding computes onPrimary and rejects insufficient contrast with a stable code`() {
        val branding = Branding.of("Clínica da DCA", "#198038", "#6B7280", null, null)
        assertThat(branding.onPrimary.value).isEqualTo("#FFFFFF")
        assertThat(branding.primary.value).isEqualTo("#198038")

        assertThatThrownBy { Branding.of("Instituto", "#FFFF00", "#6B7280", null, null) }
            .isInstanceOfSatisfying(BrandingException::class.java) { assertThat(it.code).isEqualTo("BRAND_CONTRAST_INSUFFICIENT") }
    }

    @Test
    fun `the secondary color only needs to be a valid hex, no contrast rule`() {
        assertThat(Branding.of("Clínica", "#198038", "#FFFF00", null, null).secondary.value).isEqualTo("#FFFF00")
        assertThatThrownBy { Branding.of("Clínica", "#198038", "amarelo", null, null) }
            .isInstanceOfSatisfying(BrandingException::class.java) { assertThat(it.code).isEqualTo("COLOR_INVALID") }
    }

    @Test
    fun `display name must be present and at most 120 characters`() {
        assertThat(Branding.of("  Clínica Alfa ", "#198038", "#6B7280", null, null).displayName).isEqualTo("Clínica Alfa")
        listOf("", "   ", "x".repeat(121)).forEach {
            assertThatThrownBy { Branding.of(it, "#198038", "#6B7280", null, null) }
                .isInstanceOfSatisfying(BrandingException::class.java) { e -> assertThat(e.code).isEqualTo("DISPLAY_NAME_INVALID") }
        }
    }

    @Test
    fun `the default branding is valid and uses the clinic name`() {
        val branding = Branding.default("Clínica da DCA")
        assertThat(branding.displayName).isEqualTo("Clínica da DCA")
        assertThat(ContrastPolicy.accepts(branding.primary)).isTrue()
        assertThat(branding.logoKey).isNull()
        assertThat(Branding.default("x".repeat(200)).displayName).hasSize(120)
        assertThat(Branding.default("   ").displayName).isEqualTo("Clínica")
    }

    @Test
    fun `changing the logo or favicon keeps the colors`() {
        val branding = Branding.of("Clínica", "#198038", "#6B7280", null, null)
        val updated = branding.withLogo("branding/x/logo.png").withFavicon("branding/x/favicon.png")
        assertThat(updated.logoKey).isEqualTo("branding/x/logo.png")
        assertThat(updated.faviconKey).isEqualTo("branding/x/favicon.png")
        assertThat(updated.primary).isEqualTo(branding.primary)
    }
}
