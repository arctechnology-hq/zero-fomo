package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.data.location.AdminAreaResolver
import com.arctechnology.zerofomo.data.location.Gazetteer
import com.arctechnology.zerofomo.data.location.IslandResolver
import com.arctechnology.zerofomo.data.location.LocationEngine
import com.arctechnology.zerofomo.data.location.NoOpGeocoder
import com.arctechnology.zerofomo.data.location.PlaceResolver
import com.arctechnology.zerofomo.data.location.PostalCodeResolver
import com.arctechnology.zerofomo.model.BahamianIsland
import com.arctechnology.zerofomo.model.LocationFilter
import com.arctechnology.zerofomo.model.LocationQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocationEngineTest {

    private val gazetteer = Gazetteer { File("src/main/assets/$it").inputStream() }

    private val engine = LocationEngine(
        IslandResolver(),
        PlaceResolver(),
        PostalCodeResolver(NoOpGeocoder()),
        AdminAreaResolver(NoOpGeocoder()),
        gazetteer,
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

    // ── World gazetteer step ────────────────────────────────────────────────

    @Test fun `world city resolves to a Near filter`() = runBlocking {
        val q = engine.classifyAsync("Kingston, Jamaica")
        assertTrue(q is LocationQuery.Place)
        assertEquals("JM", (q as LocationQuery.Place).place.countryCode)
        val f = engine.resolve(q)
        assertTrue(f is LocationFilter.Near)
        assertEquals("Kingston", (f as LocationFilter.Near).label)
    }

    @Test fun `island input still wins over the world gazetteer`() = runBlocking {
        // "Freeport" exists in the US and the Bahamas; the launch market wins.
        assertTrue(engine.classifyAsync("Freeport") is LocationQuery.Island)
    }

    @Test fun `unknown place with offline geocoder resolves to Everywhere`() = runBlocking {
        val f = engine.resolve(LocationQuery.FreeText("Zzyzx Quadrant"))
        assertEquals(LocationFilter.Everywhere, f)
    }
}
