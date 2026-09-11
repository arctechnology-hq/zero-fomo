package com.arctechnology.zerofomo.ui.feed

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arctechnology.zerofomo.model.BahamianIsland
import com.arctechnology.zerofomo.model.DateRangeFilter
import com.arctechnology.zerofomo.model.Event
import com.arctechnology.zerofomo.model.EventCategory
import com.arctechnology.zerofomo.model.LocationFilter
import com.arctechnology.zerofomo.ui.theme.AquaDeep
import com.arctechnology.zerofomo.ui.theme.accent
import com.arctechnology.zerofomo.ui.theme.emoji
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onEventClick: (String) -> Unit,
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }
    var showLocationSearch by remember { mutableStateOf(false) }

    LaunchedEffect(state.syncError) {
        state.syncError?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose { }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            FeedHeader(
                lastSyncEpochMs = state.lastSyncEpochMs,
                onSearchClick = { showLocationSearch = true },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            FilterBar(
                filters = state.filters,
                onDateChipClick = { preset ->
                    if (preset is DateRangeFilter.Custom) showDatePicker = true
                    else viewModel.setDateRange(preset)
                },
                onCustomRangeClick = { showDatePicker = true },
                onIslandSelected = viewModel::setIsland,
                onCategoryToggle = viewModel::toggleCategory,
                onClearKeyword = { viewModel.setKeyword("") },
            )
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.events.isEmpty() && !state.isRefreshing) {
                    EmptyState(onClearFilters = viewModel::clearFilters)
                } else {
                    EventList(
                        events = state.events,
                        onEventClick = onEventClick,
                        onToggleFavorite = viewModel::toggleFavorite,
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        CustomDateRangeSheet(
            onDismiss = { showDatePicker = false },
            onConfirm = { start, end ->
                viewModel.setDateRange(DateRangeFilter.Custom(start, end))
                showDatePicker = false
            },
        )
    }
    if (showLocationSearch) {
        SearchSheet(
            currentKeyword = state.filters.keyword,
            onDismiss = { showLocationSearch = false },
            onApply = { keyword, location ->
                viewModel.setKeyword(keyword)
                if (location.isNotBlank()) viewModel.searchLocation(location)
                showLocationSearch = false
            },
        )
    }
}

/** Branded gradient masthead: wordmark, freshness, and search — the gradient
 *  runs under the status bar for a full edge-to-edge header. */
@Composable
private fun FeedHeader(lastSyncEpochMs: Long, onSearchClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(AquaDeep, MaterialTheme.colorScheme.primary))),
    ) {
        Row(
            Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "0 FOMO",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                )
                Text(
                    freshnessLabel(lastSyncEpochMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            IconButton(onClick = onSearchClick) {
                Icon(Icons.Default.Search, contentDescription = "Search",
                    tint = Color.White)
            }
        }
    }
}

/** Re-evaluates each minute so "Updated 2 min ago" doesn't freeze while the
 *  screen stays open. */
@Composable
private fun freshnessLabel(lastSyncEpochMs: Long): String {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastSyncEpochMs) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000)
        }
    }
    if (lastSyncEpochMs == 0L) return "Not synced yet — pull to refresh"
    val mins = (now - lastSyncEpochMs) / 60_000
    return when {
        mins < 1 -> "Updated just now"
        mins < 60 -> "Updated $mins min ago"
        mins < 60 * 24 -> "Updated ${mins / 60}h ago"
        else -> "Updated ${mins / (60 * 24)}d ago"
    }
}

@Composable
private fun FilterBar(
    filters: FilterState,
    onDateChipClick: (DateRangeFilter) -> Unit,
    onCustomRangeClick: () -> Unit,
    onIslandSelected: (BahamianIsland?) -> Unit,
    onCategoryToggle: (EventCategory) -> Unit,
    onClearKeyword: () -> Unit,
) {
    Column {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(DateRangeFilter.presets) { preset ->
                FilterChip(
                    selected = filters.dateRange::class == preset::class,
                    onClick = { onDateChipClick(preset) },
                    label = { Text(preset.label) },
                )
            }
            item {
                FilterChip(
                    selected = filters.dateRange is DateRangeFilter.Custom,
                    onClick = onCustomRangeClick,
                    label = {
                        Text((filters.dateRange as? DateRangeFilter.Custom)?.label
                            ?: "Custom…")
                    },
                )
            }
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = filters.location is LocationFilter.Everywhere,
                    onClick = { onIslandSelected(null) },
                    label = { Text("All islands") },
                )
            }
            items(BahamianIsland.entries.toList()) { island ->
                FilterChip(
                    selected = (filters.location as? LocationFilter.IslandTag)
                        ?.island == island,
                    onClick = { onIslandSelected(island) },
                    label = { Text(island.displayName) },
                )
            }
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(EventCategory.entries.filter { it != EventCategory.UNKNOWN }) { cat ->
                val selected = cat in filters.categories
                FilterChip(
                    selected = selected,
                    onClick = { onCategoryToggle(cat) },
                    label = { Text(cat.label) },
                    leadingIcon = { Text(cat.emoji, fontSize = 14.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = cat.accent.copy(alpha = 0.18f),
                        selectedLabelColor = cat.accent,
                    ),
                )
            }
        }
        if (filters.keyword.isNotBlank()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                InputChip(
                    selected = true,
                    onClick = onClearKeyword,
                    label = { Text("“${filters.keyword}”") },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = "Clear search",
                            modifier = Modifier.size(16.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun EventList(
    events: List<Event>,
    onEventClick: (String) -> Unit,
    onToggleFavorite: (Event) -> Unit,
) {
    val grouped = remember(events) { events.groupBy { it.date } }
    val today = remember(events) { LocalDate.now() }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (date, dayEvents) ->
            item(key = "header-$date") {
                DayHeader(date, today, dayEvents.size)
            }
            items(dayEvents, key = { it.id }) { event ->
                EventCard(event, onClick = { onEventClick(event.id) },
                    onToggleFavorite = { onToggleFavorite(event) })
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate, count: Int) {
    val fmt = remember { DateTimeFormatter.ofPattern("EEEE, MMMM d") }
    val label = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(fmt)
    }
    Row(
        Modifier.padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(8.dp))
        Text(
            if (count == 1) "1 event" else "$count events",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
fun EventCard(
    event: Event,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(event.category.accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(event.category.emoji, fontSize = 21.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(event.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline)
                    Text(
                        event.venue.ifBlank { event.island?.displayName ?: "TBA" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    event.timeStart?.let {
                        InfoPill(
                            it.format(DateTimeFormatter.ofPattern("h:mm a")),
                            container = MaterialTheme.colorScheme.surfaceVariant,
                            content = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (event.priceLabel.isNotBlank()) {
                        val free = event.priceLabel == "Free"
                        InfoPill(
                            event.priceLabel,
                            container = if (free)
                                MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                            content = if (free)
                                MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (event.isSaved) Icons.Default.Favorite
                    else Icons.Default.FavoriteBorder,
                    contentDescription = if (event.isSaved) "Unsave" else "Save",
                    tint = if (event.isSaved) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content,
        shape = RoundedCornerShape(50)) {
        Text(text,
            Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EmptyState(onClearFilters: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🏝️", style = MaterialTheme.typography.displayMedium)
            Text("No events match these filters",
                style = MaterialTheme.typography.titleMedium)
            Text("Try widening the date range or island",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClearFilters) { Text("Clear all filters") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDateRangeSheet(
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val pickerState = rememberDateRangePickerState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            DateRangePicker(state = pickerState, modifier = Modifier.weight(1f, false))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = {
                        val start = pickerState.selectedStartDateMillis
                        val end = pickerState.selectedEndDateMillis
                        if (start != null && end != null) {
                            onConfirm(toLocalDate(start), toLocalDate(end))
                        }
                    },
                ) { Text("Apply") }
            }
        }
    }
}

private fun toLocalDate(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(
    currentKeyword: String,
    onDismiss: () -> Unit,
    onApply: (keyword: String, location: String) -> Unit,
) {
    var keyword by remember { mutableStateOf(currentKeyword) }
    var location by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("What you lookin' for?", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Keyword — reggae, brunch, junkanoo…") },
                singleLine = true,
            )
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Location — island, zip code, or region") },
                singleLine = true,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(BahamianIsland.entries.toList()) { island ->
                    AssistChip(
                        onClick = { location = island.displayName },
                        label = { Text(island.displayName) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = { onApply(keyword, location) },
                ) { Text("Apply") }
            }
        }
    }
}
