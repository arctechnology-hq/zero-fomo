package com.arctechnology.zerofomo.data.location

import com.arctechnology.zerofomo.model.AdminLevel
import com.arctechnology.zerofomo.model.BoundingBox
import com.arctechnology.zerofomo.model.Geo
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import javax.inject.Inject

/** komoot's Photon (OpenStreetMap) geocoder: no key, fair-use rate limits.
 *  Only reached for postal codes and admin areas the offline gazetteer cannot
 *  answer; failures degrade to "no filter", never a crash. Self-host
 *  (`photon.komoot.io` -> `geo.0fomo.app`) once traffic justifies it. */
interface PhotonApi {
    @GET("api/")
    suspend fun search(
        @Query("q") q: String,
        @Query("limit") limit: Int = 1,
        @Query("lang") lang: String = "en",
    ): PhotonResponse
}

@Serializable
data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
data class PhotonFeature(
    val geometry: PhotonGeometry? = null,
    val properties: PhotonProperties = PhotonProperties(),
)

@Serializable
data class PhotonGeometry(val coordinates: List<Double> = emptyList())   // [lng, lat]

@Serializable
data class PhotonProperties(
    val name: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val countrycode: String? = null,
    val postcode: String? = null,
    val extent: List<Double>? = null,   // [minLng, maxLat, maxLng, minLat]
)

class PhotonGeocoder @Inject constructor(private val api: PhotonApi) : GeocodingService {

    override suspend fun geocodePostal(code: String, countryHint: String?): GeocodeResult? =
        lookup(listOfNotNull(code, countryHint).joinToString(" "), fallbackRadiusKm = 15.0)

    override suspend fun geocodeAdminArea(
        name: String, level: AdminLevel, countryCode: String?,
    ): GeocodeResult? =
        lookup(listOfNotNull(name, countryCode).joinToString(" "), fallbackRadiusKm = 40.0)

    private suspend fun lookup(q: String, fallbackRadiusKm: Double): GeocodeResult? {
        val feature = try {
            api.search(q).features.firstOrNull()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        val coords = feature.geometry?.coordinates ?: return null
        if (coords.size < 2) return null
        val p = feature.properties
        val box = p.extent?.takeIf { it.size == 4 }?.let {
            BoundingBox(minLat = it[3], minLng = it[0], maxLat = it[1], maxLng = it[2])
        } ?: Geo.boundsAround(coords[1], coords[0], fallbackRadiusKm)
        val label = listOfNotNull(p.name ?: p.city ?: p.postcode, p.state, p.country)
            .distinct().joinToString(", ")
        return GeocodeResult(box, label.ifBlank { q })
    }
}
