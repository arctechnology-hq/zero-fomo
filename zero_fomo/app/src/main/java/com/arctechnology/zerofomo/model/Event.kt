package com.arctechnology.zerofomo.model

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Canonical domain event — the app's single vocabulary for an event,
 *  mapped 1:1 from the ETL pipeline's standardized record. */
data class Event(
    val id: String,
    val name: String,
    val date: LocalDate,
    val timeStart: LocalTime?,
    val timeEnd: LocalTime?,
    val venue: String,
    val island: BahamianIsland?,
    val lat: Double?,
    val lng: Double?,
    val priceMin: Double?,
    val priceMax: Double?,
    val isFree: Boolean,
    val category: EventCategory,
    val sourceUrl: String,
    val description: String,
    val countryCode: String = "BS",
    val isSaved: Boolean = false,
) {
    /** Localised country name from the ISO code, no gazetteer needed. */
    val countryName: String
        get() = Locale("", countryCode).displayCountry.ifBlank { countryCode }

    val priceLabel: String
        get() = when {
            isFree -> "Free"
            priceMin == null -> ""
            priceMax != null && priceMax > priceMin -> "$${trim(priceMin)} – $${trim(priceMax)}"
            else -> "$${trim(priceMin)}"
        }

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v)   // BSD prices: always dot-decimal
}

/** Mirrors the ETL pipeline's category taxonomy. UNKNOWN absorbs any new
 *  slug the feed introduces before the app is updated — never crash on data. */
enum class EventCategory(val label: String, val slug: String) {
    JUNKANOO_CULTURAL("Junkanoo / Cultural", "JUNKANOO_CULTURAL"),
    REGATTA_MARITIME("Regatta / Maritime", "REGATTA_MARITIME"),
    FARMERS_CRAFT_MARKET("Farmers / Craft Market", "FARMERS_CRAFT_MARKET"),
    FAIR_POPUP("Fair / Popup", "FAIR_POPUP"),
    FESTIVAL("Festival", "FESTIVAL"),
    CONCERT_LIVE_MUSIC("Concert / Live Music", "CONCERT_LIVE_MUSIC"),
    CLUB_PROMOTION("Club Promotion", "CLUB_PROMOTION"),
    NIGHTLIFE_PARTY("Nightlife / Party", "NIGHTLIFE_PARTY"),
    BEACH_PARTY("Beach Party", "BEACH_PARTY"),
    COMEDY("Comedy", "COMEDY"),
    PAGEANT("Pageant", "PAGEANT"),
    FOOD_DRINK("Food & Drink", "FOOD_DRINK"),
    SPORTS_FITNESS("Sports & Fitness", "SPORTS_FITNESS"),
    ARTS_THEATRE("Arts & Theatre", "ARTS_THEATRE"),
    CONFERENCE_EXPO("Conference / Expo", "CONFERENCE_EXPO"),
    BUSINESS_NETWORKING("Business / Networking", "BUSINESS_NETWORKING"),
    FAITH_COMMUNITY("Faith & Community", "FAITH_COMMUNITY"),
    GENERAL("General", "GENERAL"),
    UNKNOWN("Other", "UNKNOWN");

    companion object {
        fun fromSlug(slug: String?): EventCategory =
            entries.firstOrNull { it.slug.equals(slug, ignoreCase = true) } ?: UNKNOWN
    }
}
