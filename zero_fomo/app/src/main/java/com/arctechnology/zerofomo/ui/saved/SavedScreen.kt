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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arctechnology.zerofomo.ui.theme.AquaDeep
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arctechnology.zerofomo.data.EventRepository
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
) : ViewModel() {
    val saved: StateFlow<List<Event>> = repository.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Saved events purged because they left the feed — drives the notice. */
    val purgedNames: StateFlow<List<String>> = repository.purgedSavedNames

    fun dismissPurgedNotice() = repository.dismissPurgedNotice()

    fun toggleFavorite(event: Event) {
        viewModelScope.launch { repository.toggleFavorite(event) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    onEventClick: (String) -> Unit,
    viewModel: SavedViewModel = hiltViewModel(),
) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
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
            if (saved.isEmpty()) {
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
