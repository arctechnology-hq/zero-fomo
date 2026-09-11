package com.arctechnology.zerofomo.data

import com.arctechnology.zerofomo.data.db.EventDao
import com.arctechnology.zerofomo.data.db.FavoriteEntity
import com.arctechnology.zerofomo.data.db.toDomain
import com.arctechnology.zerofomo.data.network.EventsApi
import com.arctechnology.zerofomo.data.network.toEntity
import com.arctechnology.zerofomo.model.BahamianIsland
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

            is LocationFilter.Bounds -> {
                // v1 dataset is island-tagged but rarely has raw coordinates,
                // so a bounding box matches any island whose box intersects
                // it — the island centroid stands in for missing lat/lng.
                val islands = BahamianIsland.entries
                    .filter { it.bounds.intersects(location.box) }
                    .map { it.name }
                if (islands.isNotEmpty()) dao.byIslands(islands, from, to)
                else dao.byBounds(
                    location.box.minLat, location.box.maxLat,
                    location.box.minLng, location.box.maxLng, from, to)
            }

            LocationFilter.Everywhere -> dao.allBetween(from, to)
        }

        return combine(entities, dao.favoriteIds()) { list, favIds ->
            val favs = favIds.toSet()
            list.map { it.toDomain(isSaved = it.id in favs) }
        }
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

    /** Pull the feed and atomically replace the cache. Throws on network
     *  failure — callers decide whether that is a toast or a silent retry. */
    suspend fun refresh() {
        val feed = api.fetchFeed()
        val entities = feed.events.mapNotNull { it.toEntity() }
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
            val now = System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST_SYNC, now) }
            _lastSyncEpochMs.value = now
        }
    }

    private fun recordPurgedSaved(names: List<String>) {
        if (names.isEmpty()) return
        val merged = (_purgedSavedNames.value + names).distinct().takeLast(10)
        prefs.edit { putString(KEY_PURGED_SAVED, merged.joinToString(PURGE_SEP)) }
        _purgedSavedNames.value = merged
    }

    private companion object {
        const val KEY_LAST_SYNC = "last_sync_epoch_ms"
        const val KEY_PURGED_SAVED = "purged_saved_names"
        const val PURGE_SEP = "\u0001"   // never appears in an event name
    }
}
