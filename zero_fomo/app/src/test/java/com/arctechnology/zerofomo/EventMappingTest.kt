package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.data.network.EventDto
import com.arctechnology.zerofomo.data.network.toEntity
import com.arctechnology.zerofomo.model.BahamianIsland
import com.arctechnology.zerofomo.model.Event
import com.arctechnology.zerofomo.model.EventCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class EventMappingTest {

    private fun dto(
        date: String = "2026-07-26",
        timeStart: String? = "20:00",
        timeEnd: String? = "01:00",
        island: String? = "NEW_PROVIDENCE",
    ) = EventDto(
        id = "abc123", name = "Tet Kole", date = date,
        timeStart = timeStart, timeEnd = timeEnd,
        venue = "Baseball Stadium", island = island,
        priceMin = 30.0, priceMax = 50.0,
        category = "CONCERT_LIVE_MUSIC",
        sourceUrl = "https://example.com", description = "desc",
    )

    @Test fun `valid row maps fully`() {
        val e = dto().toEntity()
        assertNotNull(e)
        assertEquals(LocalDate.of(2026, 7, 26).toEpochDay(), e!!.epochDay)
        assertEquals("20:00", e.timeStart)
        assertEquals("NEW_PROVIDENCE", e.islandTag)
    }

    @Test fun `unparseable date drops the row`() {
        assertNull(dto(date = "July 26th").toEntity())
        assertNull(dto(date = "").toEntity())
    }

    @Test fun `unparseable time degrades to null but keeps the row`() {
        val e = dto(timeStart = "8 PM", timeEnd = "late").toEntity()
        assertNotNull(e)
        assertNull(e!!.timeStart)
        assertNull(e.timeEnd)
    }

    @Test fun `lowercase island tag still resolves to the enum`() {
        val e = dto(island = "new_providence").toEntity()
        assertEquals("NEW_PROVIDENCE", e!!.islandTag)
        assertEquals(BahamianIsland.NEW_PROVIDENCE,
            BahamianIsland.fromTag(e.islandTag))
    }

    // ── priceLabel formatting ───────────────────────────────────────────────

    private fun event(
        priceMin: Double? = null, priceMax: Double? = null, isFree: Boolean = false,
    ) = Event(
        id = "x", name = "n", date = LocalDate.of(2026, 7, 26),
        timeStart = LocalTime.of(20, 0), timeEnd = null, venue = "v",
        island = null, lat = null, lng = null,
        priceMin = priceMin, priceMax = priceMax, isFree = isFree,
        category = EventCategory.GENERAL, sourceUrl = "", description = "",
    )

    @Test fun `free events label as Free`() =
        assertEquals("Free", event(isFree = true).priceLabel)

    @Test fun `whole-dollar prices drop the decimals`() =
        assertEquals("$30", event(priceMin = 30.0).priceLabel)

    @Test fun `price ranges render both ends`() =
        assertEquals("$30 – $50", event(priceMin = 30.0, priceMax = 50.0).priceLabel)

    @Test fun `cents always render dot-decimal`() =
        assertEquals("$27.50", event(priceMin = 27.5).priceLabel)

    @Test fun `no price data means blank label`() =
        assertEquals("", event().priceLabel)

    // ── category slugs (must mirror the ETL pipeline's taxonomy) ────────────

    @Test fun `pipeline slugs resolve to their categories`() {
        assertEquals(EventCategory.CONFERENCE_EXPO,
            EventCategory.fromSlug("CONFERENCE_EXPO"))
        assertEquals(EventCategory.FAIR_POPUP,
            EventCategory.fromSlug("FAIR_POPUP"))
        assertEquals(EventCategory.FARMERS_CRAFT_MARKET,
            EventCategory.fromSlug("farmers_craft_market"))
    }

    @Test fun `unknown slug degrades to UNKNOWN not a crash`() =
        assertEquals(EventCategory.UNKNOWN, EventCategory.fromSlug("HOT_AIR_BALLOON"))
}
