package com.arctechnology.zerofomo.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A populated place from the bundled GeoNames extract (cities >= 50k plus
 *  every capital, plus the five largest towns of any country below that). */
data class Place(
    val name: String,
    val countryCode: String,
    val lat: Double,
    val lng: Double,
    val population: Int,
    val timezone: String,
    val isCapital: Boolean = false,
    val asciiName: String = name,
) {
    fun distanceKm(lat: Double, lng: Double): Double = Geo.distanceKm(this.lat, this.lng, lat, lng)
}

object Geo {
    private const val EARTH_RADIUS_KM = 6371.0088

    /** Great-circle distance (haversine). */
    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }

    /** Axis-aligned box that fully contains the circle; cheap Room pre-filter. */
    fun boundsAround(lat: Double, lng: Double, radiusKm: Double): BoundingBox {
        val dLat = radiusKm / 111.32
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val dLng = radiusKm / (111.32 * cosLat)
        return BoundingBox(
            minLat = (lat - dLat).coerceAtLeast(-90.0),
            minLng = (lng - dLng).coerceAtLeast(-180.0),
            maxLat = (lat + dLat).coerceAtMost(90.0),
            maxLng = (lng + dLng).coerceAtMost(180.0),
        )
    }
}
