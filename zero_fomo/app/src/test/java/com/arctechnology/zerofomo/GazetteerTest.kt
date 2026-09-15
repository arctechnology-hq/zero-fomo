package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.data.location.Gazetteer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs against the real bundled assets, so a broken data build fails here
 *  before it ships. */
class GazetteerTest {

    private val gazetteer = Gazetteer { File("src/main/assets/$it").inputStream() }

    @Test fun `every ISO country is present with two flag colours`() = runBlocking {
        val countries = gazetteer.countries()
        assertEquals(252, countries.size)
        countries.forEach {
            assertEquals(2, it.code.length)
            assertTrue(it.name, it.colorA and 0xFF000000L != 0L)
            assertTrue(it.name, it.colorB and 0xFF000000L != 0L)
        }
        assertEquals("Bahamas", gazetteer.country("bs")?.name)
        assertEquals("🇧🇸", gazetteer.country("BS")?.flagEmoji)
    }

    @Test fun `nearest place to a coarse Nassau fix is Nassau`() = runBlocking {
        val p = gazetteer.nearest(25.06, -77.35)
        assertEquals("Nassau", p?.name)
        assertEquals("BS", p?.countryCode)
    }

    @Test fun `nearest place to downtown Miami is Miami`() = runBlocking {
        assertEquals("Miami", gazetteer.nearest(25.7743, -80.1937)?.name)
    }

    @Test fun `prefix search ranks exact, then capitals, then population`() = runBlocking {
        assertEquals("Kingston", gazetteer.search("kingston").first().name)
        assertEquals("JM", gazetteer.search("kingston").first().countryCode)
        assertEquals("Nassau", gazetteer.search("nas").first().name)   // capital beats Nashik
    }

    @Test fun `the user's own country outranks bigger namesakes`() = runBlocking {
        // Five Hamiltons: the capital (Bermuda) wins by default, the user's
        // own country wins when known.
        assertEquals("BM", gazetteer.search("hamilton").first().countryCode)
        assertEquals("CA", gazetteer.search("hamilton", preferCountry = "CA").first().countryCode)
        assertEquals("NZ", gazetteer.search("hamilton", preferCountry = "NZ").first().countryCode)
    }

    @Test fun `country aliases work as hints`() = runBlocking {
        assertEquals("GB", gazetteer.search("london uk").first().countryCode)
        assertEquals("US", gazetteer.search("miami usa").first().countryCode)
    }

    @Test fun `country hint narrows the search`() = runBlocking {
        val r = gazetteer.search("georgetown, guyana")
        assertTrue(r.isNotEmpty())
        assertEquals("GY", r.first().countryCode)
        assertEquals("KY", gazetteer.search("george town ky").first().countryCode)
    }

    @Test fun `multi-word cities match as a whole`() = runBlocking {
        assertEquals("TT", gazetteer.search("port of spain").first().countryCode)
        assertEquals("US", gazetteer.search("new york").first().countryCode)
    }

    @Test fun `capital anchors every country that has places`() = runBlocking {
        assertEquals("Nassau", gazetteer.anchorOf("BS")?.name)
        assertEquals("Kingston", gazetteer.anchorOf("JM")?.name)
        assertNotNull(gazetteer.anchorOf("US"))
    }

    @Test fun `short queries return nothing instead of everything`() = runBlocking {
        assertTrue(gazetteer.search("n").isEmpty())
        assertTrue(gazetteer.search("  ").isEmpty())
    }
}
