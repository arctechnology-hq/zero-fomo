package com.arctechnology.zerofomo.data.network

import com.arctechnology.zerofomo.data.db.EventEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** The static feed published by comprehensive_bahamas_scraper.py --json. */
interface EventsApi {
    @GET("events.json")
    suspend fun fetchFeed(): EventsFeedDto
}

@Serializable
data class EventsFeedDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("generated_at") val generatedAt: String = "",
    val events: List<EventDto> = emptyList(),
)

@Serializable
data class EventDto(
    val id: String,
    val name: String,
    val date: String,                                    // ISO yyyy-MM-dd
    @SerialName("time_start") val timeStart: String? = null,   // "HH:mm"
    @SerialName("time_end") val timeEnd: String? = null,
    val venue: String = "",
    val island: String? = null,                          // BahamianIsland.name
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("price_min") val priceMin: Double? = null,
    @SerialName("price_max") val priceMax: Double? = null,
    @SerialName("is_free") val isFree: Boolean = false,
    val category: String = "GENERAL",
    @SerialName("source_url") val sourceUrl: String = "",
    val description: String = "",
    // schema_version 2 (optional; v1 feeds default to the launch market)
    val country: String? = null,                         // ISO 3166-1 alpha-2
    val market: String? = null,                          // e.g. "bs-nassau"
    val tz: String? = null,                              // IANA zone
)

/** Defensive mapping: a single malformed feed row must never poison a sync,
 *  so unparseable dates/times degrade instead of throwing. */
fun EventDto.toEntity(): EventEntity? {
    val localDate = try {
        LocalDate.parse(date)
    } catch (_: DateTimeParseException) {
        return null   // no date, no listing — the feed's Needs Review lane owns it
    }
    fun safeTime(t: String?): String? = try {
        t?.let { LocalTime.parse(it) }?.toString()
    } catch (_: DateTimeParseException) {
        null
    }
    return EventEntity(
        id = id,
        name = name,
        dateIso = localDate.toString(),
        epochDay = localDate.toEpochDay(),
        timeStart = safeTime(timeStart),
        timeEnd = safeTime(timeEnd),
        venue = venue,
        islandTag = island?.uppercase(),
        lat = lat,
        lng = lng,
        priceMin = priceMin,
        priceMax = priceMax,
        isFree = isFree,
        category = category,
        sourceUrl = sourceUrl,
        description = description,
        countryCode = country?.trim()?.takeIf { it.length == 2 }?.uppercase() ?: "BS",
        market = market?.trim()?.ifBlank { null },
    )
}
