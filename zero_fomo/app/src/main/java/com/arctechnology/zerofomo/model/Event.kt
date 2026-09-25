package com.arctechnology.zerofomo.model

import androidx.annotation.StringRes
import com.arctechnology.zerofomo.R
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

    /** @StringRes id for the one piece of natural-language text in price
     *  display ("Free"); null when this event has a real price. Migrate
     *  composables off `priceLabel == "Free"` string comparisons (or the
     *  plain [isFree] boolean) to this so "Free" is never hardcoded in UI. */
    @get:StringRes
    val priceFreeRes: Int?
        get() = if (isFree) R.string.price_free else null

    /** Plain numeric amount (no currency symbol, no "Free"/"From" text) for
     *  composables building their own StringRes-based price sentence, e.g.
     *  `stringResource(R.string.price_from_format, event.priceAmountText)`.
     *  Not wired into [priceLabel] itself — that keeps its existing plain
     *  "$X" text unchanged for the composables that already consume it. */
    val priceAmountText: String?
        get() = priceMin?.let { trim(it) }

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v)   // BSD prices: always dot-decimal
}

/** Mirrors the ETL pipeline's category taxonomy. UNKNOWN absorbs any new
 *  slug the feed introduces before the app is updated — never crash on data. */
enum class EventCategory(val label: String, val slug: String, @StringRes val labelRes: Int) {
    CULTURE_HERITAGE("Culture / Heritage", "CULTURE_HERITAGE", R.string.category_culture_heritage),   // was JUNKANOO_CULTURAL (Bahamas-only name)
    REGATTA_MARITIME("Regatta / Maritime", "REGATTA_MARITIME", R.string.category_regatta_maritime),
    FARMERS_CRAFT_MARKET("Farmers / Craft Market", "FARMERS_CRAFT_MARKET", R.string.category_farmers_craft_market),
    FAIR_POPUP("Fair / Popup", "FAIR_POPUP", R.string.category_fair_popup),
    FESTIVAL("Festival", "FESTIVAL", R.string.category_festival),
    CONCERT_LIVE_MUSIC("Concert / Live Music", "CONCERT_LIVE_MUSIC", R.string.category_concert_live_music),
    CLUB_PROMOTION("Club Promotion", "CLUB_PROMOTION", R.string.category_club_promotion),
    NIGHTLIFE_PARTY("Nightlife / Party", "NIGHTLIFE_PARTY", R.string.category_nightlife_party),
    BEACH_PARTY("Beach Party", "BEACH_PARTY", R.string.category_beach_party),
    COMEDY("Comedy", "COMEDY", R.string.category_comedy),
    PAGEANT("Pageant", "PAGEANT", R.string.category_pageant),
    FOOD_DRINK("Food & Drink", "FOOD_DRINK", R.string.category_food_drink),
    SPORTS_FITNESS("Sports & Fitness", "SPORTS_FITNESS", R.string.category_sports_fitness),
    ARTS_THEATRE("Arts & Theatre", "ARTS_THEATRE", R.string.category_arts_theatre),
    CONFERENCE_EXPO("Conference / Expo", "CONFERENCE_EXPO", R.string.category_conference_expo),
    BUSINESS_NETWORKING("Business / Networking", "BUSINESS_NETWORKING", R.string.category_business_networking),
    FAITH_COMMUNITY("Faith & Community", "FAITH_COMMUNITY", R.string.category_faith_community),
    GENERAL("General", "GENERAL", R.string.category_general),
    UNKNOWN("Other", "UNKNOWN", R.string.category_unknown);

    companion object {
        /** Old feed slugs keep resolving so a stale cache or an old feed never degrades to Other. */
        private val ALIASES = mapOf("JUNKANOO_CULTURAL" to CULTURE_HERITAGE)

        fun fromSlug(slug: String?): EventCategory =
            entries.firstOrNull { it.slug.equals(slug, ignoreCase = true) }
                ?: ALIASES[slug?.uppercase()] ?: UNKNOWN
    }
}
