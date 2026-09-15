package com.arctechnology.zerofomo.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.arctechnology.zerofomo.model.Country

/** The brand tokens the country rules lean on (docs/BRAND.md §3). */
object Brand {
    const val VOID = 0xFF0B0F14L
    const val PAPER = 0xFFF5F4EFL
    const val VOLT = 0xFFC8FF2EL
    const val AQUA = 0xFF19D3C5L
}

/**
 * Country-derived accent set, ARGB longs (pure Kotlin, testable).
 * Rules (product decision 2026-09-15, "mark + accents"):
 *  - the two flag colours become the two arms of the mark and the
 *    primary/secondary accents; Void, Paper, Coral and type never change;
 *  - every arm keeps >= 3:1 against its ground, every accent >= 4.5:1, by
 *    nudging lightness only (hue is the country's identity);
 *  - a white/black/grey flag colour is kept where it already contrasts
 *    (a white arm on Void) and replaced with the brand accent otherwise;
 *  - if both arms collapse into the same colour, the right arm falls back to
 *    brand Aqua so the mark still reads as two arms.
 */
data class CountryPalette(
    val armA: Long,
    val armB: Long,
    val primary: Long,
    val onPrimary: Long,
    val secondary: Long,
    val onSecondary: Long,
)

fun countryPalette(country: Country?, dark: Boolean): CountryPalette {
    val ground = if (dark) Brand.VOID else Brand.PAPER
    val brandA = ContrastMath.ensureContrast(Brand.VOLT, ground, 3.0, dark)
    val brandB = ContrastMath.ensureContrast(Brand.AQUA, ground, 3.0, dark)

    fun arm(raw: Long?, fallback: Long): Long {
        if (raw == null) return fallback
        val neutral = ContrastMath.saturation(raw) < 0.12
        if (neutral) {
            return if (ContrastMath.contrast(raw, ground) >= 3.0) raw else fallback
        }
        return ContrastMath.ensureContrast(raw, ground, 3.0, lighten = dark)
    }

    var a = arm(country?.colorA, brandA)
    var b = arm(country?.colorB, brandB)
    if (ContrastMath.tooSimilar(a, b)) b = if (ContrastMath.tooSimilar(a, brandB)) brandA else brandB
    if (ContrastMath.tooSimilar(a, b)) a = brandA

    fun accent(c: Long): Long {
        val neutral = ContrastMath.saturation(c) < 0.12
        // Grey accents look like disabled UI; fall back to the brand accent.
        val base = if (neutral) (if (c == a) brandA else brandB) else c
        return ContrastMath.ensureContrast(base, ground, 4.5, lighten = dark)
    }
    val primary = accent(a)
    val secondary = accent(b)
    fun on(c: Long): Long =
        if (ContrastMath.contrast(c, Brand.VOID) >= ContrastMath.contrast(c, Brand.PAPER)) Brand.VOID
        else Brand.PAPER
    return CountryPalette(a, b, primary, on(primary), secondary, on(secondary))
}

/** Compose view of the palette. */
data class CountryAccents(
    val armA: Color,
    val armB: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
)

fun CountryPalette.toAccents() = CountryAccents(
    Color(armA), Color(armB), Color(primary), Color(onPrimary), Color(secondary), Color(onSecondary))

/** Accents for the current theme's ground (Void in dark, Paper in light). */
val LocalCountryAccents = staticCompositionLocalOf {
    countryPalette(null, dark = true).toAccents()
}

/** Accents tuned for a dark ground regardless of theme: the masthead and the
 *  detail hero are always dark, so the mark drawn on them uses these. */
val LocalCountryAccentsOnDark = staticCompositionLocalOf {
    countryPalette(null, dark = true).toAccents()
}
