package com.arctechnology.zerofomo.model

/** Simple lat/lng axis-aligned box; the lingua franca between the island
 *  gazetteer, future geocoded lookups, and Room range queries. */
data class BoundingBox(
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double,
) {
    fun intersects(other: BoundingBox): Boolean =
        minLat <= other.maxLat && maxLat >= other.minLat &&
            minLng <= other.maxLng && maxLng >= other.minLng
}

enum class AdminLevel { STATE_PROVINCE, COUNTY, CITY }

/**
 * The polymorphic location INPUT — what the user typed or tapped.
 *
 * Type A (Island)     — Bahamas-specific, resolved offline via the gazetteer.
 * Type B (PostalCode) — alphanumeric postal patterns, resolved by a geocoder.
 * Type C (AdminArea)  — county/state/administrative filtering, geocoder-backed.
 * FreeText            — unclassified input awaiting LocationEngine.classify().
 */
sealed interface LocationQuery {
    data class Island(val island: BahamianIsland) : LocationQuery
    data class PostalCode(val code: String, val countryHint: String? = null) : LocationQuery
    data class AdminArea(
        val name: String,
        val level: AdminLevel = AdminLevel.COUNTY,
        val countryCode: String? = null,
    ) : LocationQuery
    data class FreeText(val raw: String) : LocationQuery
}

/**
 * The unified OUTPUT every resolution strategy funnels into — the only two
 * query shapes the database layer ever needs to understand, plus Everywhere.
 */
sealed interface LocationFilter {
    /** Exact tag match on the events table — instant, offline (Type A). */
    data class IslandTag(val island: BahamianIsland) : LocationFilter

    /** Geospatial range query (Types B/C, or a map viewport later). */
    data class Bounds(val box: BoundingBox, override val label: String) : LocationFilter

    data object Everywhere : LocationFilter

    val label: String
        get() = when (this) {
            is IslandTag -> island.displayName
            is Bounds -> label
            Everywhere -> "All locations"
        }
}
