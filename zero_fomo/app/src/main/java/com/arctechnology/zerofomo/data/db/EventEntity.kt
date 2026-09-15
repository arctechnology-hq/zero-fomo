package com.arctechnology.zerofomo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.arctechnology.zerofomo.model.BahamianIsland
import com.arctechnology.zerofomo.model.Event
import com.arctechnology.zerofomo.model.EventCategory
import java.time.LocalDate
import java.time.LocalTime

@Entity(
    tableName = "events",
    indices = [Index("epochDay"), Index("islandTag"), Index("category"), Index("countryCode")],
)
data class EventEntity(
    @PrimaryKey val id: String,
    val name: String,
    val dateIso: String,
    val epochDay: Long,
    val timeStart: String?,   // "HH:mm"
    val timeEnd: String?,
    val venue: String,
    val islandTag: String?,   // BahamianIsland.name, null = untagged
    val lat: Double?,
    val lng: Double?,
    val priceMin: Double?,
    val priceMax: Double?,
    val isFree: Boolean,
    val category: String,     // EventCategory.slug
    val sourceUrl: String,
    val description: String,
    val countryCode: String = "BS",   // ISO 3166-1 alpha-2 (feed schema v2)
    val market: String? = null,       // e.g. "bs-nassau" (feed schema v2)
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val eventId: String,
    val savedAtEpochMs: Long = System.currentTimeMillis(),
)

fun EventEntity.toDomain(isSaved: Boolean = false): Event = Event(
    id = id,
    name = name,
    date = LocalDate.ofEpochDay(epochDay),
    timeStart = timeStart?.let(LocalTime::parse),
    timeEnd = timeEnd?.let(LocalTime::parse),
    venue = venue,
    island = BahamianIsland.fromTag(islandTag),
    lat = lat,
    lng = lng,
    priceMin = priceMin,
    priceMax = priceMax,
    isFree = isFree,
    category = EventCategory.fromSlug(category),
    sourceUrl = sourceUrl,
    description = description,
    countryCode = countryCode,
    isSaved = isSaved,
)
