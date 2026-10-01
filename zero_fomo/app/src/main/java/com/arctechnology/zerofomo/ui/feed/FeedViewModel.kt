package com.arctechnology.zerofomo.ui.feed

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arctechnology.zerofomo.R
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
import com.arctechnology.zerofomo.model.Market
import com.arctechnology.zerofomo.model.MarketSize
import com.arctechnology.zerofomo.model.Place
import com.arctechnology.zerofomo.model.Region
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A status message the ViewModel needs to surface — carried as a resource id
 *  (plus optional format args) rather than built English text, so
 *  FeedScreen is the only layer that resolves it via stringResource. */
sealed interface StatusMessage {
    data class Res(@StringRes val resId: Int) : StatusMessage
    data class ResArg(@StringRes val resId: Int, val arg: String) : StatusMessage
    data class ResArgs(@StringRes val resId: Int, val args: List<String>) : StatusMessage
}

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
    val syncError: StatusMessage? = null,
    val lastSyncEpochMs: Long = 0L,
    val userLocation: UserLocation? = null,
    val nearbyPlaces: List<Place> = emptyList(),       // chips for the current country
    val placeSuggestions: List<Place> = emptyList(),   // location search results
    val countries: List<Country> = emptyList(),
    val isLocating: Boolean = false,
    val locationMessage: StatusMessage? = null,
    val distanceOrigin: Pair<Double, Double>? = null,  // where card distances are measured from
    // G5: adaptive scope
    val region: Region? = null,                        // the user's region, for the region chip
    val regions: List<Region> = emptyList(),           // every region, for the market browser
    val markets: List<Market> = emptyList(),           // manifest (cached offline)
    val nearRadiusKm: Double = PlaceResolver.DEFAULT_RADIUS_KM,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
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
    private val syncError = MutableStateFlow<StatusMessage?>(null)
    private val nearbyPlaces = MutableStateFlow<List<Place>>(emptyList())
    private val placeSuggestions = MutableStateFlow<List<Place>>(emptyList())
    private val countries = MutableStateFlow<List<Country>>(emptyList())
    private val isLocating = MutableStateFlow(false)
    private val locationMessage = MutableStateFlow<StatusMessage?>(null)
    private val userRegion = MutableStateFlow<Region?>(null)
    private val regions = MutableStateFlow<List<Region>>(emptyList())
    private val nearRadiusKm = MutableStateFlow(PlaceResolver.DEFAULT_RADIUS_KM)

    /** True once the user picked a location chip by hand; the automatic
     *  "follow the user's location" default (and auto-widening) then stop
     *  overriding it. A flow so the density watcher can observe it. */
    private val locationPinned = MutableStateFlow(false)
    private var searchJob: Job? = null

    private data class Core(
        val events: List<Event>, val filters: FilterState, val refreshing: Boolean,
        val error: StatusMessage?, val lastSync: Long,
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
        val countries: List<Country>, val locating: Boolean, val message: StatusMessage?,
    )

    private val loc = combine(
        locationStore.state, nearbyPlaces, placeSuggestions, countries, isLocating, locationMessage,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        Loc(arr[0] as UserLocation?, arr[1] as List<Place>, arr[2] as List<Place>,
            arr[3] as List<Country>, arr[4] as Boolean, arr[5] as StatusMessage?)
    }

    private data class Scope(
        val region: Region?, val regions: List<Region>, val markets: List<Market>, val radius: Double,
    )

    private val scope = combine(userRegion, regions, repository.markets, nearRadiusKm) { r, rs, ms, rad ->
        Scope(r, rs, ms, rad)
    }

    val uiState: StateFlow<FeedUiState> = combine(core, loc, scope) { c, l, s ->
        FeedUiState(
            events = c.events, filters = c.filters, isRefreshing = c.refreshing,
            syncError = c.error, lastSyncEpochMs = c.lastSync,
            userLocation = l.user, nearbyPlaces = l.nearby, placeSuggestions = l.suggestions,
            countries = l.countries, isLocating = l.locating, locationMessage = l.message,
            distanceOrigin = distanceOriginFor(c.filters.location, l.user),
            region = s.region, regions = s.regions, markets = s.markets, nearRadiusKm = s.radius,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState())

    /** The point card distances are measured from: the active "near" point,
     *  else the user's city. Bahamas views get none — island-tagged events carry
     *  the island centroid, so every distance would read as zero. */
    private fun distanceOriginFor(filter: LocationFilter, ul: UserLocation?): Pair<Double, Double>? =
        when (filter) {
            is LocationFilter.Near -> filter.lat to filter.lng
            is LocationFilter.IslandTag -> null
            else -> ul?.takeIf { it.country.code != "BS" }?.place?.let { it.lat to it.lng }
        }

    init {
        refreshIfStale()   // sync on open only when cache is stale
        viewModelScope.launch { countries.value = gazetteer.countries() }
        viewModelScope.launch { regions.value = gazetteer.regions() }
        viewModelScope.launch { repository.loadMarkets() }
        viewModelScope.launch {
            // Follow the stored location: default filter + nearby chips.
            locationStore.state.filterNotNull().collect { ul ->
                nearbyPlaces.value = gazetteer.placesIn(ul.country.code, limit = 8)
                userRegion.value = gazetteer.regionOf(ul.country.code)
                if (!locationPinned.value) filters.update { it.copy(location = defaultFilterFor(ul)) }
                // A new city may map to different market feeds: sync them.
                if (repository.needsResyncFor(ul.place?.lat, ul.place?.lng)) refresh()
            }
        }
        viewModelScope.launch {
            // Silent improvements that need no permission prompt: network
            // country while nothing better is known, a coarse fix if the
            // permission was already granted earlier.
            device.networkCountryCode()?.let { locationStore.suggestCountry(it) }
            if (device.hasPermission() && !locationStore.isManual) locate(quiet = true)
        }
        watchDensity()
    }

    /** Bahamas keeps its island vocabulary; everywhere else is a radius
     *  around the chosen city, or the whole country when only that is known.
     *  The radius follows the market's size (G5): a metro gets a tight circle,
     *  a small market the default, and a place with no curated market or an
     *  empty one goes straight to the whole country. */
    private fun defaultFilterFor(ul: UserLocation): LocationFilter {
        if (ul.country.code == "BS") {
            val island = ul.place?.let { BahamianIsland.match(it.name) }
            return LocationFilter.IslandTag(island ?: BahamianIsland.NEW_PROVIDENCE)
        }
        val p = ul.place ?: return LocationFilter.CountryTag(ul.country.code, ul.country.name)
        val market = repository.nearestMarket(p.lat, p.lng)
        val radius = when (market?.size) {
            MarketSize.LARGE -> RADIUS_METRO_KM
            MarketSize.MEDIUM -> PlaceResolver.DEFAULT_RADIUS_KM
            MarketSize.SMALL -> RADIUS_WIDE_KM
            MarketSize.EMPTY, null -> return LocationFilter.CountryTag(ul.country.code, ul.country.name)
        }
        nearRadiusKm.value = radius
        return LocationFilter.Near(p.lat, p.lng, radius, p.name)
    }

    /**
     * Auto-widen (G5): while the automatic scope lists fewer than
     * [AUTO_WIDEN_MIN] events in the next 30 days, step out — radius → whole
     * country → region — and say so once. Only automatic scopes widen; a chip
     * the user tapped is never overridden, and nothing moves until the first
     * sync has landed (an empty cache is not "few events").
     */
    private fun watchDensity() = viewModelScope.launch {
        combine(filters, isRefreshing, repository.lastSyncEpochMs, locationPinned) { f, refreshing, sync, pinned ->
            if (!refreshing && sync > 0L && !pinned) f.location else null
        }
            .distinctUntilChanged()
            .flatMapLatest { location ->
                when (location) {
                    is LocationFilter.Near, is LocationFilter.CountryTag, is LocationFilter.IslandTag ->
                        repository.observeEvents(location, DateRangeFilter.Next30Days)
                            .map { location to it.size }
                    else -> flowOf(null)
                }
            }
            .debounce(400)
            .collect { pair ->
                val (location, count) = pair ?: return@collect
                if (count < AUTO_WIDEN_MIN) widen(location)
            }
    }

    private suspend fun widen(from: LocationFilter) {
        val ul = locationStore.state.value ?: return
        val next: LocationFilter = when (from) {
            is LocationFilter.Near, is LocationFilter.IslandTag ->
                LocationFilter.CountryTag(ul.country.code, ul.country.name)
            is LocationFilter.CountryTag -> {
                val region = userRegion.value ?: return
                val codes = gazetteer.countriesIn(region.id)
                if (codes.size < 2) return
                LocationFilter.RegionTag(region.id, codes, region.name)
            }
            else -> return
        }
        if (locationPinned.value) return
        filters.update { it.copy(location = next) }
        locationMessage.value = StatusMessage.ResArgs(
            R.string.feed_status_widened, listOf(from.label, next.label))
    }

    // ── Filters ─────────────────────────────────────────────────────────────

    fun setDateRange(range: DateRangeFilter) =
        filters.update { it.copy(dateRange = range) }

    fun setIsland(island: BahamianIsland?) {
        locationPinned.value = true
        filters.update {
            it.copy(location = island?.let { i -> LocationFilter.IslandTag(i) }
                ?: LocationFilter.Everywhere)
        }
    }

    fun setLocationFilter(filter: LocationFilter) {
        locationPinned.value = true
        if (filter is LocationFilter.Near) nearRadiusKm.value = filter.radiusKm
        filters.update { it.copy(location = filter) }
    }

    /** Region chip: every country in the user's region. */
    fun setRegionScope() {
        val region = userRegion.value ?: return
        viewModelScope.launch {
            val codes = gazetteer.countriesIn(region.id)
            setLocationFilter(LocationFilter.RegionTag(region.id, codes, region.name))
        }
    }

    /** Radius menu on the "Near <city>" chip: re-scopes the current circle. */
    fun setNearRadius(km: Double) {
        nearRadiusKm.value = km
        val current = filters.value.location as? LocationFilter.Near ?: return
        locationPinned.value = true
        filters.update { it.copy(location = current.copy(radiusKm = km)) }
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
            locationPinned.value = true
            val filter = locationEngine.resolve(query)
            filters.update { it.copy(location = filter) }
        }
    }

    fun setKeyword(keyword: String) = filters.update { it.copy(keyword = keyword) }

    fun clearFilters() {
        locationPinned.value = false
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
        locationPinned.value = false
        placeSuggestions.value = emptyList()
        viewModelScope.launch { locationStore.setPlace(place, LocationSource.MANUAL) }
    }

    fun selectCountry(country: Country) {
        locationPinned.value = false
        viewModelScope.launch { locationStore.setCountry(country, LocationSource.MANUAL) }
    }

    /** Market browser (G5): looking from a market's centre is the same as
     *  picking its city, so the theme, chips and synced feeds all follow. */
    fun selectMarket(market: Market) {
        selectPlace(Place(
            name = market.cityName, countryCode = market.country,
            lat = market.lat, lng = market.lng, population = 0, timezone = market.tz,
        ))
    }

    /** Called after the permission dialog closes (or immediately when it was
     *  already granted). */
    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) locate(quiet = false)
        else locationMessage.value = StatusMessage.Res(R.string.feed_status_location_off)
    }

    private fun locate(quiet: Boolean) {
        viewModelScope.launch {
            isLocating.value = true
            try {
                val fix = device.current()
                val place = fix?.let { gazetteer.nearest(it.lat, it.lng) }
                if (place != null) {
                    locationPinned.value = false
                    locationStore.setPlace(place, LocationSource.DEVICE)
                    if (!quiet) locationMessage.value =
                        StatusMessage.ResArg(R.string.feed_status_near_place, place.name)
                } else if (!quiet) {
                    locationMessage.value = StatusMessage.Res(R.string.feed_status_no_fix)
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
                syncError.value = StatusMessage.Res(R.string.feed_status_offline)
            } finally {
                isRefreshing.value = false
            }
        }
    }

    fun dismissError() { syncError.value = null }

    companion object {
        const val AUTO_WIDEN_MIN = 8          // events in the next 30 days
        const val RADIUS_METRO_KM = 25.0      // large markets: a metro, not a state
        const val RADIUS_WIDE_KM = 150.0      // small markets: the whole island / district
        val RADIUS_OPTIONS_KM = listOf(10.0, 25.0, 60.0, 150.0)
    }
}
