package com.arctechnology.zerofomo.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG contrast + HSL nudging on plain ARGB longs. Pure Kotlin so the
 * country-colour rules are unit-testable without Compose on the classpath.
 */
object ContrastMath {

    private fun channel(argb: Long, shift: Int): Double = ((argb shr shift) and 0xFF).toDouble() / 255.0

    private fun linear(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    /** WCAG relative luminance, 0 (black) .. 1 (white). */
    fun luminance(argb: Long): Double =
        0.2126 * linear(channel(argb, 16)) +
            0.7152 * linear(channel(argb, 8)) +
            0.0722 * linear(channel(argb, 0))

    /** WCAG contrast ratio, 1 .. 21. */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** HSL as (h in 0..360, s in 0..1, l in 0..1). */
    fun toHsl(argb: Long): DoubleArray {
        val r = channel(argb, 16); val g = channel(argb, 8); val b = channel(argb, 0)
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6 else 0)) * 60
            g -> ((b - r) / d + 2) * 60
            else -> ((r - g) / d + 4) * 60
        }
        return doubleArrayOf(h, s, l)
    }

    fun fromHsl(h: Double, s: Double, l: Double): Long {
        fun hue(p: Double, q: Double, tIn: Double): Double {
            var t = tIn
            if (t < 0) t += 1.0
            if (t > 1) t -= 1.0
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        val r: Double; val g: Double; val b: Double
        if (s == 0.0) { r = l; g = l; b = l } else {
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            val hk = (h % 360 + 360) % 360 / 360
            r = hue(p, q, hk + 1.0 / 3); g = hue(p, q, hk); b = hue(p, q, hk - 1.0 / 3)
        }
        fun c(v: Double): Long = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toLong()
        return 0xFF000000L or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    fun saturation(argb: Long): Double = toHsl(argb)[1]

    /**
     * Nudge lightness in 3 % steps until [color] reaches [min] contrast
     * against [ground]. [lighten] picks the direction (true on dark ground).
     * Returns the first passing colour, or the last attempt if the hue can
     * never get there (pure grey on mid-grey), so callers always get a colour.
     */
    fun ensureContrast(color: Long, ground: Long, min: Double, lighten: Boolean): Long {
        if (contrast(color, ground) >= min) return color
        val hsl = toHsl(color)
        var l = hsl[2]
        var out = color
        repeat(32) {
            l = if (lighten) l + 0.03 else l - 0.03
            if (l < 0.0 || l > 1.0) return out
            out = fromHsl(hsl[0], hsl[1], l)
            if (contrast(out, ground) >= min) return out
        }
        return out
    }

    /** True when two colours are visually near-identical (used to keep the
     *  two arms of the mark distinguishable). */
    fun tooSimilar(a: Long, b: Long): Boolean {
        val ha = toHsl(a); val hb = toHsl(b)
        val dh = abs(ha[0] - hb[0]).let { if (it > 180) 360 - it else it }
        return contrast(a, b) < 1.25 && dh < 25
    }
}
