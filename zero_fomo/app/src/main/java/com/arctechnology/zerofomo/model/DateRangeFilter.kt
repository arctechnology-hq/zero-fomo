package com.arctechnology.zerofomo.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Date filtering vocabulary: quick presets + custom picker ranges, each
 *  collapsing to an inclusive LocalDate range for the Room query. */
sealed interface DateRangeFilter {
    data object AllUpcoming : DateRangeFilter
    data object Today : DateRangeFilter
    data object ThisWeekend : DateRangeFilter
    data object Next7Days : DateRangeFilter
    data object Next30Days : DateRangeFilter
    data class Custom(val start: LocalDate, val end: LocalDate) : DateRangeFilter

    val label: String
        get() = when (this) {
            AllUpcoming -> "All upcoming"
            Today -> "Today"
            ThisWeekend -> "This weekend"
            Next7Days -> "Next 7 days"
            Next30Days -> "Next 30 days"
            is Custom -> "$start → $end"
        }

    fun resolve(today: LocalDate = LocalDate.now()): ClosedRange<LocalDate> = when (this) {
        AllUpcoming -> today..today.plusYears(2)   // mirrors the feed's horizon
        Today -> today..today
        ThisWeekend -> {
            // Upcoming Fri–Sun; if we're already inside the weekend, it
            // starts today so tonight's events still show.
            val friday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
            val start = if (today.dayOfWeek in setOf(
                    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) today else friday
            val sunday = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            start..sunday
        }
        Next7Days -> today..today.plusDays(7)
        Next30Days -> today..today.plusDays(30)
        is Custom -> start..end
    }

    companion object {
        val presets = listOf(AllUpcoming, Today, ThisWeekend, Next7Days, Next30Days)
    }
}
