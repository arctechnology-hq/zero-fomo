package com.arctechnology.zerofomo.data

import com.arctechnology.zerofomo.data.db.EventDao
import com.arctechnology.zerofomo.data.db.EventEntity
import com.arctechnology.zerofomo.data.db.FavoriteEntity
import com.arctechnology.zerofomo.data.db.toDomain
import com.arctechnology.zerofomo.data.location.UserLocationStore
import com.arctechnology.zerofomo.data.network.EventsApi
import com.arctechnology.zerofomo.data.network.toEntity
import com.arctechnology.zerofomo.data.network.toModel
import com.arctechnology.zerofomo.model.Market
import com.arctechnology.zerofomo.model.MarketSelector
import java.time.LocalDate
import com.arctechnology.zerofomo.model.BahamianIsland
import com.arctechnology.zerofomo.model.BoundingBox
import com.arctechnology.zerofomo.model.DateRangeFilter
import com.arctechnology.zerofomo.model.Event
import com.arctechnology.zerofomo.model.LocationFilter
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first orchestration. Room is the single source of truth: the UI
 * only ever observes the database, and network syncs write into it.
 */
@Singleton
class EventRepository @Inject constructor(
    private val dao: EventDao,
    private val api: EventsApi,
    private val prefs: SharedPreferences,
    private val locationStore: UserLocationStore,
) {
    private val _lastSyncEpochMs =
        MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, 0L))

    /** Epoch millis of the last successful feed sync; 0 = never. Surfaced
     *  in the UI so feed freshness is honest, not implied. */
    val lastSyncEpochMs: StateFlow<Long> = _lastSyncEpochMs.asStateFlow()

    private val _purgedSavedNames = MutableStateFlow(
        prefs.getString(KEY_PURGED_SAVED, "")!!
            .split(PURGE_SEP).filter { it.isNotBlank() })

    /** Names of saved events that left the feed and were purged — sticky
     *  across restarts until the user dismisses the Saved-tab notice. */
    val purgedSavedNames: StateFlow<List<String>> = _purgedSavedNames.asStateFlow()

    fun dismissPurgedNotice() {
        prefs.edit { remove(KEY_PURGED_SAVED) }
        _purgedSavedNames.value = emptyList()
    }

    fun observeEvents(location: LocationFilter, dateRange: DateRangeFilter): Flow<List<Event>> {
        val range = dateRange.resolve()
        val from = range.start.toEpochDay()
        val to = range.endInclusive.toEpochDay()

        val entities = when (location) {
            is LocationFilter.IslandTag ->
                dao.byIslands(listOf(location.island.name), from, to)

            is LocationFilter.Bounds -> byBox(location.box, from, to)
            is LocationFilter.Near -> byBox(location.box, from, to)
            is LocationFilter.CountryTag -> dao.byCountry(location.countryCode, from, to)
            LocationFilter.Everywhere -> dao.allBetween(from, to)
        }

        return combine(entities, dao.favoriteIds()) { list, favIds ->
            val favs = favIds.toSet()
            list.map { it.toDomain(isSaved = it.id in favs) }
        }
    }

    /** Bahamas rows are island-tagged and rarely carry coordinates, so a box
     *  that touches an island matches that island's tag (the island stands in
     *  for the missing lat/lng); anywhere else it is a plain range query. */
    private fun byBox(box: BoundingBox, from: Long, to: Long): Flow<List<EventEntity>> {
        val islands = BahamianIsland.entries
            .filter { it.bounds.intersects(box) }
            .map { it.name }
        return if (islands.isNotEmpty()) dao.byIslands(islands, from, to)
        else dao.byBounds(box.minLat, box.maxLat, box.minLng, box.maxLng, from, to)
    }

    fun observeEvent(id: String): Flow<Event?> =
        combine(dao.byId(id), dao.favoriteIds()) { entity, favIds ->
            entity?.toDomain(isSaved = entity.id in favIds.toSet())
        }

    fun observeFavorites(): Flow<List<Event>> =
        dao.favorites().map { list -> list.map { it.toDomain(isSaved = true) } }

    suspend fun toggleFavorite(event: Event) {
        if (event.isSaved) dao.removeFavorite(event.id)
        else dao.addFavorite(FavoriteEntity(event.id))
    }

    /** Market ids synced by the last successful refresh (for change detection). */
    private val syncedMarkets: Set<String>
        get() = prefs.getString(KEY_SYNCED_MARKETS, "")!!.split(',').filter { it.isNotBlank() }.toSet()

    /**
     * Pull the feeds for the markets nearest the user and replace those
     * markets' rows. Throws on network failure; callers decide whether that
     * is a toast or a silent retry. Falls back to the legacy single feed when
     * the manifest is unreachable, so an old host layout still works.
     */
    suspend fun refresh() {
        val manifest = try {
            api.fetchMarkets().markets.map { it.toModel() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (manifest.isEmpty()) { refreshLegacy(); return }

        val place = locationStore.state.value?.place
        val selected = MarketSelector.select(manifest, place?.lat, place?.lng)
        if (selected.isEmpty()) { refreshLegacy(); return }

        val savedBefore = dao.favorites().first()
        var anyWritten = false
        val allNewIds = HashSet<String>()
        for (market in selected) {
            val feed = api.fetchMarketFeed(market.id)
            val entities = feed.events.mapNotNull {
                it.toEntity(feedMarket = feed.market ?: market.id,
                    feedCountry = feed.country ?: market.country)
            }
            // An empty feed for a curated market is a real state (no events
            // listed yet), so it still replaces stale rows.
            dao.replaceMarket(market.id, entities)
            entities.mapTo(allNewIds) { it.id }
            anyWritten = true
        }
        if (!anyWritten) return
        dao.deletePastOutsideMarkets(selected.map { it.id }, LocalDate.now().toEpochDay())
        if (dao.purgeOrphanFavorites() > 0) {
            recordPurgedSaved(savedBefore.filter { it.id !in allNewIds }.map { it.name })
        }
        markSynced(selected.map { it.id }.toSet())
    }

    /** True when the user's location now maps to a different market set than
     *  the last sync covered; the view model then triggers a refresh. */
    suspend fun needsResyncFor(lat: Double?, lng: Double?): Boolean {
        val manifest = try {
            api.fetchMarkets().markets.map { it.toModel() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        val wanted = MarketSelector.select(manifest, lat, lng).map { it.id }.toSet()
        return wanted.isNotEmpty() && wanted != syncedMarkets
    }

    private suspend fun refreshLegacy() {
        val feed = api.fetchFeed()
        val entities = feed.events.mapNotNull { it.toEntity(feed.market, feed.country) }
        if (entities.isNotEmpty()) {
            // Names must be captured while the old event rows still exist —
            // after the replace, an orphaned favorite is just an id.
            val savedBefore = dao.favorites().first()
            dao.replaceFeed(entities)
            if (dao.purgeOrphanFavorites() > 0) {
                val newIds = entities.mapTo(HashSet()) { it.id }
                recordPurgedSaved(savedBefore.filter { it.id !in newIds }
                    .map { it.name })
            }
            markSynced(setOf(Market.LAUNCH_ID))
        }
    }

    private fun markSynced(markets: Set<String>) {
        val now = System.currentTimeMillis()
        prefs.edit {
            putLong(KEY_LAST_SYNC, now)
            putString(KEY_SYNCED_MARKETS, markets.joinToString(","))
        }
        _lastSyncEpochMs.value = now
    }

    private fun recordPurgedSaved(names: List<String>) {
        if (names.isEmpty()) return
        val merged = (_purgedSavedNames.value + names).distinct().takeLast(10)
        prefs.edit { putString(KEY_PURGED_SAVED, merged.joinToString(PURGE_SEP)) }
        _purgedSavedNames.value = merged
    }

    private companion object {
        const val KEY_LAST_SYNC = "last_sync_epoch_ms"
        const val KEY_SYNCED_MARKETS = "synced_markets"
        const val KEY_PURGED_SAVED = "purged_saved_names"
        const val PURGE_SEP = "\u0001"   // never appears in an event name
    }
}
