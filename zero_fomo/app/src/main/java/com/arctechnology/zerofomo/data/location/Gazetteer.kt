package com.arctechnology.zerofomo.data.location

import com.arctechnology.zerofomo.model.Country
import com.arctechnology.zerofomo.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * Offline world gazetteer: 252 countries (with flag colours) and ~12.5k
 * populated places bundled under `assets/geo/`. Loaded once, lazily, off the
 * main thread; every lookup after that is a pure in-memory scan, so the app
 * keeps its no-server, works-on-a-plane property for location as well.
 *
 * [open] abstracts the asset source so JVM tests can point at the same files
 * on disk.
 */
class Gazetteer(private val open: (String) -> InputStream) {

    @Serializable
    private data class CountryRow(
        val cc: String, val name: String, val capital: String = "",
        val continent: String = "", val currency: String = "",
        val a: String, val b: String,
    )

    @Serializable
    private data class CityRow(
        val n: String, val cc: String, val lat: Double, val lng: Double,
        val p: Int = 0, val tz: String = "", val a: String? = null,
        @SerialName("cap") val capital: Int = 0,
    )

    private class Data(val countries: List<Country>, val places: List<Place>) {
        val byCode = countries.associateBy { it.code }
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val data: Data by lazy {
        val countries = json.decodeFromString<List<CountryRow>>(
            open("geo/countries.json").bufferedReader().use { it.readText() })
            .map { Country(it.cc.uppercase(), it.name, it.capital, it.continent,
                it.currency, hex(it.a), hex(it.b)) }
        val places = json.decodeFromString<List<CityRow>>(
            open("geo/cities.json").bufferedReader().use { it.readText() })
            .map { Place(it.n, it.cc.uppercase(), it.lat, it.lng, it.p, it.tz,
                it.capital == 1, it.a ?: it.n) }
        Data(countries, places)
    }

    private fun hex(s: String): Long = 0xFF000000L or s.trim().removePrefix("#").toLong(16)

    /** Force the parse early (app start) so the first tap is instant. */
    suspend fun warmUp() = withContext(Dispatchers.IO) { data.countries.size }

    suspend fun countries(): List<Country> = withContext(Dispatchers.IO) { data.countries }

    suspend fun country(code: String?): Country? = withContext(Dispatchers.IO) {
        code?.let { data.byCode[it.uppercase()] }
    }

    /** Largest places in a country, capital first. */
    suspend fun placesIn(countryCode: String, limit: Int = 12): List<Place> =
        withContext(Dispatchers.IO) {
            data.places.asSequence()
                .filter { it.countryCode == countryCode.uppercase() }
                .sortedWith(compareByDescending<Place> { it.isCapital }
                    .thenByDescending { it.population })
                .take(limit).toList()
        }

    /** Capital (or largest place) of a country; the anchor when only the
     *  country is known (network region, locale). */
    suspend fun anchorOf(countryCode: String): Place? = placesIn(countryCode, 1).firstOrNull()

    /** Closest gazetteer place to a coordinate: how a coarse device fix
     *  becomes "Nassau" or "Miami" without any network. */
    suspend fun nearest(lat: Double, lng: Double): Place? = withContext(Dispatchers.IO) {
        data.places.minByOrNull { it.distanceKm(lat, lng) }
    }

    /**
     * Prefix search for the location box. "nas" -> Nassau first; "kingston,
     * jamaica", "george town ky" or "port of spain" all resolve: the words are
     * split into the longest city prefix that still leaves a recognisable
     * country hint. Ranking: exact name, then the user's own country, then
     * capitals, then population.
     */
    suspend fun search(
        query: String, limit: Int = 8, preferCountry: String? = null,
    ): List<Place> = withContext(Dispatchers.IO) {
        val raw = query.trim().lowercase()
        if (raw.length < 2) return@withContext emptyList()
        val parts = raw.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@withContext emptyList()
        val prefer = preferCountry?.uppercase()
        for (k in parts.size downTo 1) {
            val city = parts.take(k).joinToString(" ")
            val hint = parts.drop(k).joinToString(" ")
            val codes = countryCodesFor(hint) ?: continue   // hint present but unknown
            val hits = data.places.asSequence()
                .filter { p ->
                    (p.name.lowercase().startsWith(city) || p.asciiName.lowercase().startsWith(city)) &&
                        (codes.isEmpty() || p.countryCode in codes)
                }
                .sortedWith(compareByDescending<Place> {
                    it.name.lowercase() == city || it.asciiName.lowercase() == city
                }.thenByDescending { it.countryCode == prefer }
                    .thenByDescending { it.isCapital }
                    .thenByDescending { it.population })
                .take(limit).toList()
            if (hits.isNotEmpty()) return@withContext hits
        }
        emptyList()
    }

    /** Empty hint -> empty set (no filter); unknown hint -> null. */
    private fun countryCodesFor(hint: String): Set<String>? {
        if (hint.isEmpty()) return emptySet()
        COUNTRY_ALIASES[hint]?.let { return setOf(it) }
        val codes = data.countries.filter { c ->
            c.code.lowercase() == hint || c.name.lowercase().startsWith(hint)
        }.map { it.code }.toSet()
        return codes.ifEmpty { null }
    }

    private companion object {
        val COUNTRY_ALIASES = mapOf(
            "usa" to "US", "america" to "US", "uk" to "GB", "england" to "GB",
            "scotland" to "GB", "wales" to "GB", "britain" to "GB", "uae" to "AE",
            "dr" to "DO", "t&t" to "TT", "trini" to "TT", "the bahamas" to "BS",
        )
    }
}
