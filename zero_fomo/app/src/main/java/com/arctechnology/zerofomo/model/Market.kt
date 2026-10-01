package com.arctechnology.zerofomo.model

/** How much a market has listed — the manifest's `size` (G5). The app picks
 *  its default scope from it: a metro wants a tight radius, an island wants
 *  the whole country. */
enum class MarketSize(val key: String) {
    EMPTY("empty"), SMALL("small"), MEDIUM("medium"), LARGE("large");

    companion object {
        fun fromKey(key: String?): MarketSize? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }

        /** Same thresholds as build_markets_manifest.py, for v1 manifests without `size`. */
        fun fromCount(events: Int): MarketSize = when {
            events <= 0 -> EMPTY
            events < 30 -> SMALL
            events < 200 -> MEDIUM
            else -> LARGE
        }
    }
}

/** A group of countries (Caribbean, Pacific Islands, …) from `geo/regions.json`:
 *  the widest scope that still means "my part of the world". */
data class Region(val id: String, val name: String, val order: Int = 0)

/** A curated city/island cluster with its own feed (docs/GLOBAL_DESIGN.md §4). */
data class Market(
    val id: String,
    val name: String,
    val country: String,
    val tz: String,
    val lat: Double,
    val lng: Double,
    val radiusKm: Double,
    val available: Boolean = true,
    val eventCount: Int = 0,
    val region: String = "",                      // region id, "" when unknown (v1 manifest)
    val size: MarketSize = MarketSize.fromCount(eventCount),
) {
    fun distanceKm(lat: Double, lng: Double): Double = Geo.distanceKm(this.lat, this.lng, lat, lng)

    /** "Castries, Saint Lucia" -> "Castries": the place the market is named after. */
    val cityName: String get() = name.substringBefore(",").trim()

    companion object {
        /** The launch market; also what every legacy (v1) feed row belongs to. */
        const val LAUNCH_ID = "bs-nassau"
    }
}

/**
 * Which market feeds to sync for a user looking from ([lat], [lng]).
 * Pure so it is unit-testable; the repository applies it.
 *
 * Two passes. The radius pass is unchanged from G2: the nearest markets whose
 * reach covers the user. The density pass (G5) is what makes a small island
 * usable: while the chosen markets list fewer than [MIN_EVENTS] events in
 * total, the nearest live markets in the same region are added — up to
 * [MAX_TOTAL] feeds and [WIDEN_REACH_KM] away — so a Montserrat user also
 * sees Antigua and Saint Kitts instead of three events and an empty screen.
 * A manifest without event counts (all zero) never widens.
 */
object MarketSelector {
    const val MAX_MARKETS = 3
    const val REACH_KM = 250.0        // beyond a market's own radius
    const val MIN_EVENTS = 40         // density floor before widening stops
    const val MAX_TOTAL = 6           // hard cap on synced feeds
    const val WIDEN_REACH_KM = 1500.0 // never pull a feed from another ocean

    fun select(markets: List<Market>, lat: Double?, lng: Double?): List<Market> {
        val live = markets.filter { it.available }
        if (live.isEmpty()) return emptyList()
        if (lat == null || lng == null) {
            return live.filter { it.id == Market.LAUNCH_ID }.ifEmpty { live.take(1) }
        }
        val byDistance = live.sortedBy { it.distanceKm(lat, lng) }
        val within = byDistance.filter { it.distanceKm(lat, lng) <= it.radiusKm + REACH_KM }
        val primary = within.take(MAX_MARKETS).ifEmpty { byDistance.take(1) }
        return widen(primary, byDistance, lat, lng)
    }

    private fun widen(primary: List<Market>, byDistance: List<Market>, lat: Double, lng: Double): List<Market> {
        if (byDistance.none { it.eventCount > 0 }) return primary   // v1 manifest: counts unknown
        val result = primary.toMutableList()
        var total = result.sumOf { it.eventCount }
        if (total >= MIN_EVENTS) return result
        val region = result.firstOrNull()?.region.orEmpty()
        for (m in byDistance) {
            if (total >= MIN_EVENTS || result.size >= MAX_TOTAL) break
            if (m in result) continue
            if (m.distanceKm(lat, lng) > WIDEN_REACH_KM) break
            if (region.isNotEmpty() && m.region.isNotEmpty() && m.region != region) continue
            result += m
            total += m.eventCount
        }
        return result
    }
}
