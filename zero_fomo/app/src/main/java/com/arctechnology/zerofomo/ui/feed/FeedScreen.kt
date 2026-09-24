package com.arctechnology.zerofomo.ui.feed

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arctechnology.zerofomo.data.location.UserLocation
import com.arctechnology.zerofomo.model.BahamianIsland
import com.arctechnology.zerofomo.model.Country
import com.arctechnology.zerofomo.model.DateRangeFilter
import com.arctechnology.zerofomo.model.Event
import com.arctechnology.zerofomo.model.EventCategory
import com.arctechnology.zerofomo.model.Geo
import com.arctechnology.zerofomo.model.LocationFilter
import com.arctechnology.zerofomo.model.Place
import com.arctechnology.zerofomo.ui.theme.AquaDeep
import com.arctechnology.zerofomo.ui.theme.BrandMark
import com.arctechnology.zerofomo.ui.theme.LocalCountryAccentsOnDark
import com.arctechnology.zerofomo.ui.theme.Sand
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
    var showKeywordSearch by remember { mutableStateOf(false) }
    var showLocationSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.syncError) {
        state.syncError?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }
    LaunchedEffect(state.locationMessage) {
        state.locationMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissLocationMessage()
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
                userLocation = state.userLocation,
                lastSyncEpochMs = state.lastSyncEpochMs,
                onLocationClick = { showLocationSheet = true },
                onSearchClick = { showKeywordSearch = true },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            FilterBar(
                filters = state.filters,
                userLocation = state.userLocation,
                nearbyPlaces = state.nearbyPlaces,
                onDateChipClick = { preset ->
                    if (preset is DateRangeFilter.Custom) showDatePicker = true
                    else viewModel.setDateRange(preset)
                },
                onCustomRangeClick = { showDatePicker = true },
                onIslandSelected = viewModel::setIsland,
                onLocationFilter = viewModel::setLocationFilter,
                onCategoryToggle = viewModel::toggleCategory,
                onClearKeyword = { viewModel.setKeyword("") },
            )
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.events.isEmpty() && !state.isRefreshing) {
                    EmptyState(
                        location = state.filters.location,
                        country = state.userLocation?.country,
                        onClearFilters = viewModel::clearFilters,
                        onChangeLocation = { showLocationSheet = true },
                    )
                } else {
                    EventList(
                        events = state.events,
                        origin = state.distanceOrigin,
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
    if (showKeywordSearch) {
        KeywordSheet(
            currentKeyword = state.filters.keyword,
            onDismiss = { showKeywordSearch = false },
            onApply = { keyword ->
                viewModel.setKeyword(keyword)
                showKeywordSearch = false
            },
        )
    }
    if (showLocationSheet) {
        LocationSheet(
            userLocation = state.userLocation,
            suggestions = state.placeSuggestions,
            nearbyPlaces = state.nearbyPlaces,
            countries = state.countries,
            isLocating = state.isLocating,
            onQueryChange = viewModel::queryPlaces,
            onPlaceSelected = { viewModel.selectPlace(it); showLocationSheet = false },
            onCountrySelected = { viewModel.selectCountry(it); showLocationSheet = false },
            onFreeText = { viewModel.searchLocation(it); showLocationSheet = false },
            onPermissionResult = { granted ->
                viewModel.onLocationPermissionResult(granted)
                if (granted) showLocationSheet = false
            },
            onDismiss = { viewModel.queryPlaces(""); showLocationSheet = false },
        )
    }
}

/** Branded masthead: the country-tinted mark as the "0" of "0 FOMO", the
 *  location pill, freshness, and keyword search. The gradient runs under the
 *  status bar for a full edge-to-edge header. */
@Composable
private fun FeedHeader(
    userLocation: UserLocation?,
    lastSyncEpochMs: Long,
    onLocationClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(AquaDeep, MaterialTheme.colorScheme.primary))),
    ) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)
                .fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Brand lockup: the cancelled-zero mark IS the "0" of "0 FOMO".
                // The masthead is dark in both themes, so use the dark-ground arms.
                val onDark = LocalCountryAccentsOnDark.current
                BrandMark(Modifier.size(32.dp), armA = onDark.armA, armB = onDark.armB)
                Spacer(Modifier.width(6.dp))
                Text(
                    "FOMO",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Sand,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Sand)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LocationPill(userLocation, onClick = onLocationClick)
                Spacer(Modifier.width(10.dp))
                Text(
                    freshnessLabel(lastSyncEpochMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = Sand.copy(alpha = 0.75f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "[flag] Nassau v": the one tap that changes country, city and colours. */
@Composable
private fun LocationPill(userLocation: UserLocation?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Sand.copy(alpha = 0.14f),
        contentColor = Sand,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(userLocation?.country?.flagEmoji ?: "", fontSize = 15.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                userLocation?.label ?: "Choose a place",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ExpandMore, contentDescription = "Change location",
                modifier = Modifier.size(18.dp))
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
    if (lastSyncEpochMs == 0L) return "Not synced yet. Pull to refresh"
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
    userLocation: UserLocation?,
    nearbyPlaces: List<Place>,
    onDateChipClick: (DateRangeFilter) -> Unit,
    onCustomRangeClick: () -> Unit,
    onIslandSelected: (BahamianIsland?) -> Unit,
    onLocationFilter: (LocationFilter) -> Unit,
    onCategoryToggle: (EventCategory) -> Unit,
    onClearKeyword: () -> Unit,
) {
    Column {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
        val country = userLocation?.country
        if (country == null || country.code == "BS") {
            // Launch market keeps its island vocabulary.
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
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
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                userLocation.place?.let { p ->
                    item {
                        val near = LocationFilter.Near(p.lat, p.lng, 60.0, p.name)
                        FilterChip(
                            selected = (filters.location as? LocationFilter.Near)?.label == p.name,
                            onClick = { onLocationFilter(near) },
                            label = { Text("Near ${p.name}") },
                        )
                    }
                }
                item {
                    FilterChip(
                        selected = filters.location is LocationFilter.CountryTag,
                        onClick = { onLocationFilter(LocationFilter.CountryTag(country.code, country.name)) },
                        label = { Text("All ${country.name}") },
                    )
                }
                items(nearbyPlaces.filter { it.name != userLocation.place?.name }) { place ->
                    FilterChip(
                        selected = (filters.location as? LocationFilter.Near)?.label == place.name,
                        onClick = {
                            onLocationFilter(LocationFilter.Near(place.lat, place.lng, 60.0, place.name))
                        },
                        label = { Text(place.name) },
                    )
                }
                item {
                    FilterChip(
                        selected = filters.location is LocationFilter.Everywhere,
                        onClick = { onLocationFilter(LocationFilter.Everywhere) },
                        label = { Text("Everywhere") },
                    )
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
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
    origin: Pair<Double, Double>?,
    onEventClick: (String) -> Unit,
    onToggleFavorite: (Event) -> Unit,
) {
    val grouped = remember(events) { events.groupBy { it.date } }
    val today = remember(events) { LocalDate.now() }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (date, dayEvents) ->
            item(key = "header-$date") {
                DayHeader(date, today, dayEvents.size)
            }
            items(dayEvents, key = { it.id }) { event ->
                val distance = origin?.let { (olat, olng) ->
                    val lat = event.lat
                    val lng = event.lng
                    if (lat != null && lng != null)
                        Geo.distanceLabel(Geo.distanceKm(olat, olng, lat, lng))
                    else null
                }
                EventCard(event, onClick = { onEventClick(event.id) },
                    onToggleFavorite = { onToggleFavorite(event) },
                    distanceLabel = distance)
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
    distanceLabel: String? = null,   // "~3.2 km" from the feed's origin; null = not shown
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
                    distanceLabel?.let {
                        InfoPill(
                            it,
                            container = MaterialTheme.colorScheme.surfaceVariant,
                            content = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun EmptyState(
    location: LocationFilter,
    country: Country?,
    onClearFilters: () -> Unit,
    onChangeLocation: () -> Unit,
) {
    val launchMarket = country == null || country.code == "BS"
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (launchMarket) "🏝️" else "🧭", style = MaterialTheme.typography.displayMedium)
            Text(
                if (launchMarket) "No events match these filters"
                else "Nothing listed for ${location.label} yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (launchMarket) "Try widening the date range or island"
                else "0 FOMO is growing city by city. Widen the area, or check back soon.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onClearFilters) { Text("Clear filters") }
                TextButton(onClick = onChangeLocation) { Text("Change location") }
            }
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
private fun KeywordSheet(
    currentKeyword: String,
    onDismiss: () -> Unit,
    onApply: (keyword: String) -> Unit,
) {
    var keyword by remember { mutableStateOf(currentKeyword) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("What are you looking for?", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Keyword: reggae, brunch, comedy…") },
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onApply(keyword) }) { Text("Apply") }
            }
        }
    }
}

/**
 * Where are you? Three ways in, in order of effort: one tap on the phone's
 * approximate location, a city search against the offline gazetteer, or a
 * country from the list. The header colours follow the answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationSheet(
    userLocation: UserLocation?,
    suggestions: List<Place>,
    nearbyPlaces: List<Place>,
    countries: List<Country>,
    isLocating: Boolean,
    onQueryChange: (String) -> Unit,
    onPlaceSelected: (Place) -> Unit,
    onCountrySelected: (Country) -> Unit,
    onFreeText: (String) -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showAllCountries by remember { mutableStateOf(false) }
    val permission = android.Manifest.permission.ACCESS_COARSE_LOCATION
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(), onPermissionResult)
    val countryByCode = remember(countries) { countries.associateBy { it.code } }
    // Home + neighbours first: the launch market and the first-wave rollout.
    val featured = remember(countries, userLocation) {
        (listOfNotNull(userLocation?.country?.code) + FEATURED_COUNTRIES)
            .distinct().mapNotNull { countryByCode[it] }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.heightIn(max = 620.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Where are you?", style = MaterialTheme.typography.titleLarge)
            }
            item {
                FilledTonalButton(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, permission) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) onPermissionResult(true) else launcher.launch(permission)
                    },
                    enabled = !isLocating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isLocating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.MyLocation, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (isLocating) "Finding your city…" else "Use my location (approximate)")
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; onQueryChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("City, island, zip code or region") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = ""; onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    },
                    singleLine = true,
                )
            }
            if (query.isNotBlank()) {
                items(suggestions, key = { "${it.countryCode}-${it.name}-${it.lat}" }) { place ->
                    val c = countryByCode[place.countryCode]
                    ListItem(
                        headlineContent = { Text(place.name) },
                        supportingContent = { Text(c?.name ?: place.countryCode) },
                        leadingContent = { Text(c?.flagEmoji ?: "", fontSize = 20.sp) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPlaceSelected(place) },
                    )
                }
                if (suggestions.isEmpty()) {
                    item {
                        TextButton(onClick = { onFreeText(query) }) {
                            Text("Search \"$query\" online")
                        }
                    }
                }
            } else {
                if (nearbyPlaces.isNotEmpty() && userLocation != null) {
                    item {
                        Text("Popular in ${userLocation.country.name}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline)
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(nearbyPlaces) { place ->
                                FilterChip(
                                    selected = userLocation.place?.name == place.name,
                                    onClick = { onPlaceSelected(place) },
                                    label = { Text(place.name) },
                                )
                            }
                        }
                    }
                }
                item { HorizontalDivider() }
                item {
                    Text("Country", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline)
                }
                val list = if (showAllCountries) countries else featured
                items(list, key = { it.code }) { country ->
                    ListItem(
                        headlineContent = { Text(country.name) },
                        leadingContent = { Text(country.flagEmoji, fontSize = 20.sp) },
                        trailingContent = {
                            if (userLocation?.country?.code == country.code)
                                Icon(Icons.Default.LocationOn, contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onCountrySelected(country) },
                    )
                }
                if (!showAllCountries) {
                    item {
                        TextButton(onClick = { showAllCountries = true }) {
                            Text("All ${countries.size} countries")
                        }
                    }
                }
            }
        }
    }
}

/** Launch market plus the first-wave rollout (docs/ROADMAP.md Phase 4). */
private val FEATURED_COUNTRIES = listOf(
    "BS", "JM", "TT", "BB", "KY", "US", "CA", "GB", "DO", "PR", "BM", "TC")
