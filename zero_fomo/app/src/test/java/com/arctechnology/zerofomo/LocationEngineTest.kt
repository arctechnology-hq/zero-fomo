package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.data.location.AdminAreaResolver
import com.arctechnology.zerofomo.data.location.IslandResolver
import com.arctechnology.zerofomo.data.location.LocationEngine
import com.arctechnology.zerofomo.data.location.NoOpGeocoder
import com.arctechnology.zerofomo.data.location.PostalCodeResolver
import com.arctechnology.zerofomo.model.BahamianIsland
import com.arctechnology.zerofomo.model.LocationQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationEngineTest {

    private val engine = LocationEngine(
        IslandResolver(),
        PostalCodeResolver(NoOpGeocoder()),
        AdminAreaResolver(NoOpGeocoder()),
    )

    @Test fun `nassau classifies as New Providence island`() {
        val q = engine.classify("Nassau")
        assertTrue(q is LocationQuery.Island)
        assertEquals(BahamianIsland.NEW_PROVIDENCE, (q as LocationQuery.Island).island)
    }

    @Test fun `alias containment resolves venue-style strings`() {
        val q = engine.classify("Arawak Cay Fish Fry, Nassau")
        assertTrue(q is LocationQuery.Island)
        assertEquals(BahamianIsland.NEW_PROVIDENCE, (q as LocationQuery.Island).island)
    }

    @Test fun `freeport classifies as Grand Bahama`() {
        val q = engine.classify("Freeport")
        assertEquals(BahamianIsland.GRAND_BAHAMA, (q as LocationQuery.Island).island)
    }

    @Test fun `us zip classifies as postal code`() {
        val q = engine.classify("33101")
        assertTrue(q is LocationQuery.PostalCode)
        assertEquals("US", (q as LocationQuery.PostalCode).countryHint)
    }

    @Test fun `zip plus four classifies as postal code`() {
        assertTrue(engine.classify("33101-4321") is LocationQuery.PostalCode)
    }

    @Test fun `canadian postal classifies with CA hint`() {
        val q = engine.classify("M5V 2T6")
        assertEquals("CA", (q as LocationQuery.PostalCode).countryHint)
    }

    @Test fun `unknown text falls through to admin area`() {
        assertTrue(engine.classify("Miami-Dade County") is LocationQuery.AdminArea)
    }

    @Test fun `island names never shadowed by postal patterns`() {
        // Every island display name must classify as Type A, not fall through.
        BahamianIsland.entries.forEach { island ->
            val q = engine.classify(island.displayName)
            assertTrue("${island.displayName} should classify as Island",
                q is LocationQuery.Island)
        }
    }
}
