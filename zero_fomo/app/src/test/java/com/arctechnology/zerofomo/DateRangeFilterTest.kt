package com.arctechnology.zerofomo

import com.arctechnology.zerofomo.model.DateRangeFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DateRangeFilterTest {

    private val wednesday: LocalDate = LocalDate.of(2026, 7, 29)
    private val friday: LocalDate = LocalDate.of(2026, 7, 31)
    private val saturday: LocalDate = LocalDate.of(2026, 8, 1)
    private val sunday: LocalDate = LocalDate.of(2026, 8, 2)

    @Test fun `today is a single-day range`() {
        val r = DateRangeFilter.Today.resolve(wednesday)
        assertEquals(wednesday, r.start)
        assertEquals(wednesday, r.endInclusive)
    }

    @Test fun `weekend from a weekday starts on friday`() {
        val r = DateRangeFilter.ThisWeekend.resolve(wednesday)
        assertEquals(friday, r.start)
        assertEquals(sunday, r.endInclusive)
    }

    @Test fun `weekend from friday starts today`() {
        val r = DateRangeFilter.ThisWeekend.resolve(friday)
        assertEquals(friday, r.start)
        assertEquals(sunday, r.endInclusive)
    }

    @Test fun `weekend from saturday keeps tonight's events`() {
        val r = DateRangeFilter.ThisWeekend.resolve(saturday)
        assertEquals(saturday, r.start)
        assertEquals(sunday, r.endInclusive)
    }

    @Test fun `weekend from sunday is just sunday`() {
        val r = DateRangeFilter.ThisWeekend.resolve(sunday)
        assertEquals(sunday, r.start)
        assertEquals(sunday, r.endInclusive)
    }

    @Test fun `next 7 and 30 days are inclusive forward windows`() {
        assertEquals(wednesday.plusDays(7),
            DateRangeFilter.Next7Days.resolve(wednesday).endInclusive)
        assertEquals(wednesday.plusDays(30),
            DateRangeFilter.Next30Days.resolve(wednesday).endInclusive)
    }

    @Test fun `all upcoming mirrors the feed's two-year horizon`() {
        val r = DateRangeFilter.AllUpcoming.resolve(wednesday)
        assertEquals(wednesday, r.start)
        assertEquals(wednesday.plusYears(2), r.endInclusive)
    }

    @Test fun `custom range passes through`() {
        val r = DateRangeFilter.Custom(friday, sunday).resolve(wednesday)
        assertEquals(friday, r.start)
        assertEquals(sunday, r.endInclusive)
    }
}
