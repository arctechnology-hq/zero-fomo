package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.data.location.Gazetteer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled geo/regions.json (G5): every country maps to a region and the
 *  Caribbean grouping the launch markets rely on is intact. */
class RegionsTest {

    private val gazetteer = Gazetteer { File("src/main/assets/$it").inputStream() }

    @Test fun `every bundled country belongs to a region`() = runBlocking {
        val regions = gazetteer.regions()
        assertTrue(regions.size >= 20)
        assertEquals("caribbean", regions.first().id)          // UI order: home first
        gazetteer.countries().forEach { c ->
            val r = gazetteer.regionOf(c.code)
            assertTrue("${c.code} ${c.name} has no region", r != null)
        }
    }

    @Test fun `caribbean groups the launch and rollout markets`() = runBlocking {
        val codes = gazetteer.countriesIn("caribbean")
        assertTrue(codes.containsAll(listOf("BS", "JM", "TT", "BB", "KY", "LC", "MS", "PR", "DO", "GY", "BZ")))
        assertTrue("US" !in codes)
        assertEquals("Caribbean", gazetteer.regionOf("bs")?.name)
        assertEquals("north-america", gazetteer.regionOf("US")?.id)
    }
}
