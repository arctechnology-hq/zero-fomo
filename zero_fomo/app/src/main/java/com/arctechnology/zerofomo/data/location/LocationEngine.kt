package com.arctechnology.zerofomo.data.location

import com.arctechnology.zerofomo.model.AdminLevel
import com.arctechnology.zerofomo.model.BahamianIsland
import com.arctechnology.zerofomo.model.BoundingBox
import com.arctechnology.zerofomo.model.LocationFilter
import com.arctechnology.zerofomo.model.LocationQuery
import javax.inject.Inject
import javax.inject.Singleton

/** Seam for postal / admin-area resolution. NoOpGeocoder keeps the app
 *  fully offline; PhotonGeocoder (bound in LocationModule) answers the rest. */
interface GeocodingService {
    suspend fun geocodePostal(code: String, countryHint: String?): GeocodeResult?
    suspend fun geocodeAdminArea(name: String, level: AdminLevel, countryCode: String?): GeocodeResult?
}

data class GeocodeResult(val viewport: BoundingBox, val formattedName: String)

class NoOpGeocoder @Inject constructor() : GeocodingService {
    override suspend fun geocodePostal(code: String, countryHint: String?) = null
    override suspend fun geocodeAdminArea(name: String, level: AdminLevel, countryCode: String?) = null
}

// ── Strategy contract + concrete resolvers ──────────────────────────────────

fun interface LocationResolver<in Q : LocationQuery> {
    suspend fun resolve(query: Q): LocationFilter
}

/** Bahamas islands: definitive list, zero latency, zero network. */
class IslandResolver @Inject constructor() : LocationResolver<LocationQuery.Island> {
    override suspend fun resolve(query: LocationQuery.Island): LocationFilter =
        LocationFilter.IslandTag(query.island)
}

/** World gazetteer place -> "near this city" radius. */
class PlaceResolver @Inject constructor() : LocationResolver<LocationQuery.Place> {
    override suspend fun resolve(query: LocationQuery.Place): LocationFilter =
        LocationFilter.Near(query.place.lat, query.place.lng, DEFAULT_RADIUS_KM, query.place.name)

    companion object { const val DEFAULT_RADIUS_KM = 60.0 }
}

/** Alphanumeric postal parsing -> geospatial lookup. */
class PostalCodeResolver @Inject constructor(
    private val geocoder: GeocodingService,
) : LocationResolver<LocationQuery.PostalCode> {
    override suspend fun resolve(query: LocationQuery.PostalCode): LocationFilter =
        geocoder.geocodePostal(query.code, query.countryHint)
            ?.let { LocationFilter.Bounds(it.viewport, it.formattedName) }
            ?: LocationFilter.Everywhere
}

/** Administrative-area (county/state) filtering. */
class AdminAreaResolver @Inject constructor(
    private val geocoder: GeocodingService,
) : LocationResolver<LocationQuery.AdminArea> {
    override suspend fun resolve(query: LocationQuery.AdminArea): LocationFilter =
        geocoder.geocodeAdminArea(query.name, query.level, query.countryCode)
            ?.let { LocationFilter.Bounds(it.viewport, it.formattedName) }
            ?: LocationFilter.Everywhere
}

// ── The engine: classification + polymorphic dispatch ───────────────────────

@Singleton
class LocationEngine @Inject constructor(
    private val islandResolver: IslandResolver,
    private val placeResolver: PlaceResolver,
    private val postalResolver: PostalCodeResolver,
    private val adminResolver: AdminAreaResolver,
    private val gazetteer: Gazetteer?,
) {
    suspend fun resolve(query: LocationQuery): LocationFilter = when (query) {
        is LocationQuery.Island -> islandResolver.resolve(query)
        is LocationQuery.Place -> placeResolver.resolve(query)
        is LocationQuery.PostalCode -> postalResolver.resolve(query)
        is LocationQuery.AdminArea -> adminResolver.resolve(query)
        is LocationQuery.FreeText -> resolve(classifyAsync(query.raw))
    }

    /**
     * Classification order is a product decision, not an accident: the free
     * offline island gazetteer always gets first refusal, postal patterns are
     * unambiguous, the world gazetteer catches any city it knows, and
     * admin-area (online geocoder) is the catch-all.
     */
    fun classify(raw: String): LocationQuery {
        val text = raw.trim()
        if (text.isEmpty()) return LocationQuery.AdminArea(text)
        BahamianIsland.match(text)?.let { return LocationQuery.Island(it) }
        if (US_ZIP.matches(text)) return LocationQuery.PostalCode(text, "US")
        if (CA_POSTAL.matches(text)) return LocationQuery.PostalCode(text, "CA")
        if (UK_POSTCODE.matches(text)) return LocationQuery.PostalCode(text, "GB")
        return LocationQuery.AdminArea(text, AdminLevel.COUNTY)
    }

    /** [classify] plus the world-gazetteer step, which needs a suspend call. */
    suspend fun classifyAsync(raw: String): LocationQuery {
        val sync = classify(raw)
        if (sync !is LocationQuery.AdminArea || gazetteer == null) return sync
        val place = gazetteer.search(raw, limit = 1).firstOrNull() ?: return sync
        return LocationQuery.Place(place)
    }

    private companion object {
        val US_ZIP = Regex("""^\d{5}(-\d{4})?$""")
        val CA_POSTAL = Regex("""^[A-Za-z]\d[A-Za-z]\s?\d[A-Za-z]\d$""")
        val UK_POSTCODE = Regex("""^[A-Za-z]{1,2}\d[A-Za-z\d]?\s?\d[A-Za-z]{2}$""")
    }
}
