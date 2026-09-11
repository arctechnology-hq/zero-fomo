package com.arctechnology.zerofomo.ui.calendar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.FileProvider
import com.arctechnology.zerofomo.model.Event
import android.content.ClipData
import java.io.File
import java.net.URLEncoder
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Interoperable calendar export utility. Three user-facing targets, one
 * event model:
 *
 *  1. Google Calendar / any device calendar — Intent.ACTION_INSERT against
 *     CalendarProvider's insert sheet (zero permissions, system-native UI).
 *  2. Outlook (app or web) — deep link into Outlook's compose-event view.
 *  3. iCal (.ics) — RFC 5545 file via FileProvider share; the universal
 *     answer for Apple devices and desktop Outlook.
 *
 * Events without a start time export as all-day entries.
 */
object CalendarExporter {

    private val NASSAU: ZoneId = ZoneId.of("America/Nassau")
    private val UTC_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val ISO_LOCAL: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private fun startOf(event: Event): ZonedDateTime =
        event.date.atTime(event.timeStart ?: LocalTime.of(0, 0)).atZone(NASSAU)

    private fun endOf(event: Event): ZonedDateTime {
        val start = startOf(event)
        val end = event.timeEnd?.let { end ->
            // End times past midnight ("8 PM – 1 AM") roll to the next day.
            val candidate = event.date.atTime(end).atZone(NASSAU)
            if (candidate <= start) candidate.plusDays(1) else candidate
        }
        return end ?: start.plusHours(3)
    }

    private fun bodyOf(event: Event): String = buildString {
        if (event.description.isNotBlank()) appendLine(event.description).appendLine()
        if (event.priceLabel.isNotBlank()) appendLine("Price: ${event.priceLabel}")
        if (event.sourceUrl.isNotBlank()) appendLine("Tickets/info: ${event.sourceUrl}")
        appendLine("Added from 0 FOMO")
    }.trim()

    // ── 1. Google Calendar / device calendar via system insert sheet ───────

    fun systemInsertIntent(event: Event): Intent =
        Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, event.name)
            putExtra(CalendarContract.Events.EVENT_LOCATION, event.venue)
            putExtra(CalendarContract.Events.DESCRIPTION, bodyOf(event))
            putExtra(CalendarContract.Events.EVENT_TIMEZONE, NASSAU.id)
            if (event.timeStart == null) {
                // CalendarProvider reads all-day begin/end as UTC midnights;
                // Nassau-midnight epochs would land the entry a day off.
                val dayUtc = event.date.atStartOfDay(ZoneOffset.UTC)
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                    dayUtc.toInstant().toEpochMilli())
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                    dayUtc.plusDays(1).toInstant().toEpochMilli())
            } else {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                    startOf(event).toInstant().toEpochMilli())
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                    endOf(event).toInstant().toEpochMilli())
            }
        }

    // ── 2. Outlook deep link (work/school + personal variants) ─────────────

    fun outlookIntent(event: Event, personalAccount: Boolean = false): Intent {
        val host = if (personalAccount) "outlook.live.com" else "outlook.office.com"
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val url = buildString {
            append("https://").append(host)
            append("/calendar/0/deeplink/compose")
            append("?path=/calendar/action/compose&rru=addevent")
            append("&subject=").append(enc(event.name))
            if (event.timeStart == null) {
                append("&startdt=").append(event.date)
                append("&enddt=").append(event.date.plusDays(1))
                append("&allday=true")
            } else {
                append("&startdt=").append(enc(startOf(event).toLocalDateTime().format(ISO_LOCAL)))
                append("&enddt=").append(enc(endOf(event).toLocalDateTime().format(ISO_LOCAL)))
            }
            append("&location=").append(enc(event.venue))
            append("&body=").append(enc(bodyOf(event)))
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    // ── 3. Universal .ics export (RFC 5545) via FileProvider ───────────────

    fun icsShareIntent(context: Context, event: Event): Intent {
        val dir = File(context.cacheDir, "ics").apply { mkdirs() }
        val safeName = event.name.replace(Regex("[^A-Za-z0-9 ]"), "").take(40).trim()
        val file = File(dir, "${safeName.ifBlank { "event" }}.ics")
        file.writeText(buildIcs(event))

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, event.name)
            // ClipData carries the grant through Intent.createChooser;
            // the flag alone doesn't survive the chooser on all targets.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** RFC 5545 §3.1: content lines are CRLF-terminated and folded at 75
     *  octets, continuations prefixed with a space. Strict importers
     *  (desktop Outlook, Apple Calendar) are exactly who this export is for. */
    private fun fold(line: String): String {
        if (line.length <= 74) return line
        return buildString {
            append(line.take(74))
            var rest = line.drop(74)
            while (rest.isNotEmpty()) {
                append("\r\n ").append(rest.take(73))
                rest = rest.drop(73)
            }
        }
    }

    internal fun buildIcs(event: Event): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;")
            .replace(",", "\\,").replace("\n", "\\n")
        val nowUtc = ZonedDateTime.now(ZoneId.of("UTC"))
        val lines = buildList {
            add("BEGIN:VCALENDAR")
            add("VERSION:2.0")
            add("PRODID:-//0 FOMO//Events//EN")
            add("METHOD:PUBLISH")
            add("BEGIN:VEVENT")
            add("UID:${event.id}@0fomo.app")
            add("DTSTAMP:${nowUtc.format(UTC_STAMP)}")
            if (event.timeStart == null) {
                // RFC 5545 all-day: date values, DTEND exclusive.
                val basic = DateTimeFormatter.BASIC_ISO_DATE
                add("DTSTART;VALUE=DATE:${event.date.format(basic)}")
                add("DTEND;VALUE=DATE:${event.date.plusDays(1).format(basic)}")
            } else {
                val startUtc = startOf(event).withZoneSameInstant(ZoneId.of("UTC"))
                val endUtc = endOf(event).withZoneSameInstant(ZoneId.of("UTC"))
                add("DTSTART:${startUtc.format(UTC_STAMP)}")
                add("DTEND:${endUtc.format(UTC_STAMP)}")
            }
            add("SUMMARY:${esc(event.name)}")
            if (event.venue.isNotBlank()) add("LOCATION:${esc(event.venue)}")
            add("DESCRIPTION:${esc(bodyOf(event))}")
            if (event.sourceUrl.isNotBlank()) add("URL:${esc(event.sourceUrl)}")
            add("END:VEVENT")
            add("END:VCALENDAR")
        }
        return lines.joinToString("\r\n", postfix = "\r\n") { fold(it) }
    }
}
