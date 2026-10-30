package com.endoacademy.clinic.platform.domain

import kotlin.math.pow

/** Violação de regra de branding, com `code` estável (sem eco do valor recebido). */
class BrandingException(val code: String) : RuntimeException(code)

/** Cor `#RRGGBB`, normalizada em maiúsculas. */
@ConsistentCopyVisibility
data class HexColor private constructor(val value: String) {

    private fun channel(offset: Int): Double {
        val c = value.substring(offset, offset + 2).toInt(16) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** Luminância relativa do WCAG 2.x. */
    val luminance: Double get() = 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5)

    companion object {
        private val PATTERN = Regex("#[0-9A-Fa-f]{6}")
        val WHITE = HexColor("#FFFFFF")
        val BLACK = HexColor("#000000")

        fun parse(text: String): HexColor {
            val trimmed = text.trim()
            if (!PATTERN.matches(trimmed)) throw BrandingException("COLOR_INVALID")
            return HexColor(trimmed.uppercase())
        }
    }
}

data class ContrastCheck(val onPrimary: HexColor, val textRatio: Double, val backgroundRatio: Double)

/**
 * Regra de contraste da cor primária (plano, seção 12/13):
 * - o texto sobre a primária é o melhor entre branco e preto; a razão com ele é sempre >= 4,58, então esta parte
 *   nunca reprova e serve só para calcular `onPrimary`;
 * - a primária precisa de pelo menos 3:1 contra o fundo branco do app (WCAG 1.4.11, componentes de interface). Esta é
 *   a parte que pode reprovar (cores muito claras).
 */
object ContrastPolicy {
    const val MIN_BACKGROUND_RATIO = 3.0

    fun ratio(a: HexColor, b: HexColor): Double {
        val lighter = maxOf(a.luminance, b.luminance)
        val darker = minOf(a.luminance, b.luminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun evaluate(primary: HexColor): ContrastCheck {
        val white = ratio(primary, HexColor.WHITE)
        val black = ratio(primary, HexColor.BLACK)
        val onPrimary = if (white >= black) HexColor.WHITE else HexColor.BLACK
        return ContrastCheck(onPrimary, maxOf(white, black), white)
    }

    fun accepts(primary: HexColor): Boolean = evaluate(primary).backgroundRatio >= MIN_BACKGROUND_RATIO
}

/** Identidade visual de uma clínica. `onPrimary` é calculado, nunca informado. */
@ConsistentCopyVisibility
data class Branding private constructor(
    val displayName: String,
    val primary: HexColor,
    val secondary: HexColor,
    val logoKey: String?,
    val faviconKey: String?,
) {
    val onPrimary: HexColor get() = ContrastPolicy.evaluate(primary).onPrimary

    fun withLogo(key: String?) = copy(logoKey = key)

    fun withFavicon(key: String?) = copy(faviconKey = key)

    companion object {
        const val MAX_NAME = 120
        private const val DEFAULT_PRIMARY = "#0F62FE"
        private const val DEFAULT_SECONDARY = "#6B7280"
        private const val FALLBACK_NAME = "Clínica"

        fun of(displayName: String, primary: String, secondary: String, logoKey: String?, faviconKey: String?): Branding {
            val name = displayName.trim()
            if (name.isEmpty() || name.length > MAX_NAME) throw BrandingException("DISPLAY_NAME_INVALID")
            val primaryColor = HexColor.parse(primary)
            val secondaryColor = HexColor.parse(secondary)
            if (!ContrastPolicy.accepts(primaryColor)) throw BrandingException("BRAND_CONTRAST_INSUFFICIENT")
            return Branding(name, primaryColor, secondaryColor, logoKey, faviconKey)
        }

        /** Marca inicial de uma clínica nova: nome da clínica e cores neutras que passam na regra. */
        fun default(clinicName: String): Branding =
            of(clinicName.trim().take(MAX_NAME).ifEmpty { FALLBACK_NAME }, DEFAULT_PRIMARY, DEFAULT_SECONDARY, null, null)
    }
}
