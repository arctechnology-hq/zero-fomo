package com.arctechnology.zerofomo.model

/** Simple lat/lng axis-aligned box; the lingua franca between the island
 *  gazetteer, the world gazetteer, geocoded lookups, and Room range queries. */
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
 * The polymorphic location INPUT: what the user typed, tapped, or where the
 * phone says it is.
 *
 * Island     - Bahamas-specific, resolved offline via the island gazetteer.
 * Place      - any city from the bundled world gazetteer (offline).
 * PostalCode - alphanumeric postal patterns, resolved by a geocoder.
 * AdminArea  - county/state/administrative filtering, geocoder-backed.
 * FreeText   - unclassified input awaiting LocationEngine.classify().
 */
sealed interface LocationQuery {
    data class Island(val island: BahamianIsland) : LocationQuery
    data class Place(val place: com.arctechnology.zerofomo.model.Place) : LocationQuery
    data class PostalCode(val code: String, val countryHint: String? = null) : LocationQuery
    data class AdminArea(
        val name: String,
        val level: AdminLevel = AdminLevel.COUNTY,
        val countryCode: String? = null,
    ) : LocationQuery
    data class FreeText(val raw: String) : LocationQuery
}

/**
 * The unified OUTPUT every resolution strategy funnels into: the few query
 * shapes the database layer needs to understand.
 */
sealed interface LocationFilter {
    val label: String

    /** Exact tag match on the events table: instant, offline (Bahamas). */
    data class IslandTag(val island: BahamianIsland) : LocationFilter {
        override val label: String get() = island.displayName
    }

    /** Everything within [radiusKm] of a point (device location or a city). */
    data class Near(
        val lat: Double,
        val lng: Double,
        val radiusKm: Double,
        override val label: String,
    ) : LocationFilter {
        val box: BoundingBox get() = Geo.boundsAround(lat, lng, radiusKm)
    }

    /** Geospatial range query (postal / admin-area viewport, map bounds later). */
    data class Bounds(val box: BoundingBox, override val label: String) : LocationFilter

    /** Whole-country view. */
    data class CountryTag(val countryCode: String, override val label: String) : LocationFilter

    data object Everywhere : LocationFilter {
        override val label: String get() = "Everywhere"
    }
}
