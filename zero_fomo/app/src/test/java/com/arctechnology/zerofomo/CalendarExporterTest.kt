package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.model.Event
import com.arctechnology.zerofomo.model.EventCategory
import com.arctechnology.zerofomo.ui.calendar.CalendarExporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class CalendarExporterTest {

    private fun event(
        timeStart: LocalTime? = LocalTime.of(20, 0),
        timeEnd: LocalTime? = null,
        name: String = "Junkanoo Rush",
        venue: String = "Bay Street",
    ) = Event(
        id = "abc123", name = name,
        date = LocalDate.of(2026, 7, 26),
        timeStart = timeStart, timeEnd = timeEnd, venue = venue,
        island = null, lat = null, lng = null,
        priceMin = null, priceMax = null, isFree = false,
        category = EventCategory.CULTURE_HERITAGE,
        sourceUrl = "https://example.com/tix", description = "Big time",
    )

    @Test fun `timed event renders Nassau time as UTC`() {
        // July = EDT (UTC-4): 20:00 Nassau on the 26th is 00:00Z on the 27th.
        val ics = CalendarExporter.buildIcs(event())
        assertTrue(ics.contains("DTSTART:20260727T000000Z"))
    }

    @Test fun `missing end time defaults to three hours`() {
        val ics = CalendarExporter.buildIcs(event())
        assertTrue(ics.contains("DTEND:20260727T030000Z"))
    }

    @Test fun `end past midnight rolls to the next day`() {
        // 20:00 – 01:00: end is 01:00 Nassau on the 27th = 05:00Z.
        val ics = CalendarExporter.buildIcs(event(timeEnd = LocalTime.of(1, 0)))
        assertTrue(ics.contains("DTEND:20260727T050000Z"))
    }

    @Test fun `untimed event is a true all-day entry`() {
        val ics = CalendarExporter.buildIcs(event(timeStart = null))
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260726"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20260727"))
        assertFalse(ics.contains("DTSTART:2026"))   // no timed variant leaks in
    }

    @Test fun `rfc 5545 text is escaped`() {
        val ics = CalendarExporter.buildIcs(
            event(name = "Fish Fry; rake, scrape", venue = "Arawak Cay, Nassau"))
        assertTrue(ics.contains("SUMMARY:Fish Fry\\; rake\\, scrape"))
        assertTrue(ics.contains("LOCATION:Arawak Cay\\, Nassau"))
    }

    @Test fun `event envelope is well formed`() {
        val ics = CalendarExporter.buildIcs(event())
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("UID:abc123@0fomo.app"))
        assertTrue(ics.trimEnd().endsWith("END:VCALENDAR"))
    }

    @Test fun `content lines are CRLF terminated, never bare LF`() {
        val ics = CalendarExporter.buildIcs(event())
        assertTrue(ics.contains("\r\n"))
        assertFalse(ics.replace("\r\n", "").contains("\n"))
    }

    @Test fun `long lines fold at 75 octets with space continuations`() {
        val longDesc = "Junkanoo rush-out with rake and scrape band, food stalls, " +
            "conch salad on the spot, kids corner, fireworks over the harbour " +
            "and a whole heap of vibes until the early morning hours."
        val ics = CalendarExporter.buildIcs(
            event().copy(description = longDesc))
        val lines = ics.split("\r\n")
        assertTrue(lines.all { it.length <= 75 })
        assertTrue(lines.any { it.startsWith(" ") })   // continuation present
    }
}
