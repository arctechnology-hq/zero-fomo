package com.arctechnology.zerofomo.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arctechnology.zerofomo.data.EventRepository
import com.arctechnology.zerofomo.data.location.DeviceLocationProvider
import com.arctechnology.zerofomo.data.location.Gazetteer
import com.arctechnology.zerofomo.data.location.LocationEngine
import com.arctechnology.zerofomo.data.location.LocationSource
import com.arctechnology.zerofomo.data.location.PlaceResolver
import com.arctechnology.zerofomo.data.location.UserLocation
import com.arctechnology.zerofomo.data.location.UserLocationStore
import com.arctechnology.zerofomo.model.BahamianIsland
import com.arctechnology.zerofomo.model.Country
import com.arctechnology.zerofomo.model.DateRangeFilter
import com.arctechnology.zerofomo.model.Event
import com.arctechnology.zerofomo.model.EventCategory
import com.arctechnology.zerofomo.model.LocationFilter
import com.arctechnology.zerofomo.model.LocationQuery
import com.arctechnology.zerofomo.model.Place
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One immutable filter state = MVI-style single source of UI truth. */
data class FilterState(
    val location: LocationFilter = LocationFilter.Everywhere,
    val dateRange: DateRangeFilter = DateRangeFilter.AllUpcoming,
    val categories: Set<EventCategory> = emptySet(),   // empty = all categories
    val keyword: String = "",                          // blank = no text filter
)

data class FeedUiState(
    val events: List<Event> = emptyList(),
    val filters: FilterState = FilterState(),
    val isRefreshing: Boolean = false,
    val syncError: String? = null,
    val lastSyncEpochMs: Long = 0L,
    val userLocation: UserLocation? = null,
    val nearbyPlaces: List<Place> = emptyList(),       // chips for the current country
    val placeSuggestions: List<Place> = emptyList(),   // location search results
    val countries: List<Country> = emptyList(),
    val isLocating: Boolean = false,
    val locationMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: EventRepository,
    private val locationEngine: LocationEngine,
    private val locationStore: UserLocationStore,
    private val gazetteer: Gazetteer,
    private val device: DeviceLocationProvider,
) : ViewModel() {

    private val filters = MutableStateFlow(FilterState())
    private val isRefreshing = MutableStateFlow(false)
    private val syncError = MutableStateFlow<String?>(null)
    private val nearbyPlaces = MutableStateFlow<List<Place>>(emptyList())
    private val placeSuggestions = MutableStateFlow<List<Place>>(emptyList())
    private val countries = MutableStateFlow<List<Country>>(emptyList())
    private val isLocating = MutableStateFlow(false)
    private val locationMessage = MutableStateFlow<String?>(null)

    /** True once the user picked a location chip by hand; the automatic
     *  "follow the user's location" default then stops overriding it. */
    private var locationPinned = false
    private var searchJob: Job? = null

    private data class Core(
        val events: List<Event>, val filters: FilterState, val refreshing: Boolean,
        val error: String?, val lastSync: Long,
    )

    private val core = combine(
        filters.flatMapLatest { f -> repository.observeEvents(f.location, f.dateRange) },
        filters, isRefreshing, syncError, repository.lastSyncEpochMs,
    ) { events, f, refreshing, error, lastSync ->
        var visible = if (f.categories.isEmpty()) events
        else events.filter { it.category in f.categories }
        if (f.keyword.isNotBlank()) {
            val needle = f.keyword.trim()
            visible = visible.filter {
                it.name.contains(needle, ignoreCase = true) ||
                    it.venue.contains(needle, ignoreCase = true) ||
                    it.description.contains(needle, ignoreCase = true)
            }
        }
        Core(visible, f, refreshing, error, lastSync)
    }

    private data class Loc(
        val user: UserLocation?, val nearby: List<Place>, val suggestions: List<Place>,
        val countries: List<Country>, val locating: Boolean, val message: String?,
    )

    private val loc = combine(
        locationStore.state, nearbyPlaces, placeSuggestions, countries, isLocating, locationMessage,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        Loc(arr[0] as UserLocation?, arr[1] as List<Place>, arr[2] as List<Place>,
            arr[3] as List<Country>, arr[4] as Boolean, arr[5] as String?)
    }

    val uiState: StateFlow<FeedUiState> = combine(core, loc) { c, l ->
        FeedUiState(
            events = c.events, filters = c.filters, isRefreshing = c.refreshing,
            syncError = c.error, lastSyncEpochMs = c.lastSync,
            userLocation = l.user, nearbyPlaces = l.nearby, placeSuggestions = l.suggestions,
            countries = l.countries, isLocating = l.locating, locationMessage = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState())

    init {
        refreshIfStale()   // sync on open only when cache is stale
        viewModelScope.launch { countries.value = gazetteer.countries() }
        viewModelScope.launch {
            // Follow the stored location: default filter + nearby chips.
            locationStore.state.filterNotNull().collect { ul ->
                nearbyPlaces.value = gazetteer.placesIn(ul.country.code, limit = 8)
                if (!locationPinned) filters.update { it.copy(location = defaultFilterFor(ul)) }
            }
        }
        viewModelScope.launch {
            // Silent improvements that need no permission prompt: network
            // country while nothing better is known, a coarse fix if the
            // permission was already granted earlier.
            device.networkCountryCode()?.let { locationStore.suggestCountry(it) }
            if (device.hasPermission() && !locationStore.isManual) locate(quiet = true)
        }
    }

    /** Bahamas keeps its island vocabulary; everywhere else is a radius
     *  around the chosen city, or the whole country when only that is known. */
    private fun defaultFilterFor(ul: UserLocation): LocationFilter {
        if (ul.country.code == "BS") {
            val island = ul.place?.let { BahamianIsland.match(it.name) }
            return LocationFilter.IslandTag(island ?: BahamianIsland.NEW_PROVIDENCE)
        }
        val p = ul.place ?: return LocationFilter.CountryTag(ul.country.code, ul.country.name)
        return LocationFilter.Near(p.lat, p.lng, PlaceResolver.DEFAULT_RADIUS_KM, p.name)
    }

    // ── Filters ─────────────────────────────────────────────────────────────

    fun setDateRange(range: DateRangeFilter) =
        filters.update { it.copy(dateRange = range) }

    fun setIsland(island: BahamianIsland?) {
        locationPinned = true
        filters.update {
            it.copy(location = island?.let { i -> LocationFilter.IslandTag(i) }
                ?: LocationFilter.Everywhere)
        }
    }

    fun setLocationFilter(filter: LocationFilter) {
        locationPinned = true
        filters.update { it.copy(location = filter) }
    }

    fun toggleCategory(category: EventCategory) = filters.update {
        val next = if (category in it.categories) it.categories - category
        else it.categories + category
        it.copy(categories = next)
    }

    /** Free-text location box -> polymorphic LocationEngine resolution. */
    fun searchLocation(raw: String) {
        viewModelScope.launch {
            val query = locationEngine.classifyAsync(raw)
            if (query is LocationQuery.Place) { selectPlace(query.place); return@launch }
            locationPinned = true
            val filter = locationEngine.resolve(query)
            filters.update { it.copy(location = filter) }
        }
    }

    fun setKeyword(keyword: String) = filters.update { it.copy(keyword = keyword) }

    fun clearFilters() {
        locationPinned = false
        filters.value = FilterState()
        locationStore.state.value?.let { ul ->
            filters.update { it.copy(location = defaultFilterFor(ul)) }
        }
    }

    // ── Location ────────────────────────────────────────────────────────────

    fun queryPlaces(text: String) {
        searchJob?.cancel()
        if (text.isBlank()) { placeSuggestions.value = emptyList(); return }
        searchJob = viewModelScope.launch {
            placeSuggestions.value = gazetteer.search(
                text, preferCountry = locationStore.state.value?.country?.code)
        }
    }

    fun selectPlace(place: Place) {
        locationPinned = false
        placeSuggestions.value = emptyList()
        viewModelScope.launch { locationStore.setPlace(place, LocationSource.MANUAL) }
    }

    fun selectCountry(country: Country) {
        locationPinned = false
        viewModelScope.launch { locationStore.setCountry(country, LocationSource.MANUAL) }
    }

    /** Called after the permission dialog closes (or immediately when it was
     *  already granted). */
    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) locate(quiet = false)
        else locationMessage.value = "Location is off. Pick a city instead."
    }

    private fun locate(quiet: Boolean) {
        viewModelScope.launch {
            isLocating.value = true
            try {
                val fix = device.current()
                val place = fix?.let { gazetteer.nearest(it.lat, it.lng) }
                if (place != null) {
                    locationPinned = false
                    locationStore.setPlace(place, LocationSource.DEVICE)
                    if (!quiet) locationMessage.value = "You're near ${place.name}"
                } else if (!quiet) {
                    locationMessage.value = "Couldn't get a fix. Pick a city instead."
                }
            } finally {
                isLocating.value = false
            }
        }
    }

    fun dismissLocationMessage() { locationMessage.value = null }

    // ── Sync ────────────────────────────────────────────────────────────────

    /** Called on every foreground resume: cheap freshness without hammering
     *  the feed host every time the user flips apps. */
    fun refreshIfStale(maxAgeMinutes: Long = 30) {
        val age = System.currentTimeMillis() - repository.lastSyncEpochMs.value
        if (age > maxAgeMinutes * 60_000) refresh()
    }

    fun toggleFavorite(event: Event) {
        viewModelScope.launch { repository.toggleFavorite(event) }
    }

    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            syncError.value = null
            try {
                repository.refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // scope teardown, not a network failure
            } catch (e: Exception) {
                syncError.value = "Offline. Showing cached events."
            } finally {
                isRefreshing.value = false
            }
        }
    }

    fun dismissError() { syncError.value = null }
}
