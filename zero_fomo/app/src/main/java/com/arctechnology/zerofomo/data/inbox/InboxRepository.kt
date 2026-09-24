package com.arctechnology.zerofomo.data.inbox

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import com.arctechnology.zerofomo.BuildConfig
import com.arctechnology.zerofomo.data.db.SubmissionDao
import com.arctechnology.zerofomo.data.db.SubmissionEntity
import com.arctechnology.zerofomo.data.sync.UploadWorker
import com.arctechnology.zerofomo.model.Market
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Share to 0 FOMO": queue on device, upload when online. Images are
 * re-encoded to <= 1600 px JPEG in app-private storage and deleted once the
 * inbox has them, so nothing personal lingers on the phone or in transit
 * beyond the post the user chose to forward.
 */
@Singleton
class InboxRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SubmissionDao,
    private val api: InboxApi,
    private val prefs: SharedPreferences,
) {
    val recent: Flow<List<SubmissionEntity>> get() = dao.recent()

    /** Nearest synced market from the last feed refresh; the launch market
     *  when nothing has synced yet. */
    fun currentMarketId(): String =
        prefs.getString("synced_markets", "")!!.split(',').firstOrNull { it.isNotBlank() }
            ?: Market.LAUNCH_ID

    /** Anonymous, per-install id for rate limiting (no account, no PII). */
    private fun deviceId(): String {
        prefs.getString(KEY_DEVICE, null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(24)
        prefs.edit { putString(KEY_DEVICE, id) }
        return id
    }

    suspend fun enqueue(
        text: String, url: String, imageUri: Uri?, sourceHint: String,
    ): SubmissionEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val imagePath = imageUri?.let { copyDownscaled(it, id) }
        val kind = when {
            imagePath != null -> "image"
            url.isNotBlank() && text.isBlank() -> "url"
            else -> "text"
        }
        val entity = SubmissionEntity(
            id = id, market = currentMarketId(), kind = kind, text = text, url = url,
            imagePath = imagePath, sourceHint = sourceHint,
            createdAtEpochMs = System.currentTimeMillis(), status = SubmissionEntity.QUEUED,
        )
        dao.insert(entity)
        UploadWorker.enqueue(context)
        entity
    }

    /** Put a FAILED submission back in the queue. A failed photo cannot be
     *  re-sent: its file was deleted with the failure (nothing lingers), so the
     *  Saved tab only offers Retry for text and link submissions. */
    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        val s = dao.byId(id) ?: return@withContext
        val photoGone = s.kind == "image" && s.imagePath?.let { File(it).exists() } != true
        if (photoGone) {
            dao.update(s.id, SubmissionEntity.FAILED, s.attempts, null,
                "The photo is no longer on this phone — share it again")
        } else {
            dao.update(s.id, SubmissionEntity.QUEUED, 0, null, null)
            UploadWorker.enqueue(context)
        }
    }

    /** Forget a submission on this device (the inbox copy, if any, is unaffected). */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        dao.byId(id)?.imagePath?.let { File(it).delete() }
        dao.delete(id)
    }

    /** Called by [UploadWorker]. Returns true when nothing is left queued. */
    suspend fun uploadPending(): Boolean = withContext(Dispatchers.IO) {
        var allDone = true
        for (s in dao.queued()) {
            val image = s.imagePath?.let { File(it) }?.takeIf { it.exists() }
            val body = SubmissionDto(
                market = s.market, kind = s.kind, device = deviceId(),
                text = s.text, url = s.url, sourceHint = s.sourceHint,
                imageBase64 = image?.let { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) },
                imageType = image?.let { "image/jpeg" },
                appVersion = BuildConfig.VERSION_NAME,
            )
            try {
                val resp = api.submit(body, BuildConfig.INBOX_TOKEN)
                if (resp.ok) {
                    dao.update(s.id, SubmissionEntity.SENT, s.attempts + 1, resp.id, null)
                    image?.delete()
                } else {
                    // A 4xx-style rejection will not fix itself: park it.
                    dao.update(s.id, SubmissionEntity.FAILED, s.attempts + 1, null, resp.error)
                    image?.delete()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: retrofit2.HttpException) {
                // Only a rejection of the payload itself is final. 404/5xx mean
                // the inbox is not (yet) reachable behind the host: keep trying.
                if (e.code() in PERMANENT_HTTP || s.attempts + 1 >= MAX_ATTEMPTS) {
                    dao.update(s.id, SubmissionEntity.FAILED, s.attempts + 1, null, "HTTP ${e.code()}")
                    image?.delete()
                } else {
                    dao.update(s.id, SubmissionEntity.QUEUED, s.attempts + 1, null, "HTTP ${e.code()}")
                    allDone = false
                }
            } catch (e: Exception) {
                if (s.attempts + 1 >= MAX_ATTEMPTS) {
                    dao.update(s.id, SubmissionEntity.FAILED, s.attempts + 1, null, e.message)
                    image?.delete()
                } else {
                    dao.update(s.id, SubmissionEntity.QUEUED, s.attempts + 1, null, e.message)
                    allDone = false
                }
            }
        }
        dao.purgeSentBefore(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
        allDone
    }

    private fun copyDownscaled(uri: Uri, id: String): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_PX * 2 || bounds.outHeight / sample > MAX_PX * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val scale = minOf(1f, MAX_PX.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(
            bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val dir = File(context.filesDir, "inbox").apply { mkdirs() }
        val out = File(dir, "$id.jpg")
        ByteArrayOutputStream().use { buf ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, buf)
            out.writeBytes(buf.toByteArray())
        }
        return out.absolutePath
    }

    private companion object {
        const val KEY_DEVICE = "inbox_device_id"
        const val MAX_PX = 1600
        const val MAX_ATTEMPTS = 8
        val PERMANENT_HTTP = setOf(400, 401, 403, 413, 415, 422)
    }
}
