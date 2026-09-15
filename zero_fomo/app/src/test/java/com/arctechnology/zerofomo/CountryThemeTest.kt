package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.data.location.Gazetteer
import com.arctechnology.zerofomo.model.Country
import com.arctechnology.zerofomo.ui.theme.Brand
import com.arctechnology.zerofomo.ui.theme.ContrastMath
import com.arctechnology.zerofomo.ui.theme.countryPalette
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CountryThemeTest {

    @Test fun `wcag contrast is the standard formula`() {
        assertEquals(21.0, ContrastMath.contrast(0xFFFFFFFFL, 0xFF000000L), 0.01)
        assertEquals(1.0, ContrastMath.contrast(0xFF808080L, 0xFF808080L), 0.001)
        // docs/BRAND.md quotes ~15.9 and ~17.2 for these pairs (rounded tool output).
        assertEquals(16.3, ContrastMath.contrast(Brand.VOLT, Brand.VOID), 0.5)
        assertEquals(17.2, ContrastMath.contrast(Brand.PAPER, Brand.VOID), 0.5)
    }

    @Test fun `hsl round-trips`() {
        val c = 0xFF00778BL
        val hsl = ContrastMath.toHsl(c)
        val back = ContrastMath.fromHsl(hsl[0], hsl[1], hsl[2])
        assertEquals(c, back)
    }

    @Test fun `bahamas keeps its flag colours on the dark ground`() {
        val p = countryPalette(Country.BAHAMAS, dark = true)
        // Aqua and gold already clear 3:1 on Void: untouched.
        assertEquals(0xFFFFC72CL, p.armB)
        assertTrue(ContrastMath.contrast(p.armA, Brand.VOID) >= 3.0)
        assertEquals(Brand.VOID, p.onSecondary)
    }

    @Test fun `a dark flag colour is lightened for the dark ground and kept for light`() {
        // Trinidad & Tobago: red + black. Black cannot be an arm on Void.
        val tt = Country("TT", "Trinidad and Tobago", "", "NA", "TTD", 0xFFCE1126, 0xFF000000)
        val dark = countryPalette(tt, dark = true)
        assertTrue(ContrastMath.contrast(dark.armB, Brand.VOID) >= 3.0)
        assertFalse(ContrastMath.tooSimilar(dark.armA, dark.armB))
        val light = countryPalette(tt, dark = false)
        assertEquals(0xFF000000L, light.armB)   // black arm on Paper is fine
    }

    @Test fun `null country is the plain brand palette`() {
        val p = countryPalette(null, dark = true)
        assertEquals(Brand.VOLT, p.armA)
        assertEquals(Brand.AQUA, p.armB)
    }

    @Test fun `every bundled country clears the contrast floors on both grounds`() = runBlocking {
        val gazetteer = Gazetteer { File("src/main/assets/$it").inputStream() }
        gazetteer.countries().forEach { c ->
            listOf(true, false).forEach { dark ->
                val ground = if (dark) Brand.VOID else Brand.PAPER
                val p = countryPalette(c, dark)
                fun check(name: String, v: Long, min: Double) = assertTrue(
                    "${c.code} $name ${"%06X".format(v and 0xFFFFFF)} on ${if (dark) "Void" else "Paper"} " +
                        "= ${"%.2f".format(ContrastMath.contrast(v, ground))} < $min",
                    ContrastMath.contrast(v, ground) >= min)
                check("armA", p.armA, 3.0)
                check("armB", p.armB, 3.0)
                check("primary", p.primary, 4.5)
                check("secondary", p.secondary, 4.5)
                assertTrue("${c.code} onPrimary", ContrastMath.contrast(p.onPrimary, p.primary) >= 4.5)
                assertFalse("${c.code} arms identical", ContrastMath.tooSimilar(p.armA, p.armB))
            }
        }
    }
}
