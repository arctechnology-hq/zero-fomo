package com.arctechnology.zerofomo.model

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
) {
    fun distanceKm(lat: Double, lng: Double): Double = Geo.distanceKm(this.lat, this.lng, lat, lng)

    companion object {
        /** The launch market; also what every legacy (v1) feed row belongs to. */
        const val LAUNCH_ID = "bs-nassau"
    }
}

/**
 * Which market feeds to sync for a user looking from ([lat], [lng]).
 * Pure so it is unit-testable; the repository applies it.
 */
object MarketSelector {
    const val MAX_MARKETS = 3
    const val REACH_KM = 250.0   // beyond a market's own radius

    fun select(markets: List<Market>, lat: Double?, lng: Double?): List<Market> {
        val live = markets.filter { it.available }
        if (live.isEmpty()) return emptyList()
        if (lat == null || lng == null) {
            return live.filter { it.id == Market.LAUNCH_ID }.ifEmpty { live.take(1) }
        }
        val byDistance = live.sortedBy { it.distanceKm(lat, lng) }
        val within = byDistance.filter { it.distanceKm(lat, lng) <= it.radiusKm + REACH_KM }
        return within.take(MAX_MARKETS).ifEmpty { byDistance.take(1) }
    }
}
