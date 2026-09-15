package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.model.Market
import com.arctechnology.zerofomo.model.MarketSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSelectorTest {

    private fun m(id: String, cc: String, lat: Double, lng: Double, r: Double = 50.0, live: Boolean = true) =
        Market(id, id, cc, "UTC", lat, lng, r, live)

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
}
