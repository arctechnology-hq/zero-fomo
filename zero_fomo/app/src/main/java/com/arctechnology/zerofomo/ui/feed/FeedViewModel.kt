package com.arctechnology.zerofomo.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arctechnology.zerofomo.data.EventRepository
import com.arctechnology.zerofomo.data.location.LocationEngine
import com.arctechnology.zerofomo.model.BahamianIsland
import com.arctechnology.zerofomo.model.DateRangeFilter
import com.arctechnology.zerofomo.model.Event
import com.arctechnology.zerofomo.model.EventCategory
import com.arctechnology.zerofomo.model.LocationFilter
import com.arctechnology.zerofomo.model.LocationQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One immutable filter state = MVI-style single source of UI truth. */
data class FilterState(
    val location: LocationFilter = LocationFilter.IslandTag(BahamianIsland.NEW_PROVIDENCE),
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
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: EventRepository,
    private val locationEngine: LocationEngine,
) : ViewModel() {

    private val filters = MutableStateFlow(FilterState())
    private val isRefreshing = MutableStateFlow(false)
    private val syncError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<FeedUiState> = combine(
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
        FeedUiState(visible, f, refreshing, error, lastSync)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState())

    init { refreshIfStale() }   // sync on open only when cache is stale

    fun setDateRange(range: DateRangeFilter) =
        filters.update { it.copy(dateRange = range) }

    fun setIsland(island: BahamianIsland?) = filters.update {
        it.copy(location = island?.let { i -> LocationFilter.IslandTag(i) }
            ?: LocationFilter.Everywhere)
    }

    fun toggleCategory(category: EventCategory) = filters.update {
        val next = if (category in it.categories) it.categories - category
        else it.categories + category
        it.copy(categories = next)
    }

    /** Free-text location box → polymorphic LocationEngine resolution. */
    fun searchLocation(raw: String) {
        viewModelScope.launch {
            val filter = locationEngine.resolve(LocationQuery.FreeText(raw))
            filters.update { it.copy(location = filter) }
        }
    }

    fun setKeyword(keyword: String) = filters.update { it.copy(keyword = keyword) }

    fun clearFilters() { filters.value = FilterState() }

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
                syncError.value = "Offline — showing cached events"
            } finally {
                isRefreshing.value = false
            }
        }
    }

    fun dismissError() { syncError.value = null }
}
