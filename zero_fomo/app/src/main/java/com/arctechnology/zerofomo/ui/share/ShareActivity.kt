package com.arctechnology.zerofomo.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.arctechnology.zerofomo.R
import com.arctechnology.zerofomo.data.inbox.InboxRepository
import com.arctechnology.zerofomo.data.location.UserLocationStore
import com.arctechnology.zerofomo.ui.theme.BrandMark
import com.arctechnology.zerofomo.ui.theme.LocalCountryAccentsOnDark
import com.arctechnology.zerofomo.ui.theme.ZeroFomoTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Share to 0 FOMO": the compliant answer to WhatsApp, Instagram, TikTok and
 * Facebook (docs/GLOBAL_DESIGN.md §3). A user forwards a flyer, a caption or
 * a link; we queue it, upload it to the inbox, and a reviewer publishes it.
 * Nothing is read from those apps: only what the user chose to send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject lateinit var inbox: InboxRepository
    @Inject lateinit var locationStore: UserLocationStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_SEND) { finish(); return }
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val imageUri: Uri? = if (intent.type?.startsWith("image/") == true)
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) else null
        val hint = sourceHint()
        if (sharedText.isBlank() && imageUri == null) { finish(); return }
        val (text, url) = splitUrl(sharedText)

        setContent {
            val ul by locationStore.state.collectAsStateWithLifecycle()
            ZeroFomoTheme(country = ul?.country) {
                var note by remember { mutableStateOf("") }
                var sending by remember { mutableStateOf(false) }
                ModalBottomSheet(onDismissRequest = { finish() }) {
                    Column(
                        Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val onDark = LocalCountryAccentsOnDark.current
                            BrandMark(Modifier.size(26.dp), armA = onDark.armA, armB = onDark.armB,
                                bar = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.share_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                        }
                        val kindLabel = stringResource(
                            if (imageUri != null) R.string.share_kind_flyer
                            else if (url.isNotBlank()) R.string.share_kind_link
                            else R.string.share_kind_post)
                        val fromHint = if (hint.isNotBlank())
                            stringResource(R.string.share_from_hint, hint) else ""
                        val forAreaPrefix = stringResource(R.string.share_for_area_prefix)
                        val defaultArea = stringResource(R.string.share_default_area)
                        Text(
                            buildString {
                                append(kindLabel)
                                if (fromHint.isNotBlank()) append(" ").append(fromHint)
                                append(" ").append(forAreaPrefix).append(" ")
                                append(ul?.label ?: defaultArea)
                                ul?.country?.let { append(" ${it.flagEmoji}") }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (text.isNotBlank()) Text(text, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        if (url.isNotBlank()) Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                        OutlinedTextField(
                            value = note, onValueChange = { note = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.share_note_placeholder)) },
                            minLines = 2,
                        )
                        Text(stringResource(R.string.share_reviewer_disclaimer),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { finish() }) {
                                Text(stringResource(R.string.share_cancel_button))
                            }
                            Spacer(Modifier.width(8.dp))
                            val sentToast = stringResource(R.string.share_sent_toast)
                            Button(enabled = !sending, onClick = {
                                sending = true
                                lifecycleScope.launch {
                                    val body = listOf(text, note.trim()).filter { it.isNotBlank() }
                                        .joinToString("\n\n")
                                    inbox.enqueue(body, url, imageUri, hint)
                                    Toast.makeText(this@ShareActivity,
                                        sentToast, Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }) {
                                Text(stringResource(
                                    if (sending) R.string.share_sending_button
                                    else R.string.share_send_button))
                            }
                        }
                    }
                }
            }
        }
    }

    /** Which app the share came from, for the reviewer's context only. */
    private fun sourceHint(): String {
        val pkg = if (Build.VERSION.SDK_INT >= 22) referrer?.host.orEmpty() else ""
        return KNOWN_SOURCES.entries.firstOrNull { pkg.startsWith(it.key) }?.value
            ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun splitUrl(shared: String): Pair<String, String> {
        val url = URL_RX.find(shared)?.value.orEmpty()
        val text = shared.replace(url, "").trim()
        return text to url
    }

    private companion object {
        val URL_RX = Regex("""https?://\S+""")
        val KNOWN_SOURCES = mapOf(
            "com.whatsapp" to "WhatsApp", "com.instagram" to "Instagram",
            "com.facebook" to "Facebook", "com.zhiliaoapp.musically" to "TikTok",
            "org.telegram" to "Telegram", "com.twitter" to "X", "com.snapchat" to "Snapchat",
            "com.google.android.gm" to "Gmail", "com.android.chrome" to "Chrome",
        )
    }
}
