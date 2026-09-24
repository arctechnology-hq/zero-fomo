package com.arctechnology.zerofomo.ui.saved

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arctechnology.zerofomo.ui.theme.AquaDeep
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arctechnology.zerofomo.data.EventRepository
import com.arctechnology.zerofomo.data.db.SubmissionEntity
import com.arctechnology.zerofomo.data.inbox.InboxRepository
import com.arctechnology.zerofomo.model.Event
import com.arctechnology.zerofomo.ui.feed.EventCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SavedViewModel @Inject constructor(
    private val repository: EventRepository,
    private val inbox: InboxRepository,
) : ViewModel() {
    val saved: StateFlow<List<Event>> = repository.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "Share to 0 FOMO" submissions from this phone, newest first, so the
     *  user can see a forwarded flyer actually left the device. */
    val submissions: StateFlow<List<SubmissionEntity>> = inbox.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Saved events purged because they left the feed — drives the notice. */
    val purgedNames: StateFlow<List<String>> = repository.purgedSavedNames

    fun dismissPurgedNotice() = repository.dismissPurgedNotice()

    fun toggleFavorite(event: Event) {
        viewModelScope.launch { repository.toggleFavorite(event) }
    }

    fun retrySubmission(id: String) { viewModelScope.launch { inbox.retry(id) } }

    fun removeSubmission(id: String) { viewModelScope.launch { inbox.remove(id) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    onEventClick: (String) -> Unit,
    viewModel: SavedViewModel = hiltViewModel(),
) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val submissions by viewModel.submissions.collectAsStateWithLifecycle()
    val purged by viewModel.purgedNames.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(AquaDeep, MaterialTheme.colorScheme.primary))),
        ) {
            Text(
                "Saved 💛",
                Modifier
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 6.dp, bottom = 12.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = Color.White,
            )
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (purged.isNotEmpty()) {
                PurgedNotice(purged, onDismiss = viewModel::dismissPurgedNotice)
            }
            if (saved.isEmpty() && submissions.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💛", style = MaterialTheme.typography.displayMedium)
                        Text("Nothing saved yet",
                            style = MaterialTheme.typography.titleMedium)
                        Text("Tap the heart on any event to keep it here — even offline",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (submissions.isNotEmpty()) {
                        item(key = "forwarded-header") {
                            SectionHeader(
                                "Forwarded to 0 FOMO",
                                "Posts you shared are reviewed before they reach the feed",
                            )
                        }
                        items(submissions, key = { "sub-${it.id}" }) { s ->
                            ForwardedRow(
                                s,
                                onRetry = { viewModel.retrySubmission(s.id) },
                                onRemove = { viewModel.removeSubmission(s.id) },
                            )
                        }
                        item(key = "saved-header") {
                            SectionHeader(
                                "Saved events",
                                if (saved.isEmpty()) "Tap the heart on any event to keep it here" else null,
                            )
                        }
                    }
                    items(saved, key = { it.id }) { event ->
                        EventCard(event,
                            onClick = { onEventClick(event.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(event) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** One "Share to 0 FOMO" submission: what was shared, where from, and whether
 *  it has left the phone. Failed text/link shares can be retried; a failed
 *  photo cannot (its file is deleted with the failure) so it only offers
 *  removal — share the flyer again instead. */
@Composable
private fun ForwardedRow(s: SubmissionEntity, onRetry: () -> Unit, onRemove: () -> Unit) {
    val glyph = when (s.kind) { "image" -> "📷"; "url" -> "🔗"; else -> "📝" }
    val title = when {
        s.text.isNotBlank() -> s.text.lineSequence().first { it.isNotBlank() }.take(90)
        s.url.isNotBlank() -> s.url
        else -> "Photo"
    }
    val from = s.sourceHint.ifBlank { "another app" }
    val failed = s.status == SubmissionEntity.FAILED
    val statusText = when (s.status) {
        SubmissionEntity.SENT -> "Sent · in review"
        SubmissionEntity.FAILED -> "Couldn't send" + (s.error?.let { " · $it" } ?: "")
        else -> if (s.attempts > 0) "Retrying when online…" else "Queued"
    }
    val statusColor = when (s.status) {
        SubmissionEntity.SENT -> MaterialTheme.colorScheme.tertiary
        SubmissionEntity.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(glyph, fontSize = 18.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("from $from · ${ago(s.createdAtEpochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                Text(statusText, style = MaterialTheme.typography.labelMedium,
                    color = statusColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (failed) {
                if (s.kind != "image") {
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "Remove")
                }
            }
        }
    }
}

private fun ago(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val mins = ((now - epochMs) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

/** Product decision 2026-08-17: saved events that drop off the feed are
 *  purged, and the user is told — never a silent disappearance. */
@Composable
private fun PurgedNotice(names: List<String>, onDismiss: () -> Unit) {
    val shown = names.take(3).joinToString(", ")
    val more = names.size - 3
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (names.size == 1) "A saved event is no longer listed"
                    else "${names.size} saved events are no longer listed",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (more > 0) "$shown and $more more were removed from Saved."
                    else "$shown ${if (names.size == 1) "was" else "were"} removed from Saved.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss notice")
            }
        }
    }
}
