package com.arctechnology.zerofomo.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arctechnology.zerofomo.data.EventRepository
import com.arctechnology.zerofomo.model.Event
import com.arctechnology.zerofomo.ui.calendar.CalendarExporter
import com.arctechnology.zerofomo.ui.theme.Gold
import com.arctechnology.zerofomo.ui.theme.accent
import com.arctechnology.zerofomo.ui.theme.emoji
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: EventRepository,
) : ViewModel() {
    private val eventId: String = checkNotNull(savedStateHandle["eventId"])

    val event: StateFlow<Event?> = repository.observeEvent(eventId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleFavorite() {
        event.value?.let { viewModelScope.launch { repository.toggleFavorite(it) } }
    }
}

@Composable
fun DetailScreen(
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val event by viewModel.event.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCalendarSheet by remember { mutableStateOf(false) }

    val ev = event ?: run {
        Box(Modifier.fillMaxSize())   // Room row still streaming in
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        HeroHeader(
            event = ev,
            onBack = onBack,
            onShare = { context.shareEvent(ev) },
            onToggleFavorite = viewModel::toggleFavorite,
        )
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ev.timeStart?.let { start ->
                val fmt = DateTimeFormatter.ofPattern("h:mm a")
                val label = ev.timeEnd?.let { "${start.format(fmt)} – ${it.format(fmt)}" }
                    ?: start.format(fmt)
                InfoRow(ev, Icons.Default.Schedule, label)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoRow(ev, Icons.Default.LocationOn,
                    listOfNotNull(
                        ev.venue.ifBlank { null },
                        ev.island?.displayName).joinToString(" · ")
                        .ifBlank { "Location TBA" })
                if (ev.venue.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        val q = Uri.encode("${ev.venue}, Bahamas")
                        context.startSafely(
                            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
                    }) { Text("Directions") }
                }
            }
            if (ev.priceLabel.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        if (ev.priceLabel == "Free") "Free entry" else ev.priceLabel,
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCalendarSheet = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add to Calendar")
                }
                if (ev.sourceUrl.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            context.startSafely(Intent(Intent.ACTION_VIEW,
                                Uri.parse(ev.sourceUrl)))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Get Tickets") }
                }
            }

            if (ev.description.isNotBlank()) {
                Text("About", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(ev.description, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showCalendarSheet) {
        CalendarTargetSheet(
            onDismiss = { showCalendarSheet = false },
            onTarget = { target ->
                showCalendarSheet = false
                val intent = when (target) {
                    CalendarTarget.GOOGLE_DEVICE -> CalendarExporter.systemInsertIntent(ev)
                    CalendarTarget.OUTLOOK -> CalendarExporter.outlookIntent(ev)
                    CalendarTarget.ICAL_FILE ->
                        Intent.createChooser(
                            CalendarExporter.icsShareIntent(context, ev),
                            "Share calendar file")
                }
                context.startSafely(intent)
            },
        )
    }
}

/** Full-bleed banner in the event's category color: nav actions, category
 *  chip, title, and date all live on the gradient. Light accents (comedy
 *  amber, fair olive) flip to dark ink for contrast. */
@Composable
private fun HeroHeader(
    event: Event,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val accent = event.category.accent
    val onHero = if (accent.luminance() > 0.5f) Color(0xF0102022) else Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(accent, lerp(accent, Color.Black, 0.35f)))),
    ) {
        Column(Modifier.statusBarsPadding().padding(bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back", tint = onHero)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = onHero)
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (event.isSaved) Icons.Default.Favorite
                        else Icons.Default.FavoriteBorder,
                        contentDescription = if (event.isSaved) "Unsave" else "Save",
                        tint = if (event.isSaved) Gold else onHero,
                    )
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Surface(
                    color = onHero.copy(alpha = 0.16f),
                    contentColor = onHero,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        "${event.category.emoji}  ${event.category.label}",
                        Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(event.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = onHero)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null,
                        Modifier.size(16.dp), tint = onHero.copy(alpha = 0.85f))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        event.date.format(
                            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")),
                        style = MaterialTheme.typography.bodyLarge,
                        color = onHero.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(event: Event, icon: androidx.compose.ui.graphics.vector.ImageVector,
                    text: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(34.dp)
                .background(event.category.accent.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp),
                tint = event.category.accent)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun Context.shareEvent(ev: Event) {
    val text = buildString {
        append(ev.name)
        append(" — ${ev.date}")
        ev.timeStart?.let { append(" $it") }
        if (ev.venue.isNotBlank()) append(" @ ${ev.venue}")
        if (ev.sourceUrl.isNotBlank()) append("\n${ev.sourceUrl}")
    }
    startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share event"))
}

enum class CalendarTarget { GOOGLE_DEVICE, OUTLOOK, ICAL_FILE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarTargetSheet(
    onDismiss: () -> Unit,
    onTarget: (CalendarTarget) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Add to calendar",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium)
            ListItem(
                headlineContent = { Text("Google / device calendar") },
                supportingContent = { Text("Opens your calendar app's event editor") },
                modifier = Modifier.clickableTarget { onTarget(CalendarTarget.GOOGLE_DEVICE) },
            )
            ListItem(
                headlineContent = { Text("Outlook") },
                supportingContent = { Text("Opens Outlook's new-event page") },
                modifier = Modifier.clickableTarget { onTarget(CalendarTarget.OUTLOOK) },
            )
            ListItem(
                headlineContent = { Text("iCal file (.ics)") },
                supportingContent = { Text("Share a universal calendar file") },
                modifier = Modifier.clickableTarget { onTarget(CalendarTarget.ICAL_FILE) },
            )
        }
    }
}

private fun Modifier.clickableTarget(onClick: () -> Unit): Modifier =
    fillMaxWidth().clickable(onClick = onClick)

/** Every outbound intent (ticket links, Outlook deep links, geo URIs) can
 *  hit a device with no matching app — never let that crash the screen. */
private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app on this phone can open that",
            Toast.LENGTH_SHORT).show()
    }
}
