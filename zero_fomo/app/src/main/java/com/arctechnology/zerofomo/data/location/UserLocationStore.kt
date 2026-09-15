package com.arctechnology.zerofomo.data.location

import android.content.SharedPreferences
import androidx.core.content.edit
import com.arctechnology.zerofomo.model.Country
import com.arctechnology.zerofomo.model.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class LocationSource { DEFAULT, NETWORK, DEVICE, MANUAL }

/** Where the user is "looking from": drives the country theme, the default
 *  feed filter, and the header pill. */
data class UserLocation(
    val country: Country,
    val place: Place?,
    val source: LocationSource,
) {
    val label: String get() = place?.name ?: country.name
}

/**
 * Single source of truth for the chosen location, persisted across launches.
 * DEFAULT/NETWORK values are provisional: a later device fix or manual pick
 * replaces them; a MANUAL pick is never overwritten automatically.
 */
@Singleton
class UserLocationStore @Inject constructor(
    private val prefs: SharedPreferences,
    private val gazetteer: Gazetteer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UserLocation?>(null)

    /** null until restored from prefs (a few ms after process start). */
    val state: StateFlow<UserLocation?> = _state.asStateFlow()

    init { scope.launch { restore() } }

    private suspend fun restore() {
        val cc = prefs.getString(KEY_CC, null)
        val source = prefs.getString(KEY_SOURCE, null)
            ?.let { s -> LocationSource.entries.firstOrNull { it.name == s } }
            ?: LocationSource.DEFAULT
        val country = gazetteer.country(cc) ?: Country.BAHAMAS
        val place = prefs.getString(KEY_PLACE, null)?.let { name ->
            val lat = prefs.getFloat(KEY_LAT, Float.NaN)
            val lng = prefs.getFloat(KEY_LNG, Float.NaN)
            if (lat.isNaN() || lng.isNaN()) null
            else Place(name, country.code, lat.toDouble(), lng.toDouble(),
                population = 0, timezone = prefs.getString(KEY_TZ, "") ?: "")
        } ?: gazetteer.anchorOf(country.code)
        _state.value = UserLocation(country, place, if (cc == null) LocationSource.DEFAULT else source)
    }

    val isManual: Boolean get() = _state.value?.source == LocationSource.MANUAL

    suspend fun setPlace(place: Place, source: LocationSource) {
        val country = gazetteer.country(place.countryCode) ?: Country.BAHAMAS
        persist(country, place, source)
    }

    suspend fun setCountry(country: Country, source: LocationSource) {
        persist(country, gazetteer.anchorOf(country.code), source)
    }

    /** Provisional update (network/locale): only applied while nothing
     *  better is known. */
    suspend fun suggestCountry(code: String) {
        val current = _state.value
        if (current != null && current.source != LocationSource.DEFAULT) return
        val country = gazetteer.country(code) ?: return
        if (current?.country?.code == country.code) return
        persist(country, gazetteer.anchorOf(country.code), LocationSource.NETWORK)
    }

    private fun persist(country: Country, place: Place?, source: LocationSource) {
        prefs.edit {
            putString(KEY_CC, country.code)
            putString(KEY_SOURCE, source.name)
            if (place == null) {
                remove(KEY_PLACE); remove(KEY_LAT); remove(KEY_LNG); remove(KEY_TZ)
            } else {
                putString(KEY_PLACE, place.name)
                putFloat(KEY_LAT, place.lat.toFloat())
                putFloat(KEY_LNG, place.lng.toFloat())
                putString(KEY_TZ, place.timezone)
            }
        }
        _state.value = UserLocation(country, place, source)
    }

    private companion object {
        const val KEY_CC = "loc_cc"
        const val KEY_PLACE = "loc_place"
        const val KEY_LAT = "loc_lat"
        const val KEY_LNG = "loc_lng"
        const val KEY_TZ = "loc_tz"
        const val KEY_SOURCE = "loc_source"
    }
}
