package com.arctechnology.zerofomo.data.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arctechnology.zerofomo.R
import com.arctechnology.zerofomo.data.db.EventDao
import com.arctechnology.zerofomo.data.db.toDomain
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Daily check: any SAVED event happening tomorrow fires a reminder
 * notification that deep-links straight to the event page. Runs entirely
 * on-device against the Room cache — works offline, no accounts needed.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: EventDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext,
                Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return Result.success()   // user declined notifications; not an error

        val tomorrow = LocalDate.now().plusDays(1)
        val events = dao.favoritesOnDay(tomorrow.toEpochDay()).map { it.toDomain(true) }
        if (events.isEmpty()) return Result.success()

        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = applicationContext.getString(R.string.reminder_channel_description)
            })

        val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
        events.forEach { event ->
            // Explicit to this app: the zerofomo:// scheme must never resolve
            // into another app's intent filter from a notification tap.
            val view = Intent(Intent.ACTION_VIEW,
                Uri.parse("zerofomo://event/${event.id}"))
                .setPackage(applicationContext.packageName)
            val tap = PendingIntent.getActivity(
                applicationContext, event.id.hashCode(), view,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val timeBit = event.timeStart?.let {
                applicationContext.getString(R.string.reminder_time_suffix_format, it.format(timeFmt))
            } ?: ""
            val venue = event.venue.ifBlank { applicationContext.getString(R.string.reminder_venue_fallback) }
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_zerofomo)
                .setContentTitle(applicationContext.getString(R.string.reminder_notification_title_format, event.name))
                .setContentText(applicationContext.getString(R.string.reminder_notification_body_format, venue, timeBit))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            nm.notify(event.id.hashCode(), notification)
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "event-reminders"
        private const val UNIQUE_NAME = "saved-event-reminders"

        /** Reminders belong in the early evening, not whatever hour the app
         *  first launched — anchor the first run to the next 6 PM local. */
        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var firstRun = now.toLocalDate().atTime(18, 0)
            if (!firstRun.isAfter(now)) firstRun = firstRun.plusDays(1)
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(
                    Duration.between(now, firstRun).toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
