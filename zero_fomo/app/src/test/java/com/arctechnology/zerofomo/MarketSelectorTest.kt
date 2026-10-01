package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.model.Market
import com.arctechnology.zerofomo.model.MarketSelector
import com.arctechnology.zerofomo.model.MarketSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSelectorTest {

    private fun m(id: String, cc: String, lat: Double, lng: Double, r: Double = 50.0, live: Boolean = true,
                  events: Int = 0, region: String = "") =
        Market(id, id, cc, "UTC", lat, lng, r, live, events, region)

    private val markets = listOf(
        m("bs-nassau", "BS", 25.06, -77.345, 40.0),
        m("bs-freeport", "BS", 26.533, -78.7, 40.0),
        m("us-miami", "US", 25.774, -80.194),
        m("us-fort-lauderdale", "US", 26.122, -80.137, 35.0),
        m("us-orlando", "US", 28.538, -81.379),
        m("us-atlanta", "US", 33.749, -84.388),
        m("jm-kingston", "JM", 17.997, -76.794, 40.0),
        m("eu-nowhere", "GB", 51.5, -0.12, 50.0, live = false),
    )

    @Test fun `no location falls back to the launch market`() {
        assertEquals(listOf("bs-nassau"), MarketSelector.select(markets, null, null).map { it.id })
    }

    @Test fun `nassau user gets nassau, freeport and miami, nearest first`() {
        val ids = MarketSelector.select(markets, 25.06, -77.35).map { it.id }
        assertEquals("bs-nassau", ids.first())
        assertTrue(ids.containsAll(listOf("bs-freeport", "us-miami")))
        assertEquals(3, ids.size)
    }

    @Test fun `south florida user never syncs more than three markets`() {
        val ids = MarketSelector.select(markets, 26.0, -80.2).map { it.id }
        assertEquals(3, ids.size)
        assertEquals("us-fort-lauderdale", ids.first())
        assertTrue("us-miami" in ids)
    }

    @Test fun `kingston user gets kingston only`() {
        assertEquals(listOf("jm-kingston"), MarketSelector.select(markets, 18.0, -76.8).map { it.id })
    }

    @Test fun `far away user gets the single nearest market, never nothing`() {
        // Lisbon: nothing within reach, so the closest live market.
        val ids = MarketSelector.select(markets, 38.72, -9.14).map { it.id }
        assertEquals(1, ids.size)
    }

    @Test fun `unavailable markets are ignored`() {
        val ids = MarketSelector.select(markets, 51.5, -0.12).map { it.id }
        assertTrue("eu-nowhere" !in ids)
    }

    // ── G5 density pass ───────────────────────────────────────────────────────

    private val eastern = listOf(
        m("ms-brades", "MS", 16.79, -62.21, 20.0, events = 3, region = "caribbean"),
        m("ag-st-johns", "AG", 17.118, -61.845, 35.0, events = 7, region = "caribbean"),
        m("kn-basseterre", "KN", 17.3, -62.72, 25.0, events = 4, region = "caribbean"),
        m("gp-pointe-a-pitre", "GP", 16.24, -61.53, 30.0, events = 12, region = "caribbean"),
        m("dm-roseau", "DM", 15.3, -61.39, 25.0, events = 15, region = "caribbean"),
        m("lc-castries", "LC", 14.01, -60.99, 30.0, events = 3, region = "caribbean"),
        m("bb-bridgetown", "BB", 13.097, -59.617, 30.0, events = 14, region = "caribbean"),
        m("pr-san-juan", "PR", 18.466, -66.106, 40.0, events = 86, region = "caribbean"),
        m("us-miami", "US", 25.774, -80.194, 50.0, events = 1894, region = "north-america"),
        m("sn-dakar", "SN", 14.69, -17.44, 40.0, events = 50, region = "west-africa"),
    )

    @Test fun `a small island keeps adding neighbours until the density floor`() {
        val picked = MarketSelector.select(eastern, 16.79, -62.21)
        assertEquals("ms-brades", picked.first().id)
        assertTrue(picked.sumOf { it.eventCount } >= MarketSelector.MIN_EVENTS)
        assertTrue(picked.size <= MarketSelector.MAX_TOTAL)
        assertTrue(picked.none { it.region != "caribbean" })      // Miami and Dakar never
    }

    @Test fun `widening stays inside the region even when another region is nearer`() {
        // Barbados: Dakar is across the Atlantic (~4,500 km) and Miami is in
        // another region; neither may be pulled in, Caribbean neighbours may.
        val picked = MarketSelector.select(eastern, 13.1, -59.6)
        assertTrue(picked.none { it.id == "sn-dakar" || it.id == "us-miami" })
        assertTrue(picked.size > 1)
    }

    @Test fun `a dense market never widens`() {
        val picked = MarketSelector.select(eastern, 18.47, -66.1)
        assertEquals(listOf("pr-san-juan"), picked.map { it.id })
    }

    @Test fun `a v1 manifest without counts never widens`() {
        val picked = MarketSelector.select(markets, 18.0, -76.8)
        assertEquals(1, picked.size)
    }

    @Test fun `size falls back to the count thresholds`() {
        assertEquals(MarketSize.EMPTY, Market("x", "x", "XX", "UTC", 0.0, 0.0, 1.0, eventCount = 0).size)
        assertEquals(MarketSize.SMALL, MarketSize.fromCount(29))
        assertEquals(MarketSize.MEDIUM, MarketSize.fromCount(30))
        assertEquals(MarketSize.LARGE, MarketSize.fromCount(200))
        assertEquals(MarketSize.LARGE, MarketSize.fromKey("LARGE"))
        assertEquals(null, MarketSize.fromKey("huge"))
    }
}
