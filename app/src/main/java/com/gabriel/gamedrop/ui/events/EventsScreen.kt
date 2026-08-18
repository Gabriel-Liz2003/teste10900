package com.gabriel.gamedrop.ui.events

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gabriel.gamedrop.R
import com.gabriel.gamedrop.core.SimpleViewModelFactory
import com.gabriel.gamedrop.data.events.EventHighlightType
import com.gabriel.gamedrop.data.events.EventRepository
import com.gabriel.gamedrop.data.events.GamingEvent
import com.gabriel.gamedrop.events.EventNotificationCoordinator
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsRoute(
    repository: EventRepository,
    contentPadding: PaddingValues,
    onEventClick: (Long) -> Unit
) {
    val context = LocalContext.current
    val factory = remember(repository) { SimpleViewModelFactory { EventsViewModel(repository) } }
    val vm: EventsViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    var notificationsEnabled by remember { mutableStateOf(EventNotificationCoordinator.isEnabled(context)) }
    var month by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        EventNotificationCoordinator.ensurePeriodicSync(context)
        if (notificationsEnabled && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val allEvents = remember(state.upcoming, state.past) { state.upcoming + state.past }
    LaunchedEffect(allEvents, notificationsEnabled) {
        if (allEvents.isNotEmpty() && notificationsEnabled) {
            EventNotificationCoordinator.reconcile(context, allEvents, announceNew = true)
        }
    }

    val eventsByDate = remember(allEvents) {
        allEvents.groupBy { event -> eventLocalDate(event.startTime) }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
    }
    val selectedEvents = eventsByDate[selectedDate].orEmpty().sortedBy { it.startTime }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { vm.refresh(true) },
        modifier = Modifier.padding(contentPadding)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { TopAppBar(title = { Text(stringResource(R.string.events_title), fontWeight = FontWeight.Bold) }) }

            item {
                ElevatedCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.event_notifications_title), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.event_notifications_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = { enabled ->
                                notificationsEnabled = enabled
                                EventNotificationCoordinator.setEnabled(context, enabled)
                                if (enabled && Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }
                }
            }

            item {
                EventCalendar(
                    month = month,
                    selectedDate = selectedDate,
                    eventDates = eventsByDate.keys,
                    onPreviousMonth = { month = month.minusMonths(1) },
                    onNextMonth = { month = month.plusMonths(1) },
                    onToday = {
                        selectedDate = LocalDate.now()
                        month = LocalDate.now().withDayOfMonth(1)
                    },
                    onSelectDate = { selectedDate = it }
                )
            }

            item {
                SectionTitle(stringResource(R.string.events_on_date, formatLocalDate(selectedDate)))
            }
            if (selectedEvents.isEmpty()) {
                item { EmptyEventsText(R.string.events_no_date) }
            } else {
                items(selectedEvents, key = { "date-${it.id}" }) { EventCard(it, onEventClick) }
            }

            if (state.isFromCache) item {
                Text(
                    stringResource(R.string.events_cached),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.error) item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.events_error), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { vm.refresh(true) }) { Text(stringResource(R.string.retry)) }
                }
            }
            if (state.isLoading) {
                items(3) {
                    ElevatedCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().height(140.dp))
                    }
                }
            } else if (state.upcoming.isEmpty() && state.past.isEmpty() && !state.error) {
                item { EmptyEventsText(R.string.events_feed_not_ready) }
            } else {
                item { SectionTitle(stringResource(R.string.events_upcoming)) }
                if (state.upcoming.isEmpty()) item { EmptyEventsText(R.string.events_no_upcoming) }
                items(state.upcoming, key = { "up-${it.id}" }) { EventCard(it, onEventClick) }
                item { SectionTitle(stringResource(R.string.events_past)) }
                if (state.past.isEmpty()) item { EmptyEventsText(R.string.events_no_past) }
                items(state.past, key = { "past-${it.id}" }) { EventCard(it, onEventClick) }
            }
            item {
                Text(
                    stringResource(R.string.events_source_igdb),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EventCalendar(
    month: LocalDate,
    selectedDate: LocalDate,
    eventDates: Set<LocalDate>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit
) {
    val firstDayOffset = month.dayOfWeek.value - 1
    val daysInMonth = month.lengthOfMonth()
    val cells = (0 until 42).map { index ->
        val day = index - firstDayOffset + 1
        if (day in 1..daysInMonth) month.withDayOfMonth(day) else null
    }
    val weekLabels = listOf(
        R.string.weekday_mon, R.string.weekday_tue, R.string.weekday_wed,
        R.string.weekday_thu, R.string.weekday_fri, R.string.weekday_sat, R.string.weekday_sun
    )

    ElevatedCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.events_calendar_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPreviousMonth) { Text("‹") }
                Text(
                    "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.titlecase() }} ${month.year}",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onNextMonth) { Text("›") }
            }
            Row(Modifier.fillMaxWidth()) {
                weekLabels.forEach { res ->
                    Text(stringResource(res), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                }
            }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                            if (date != null) {
                                val selected = date == selectedDate
                                val hasEvent = date in eventDates
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .clickable { onSelectDate(date) }
                                        .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(date.dayOfMonth.toString(), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                    if (hasEvent) {
                                        Box(
                                            Modifier
                                                .padding(top = 3.dp)
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onToday, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.go_today)) }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun EmptyEventsText(textRes: Int) {
    Text(stringResource(textRes), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EventCard(event: GamingEvent, onEventClick: (Long) -> Unit) {
    val context = LocalContext.current
    ElevatedCard(onClick = { onEventClick(event.id) }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!event.logoUrl.isNullOrBlank()) {
                AsyncImage(model = event.logoUrl, contentDescription = event.name, modifier = Modifier.size(88.dp), contentScale = ContentScale.Fit)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(event.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val countdown = eventCountdown(event.startTime)
                Text(
                    if (countdown.isNullOrBlank()) formatEventDate(event.startTime) else "${formatEventDate(event.startTime)} • $countdown",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(stringResource(R.string.events_games_count, event.games.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                event.liveStreamUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    AssistChip(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                        label = { Text(stringResource(R.string.events_watch_live)) },
                        leadingIcon = { Icon(Icons.Default.LiveTv, null) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailRoute(repository: EventRepository, eventId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    var event by remember { mutableStateOf<GamingEvent?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(eventId) {
        event = when (val result = repository.load(false)) {
            is com.gabriel.gamedrop.data.events.EventFeedResult.Success -> result.feed.events.firstOrNull { it.id == eventId }
            else -> null
        }
        loading = false
    }
    if (loading) { Box(Modifier.fillMaxSize().padding(24.dp)) { CircularProgressIndicator() }; return }
    val item = event ?: run {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.events_not_found)); Button(onClick = onBack) { Text(stringResource(R.string.back)) }
        }; return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { TopAppBar(title = { Text(item.name) }, navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }) }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.logoUrl?.let { AsyncImage(model = it, contentDescription = item.name, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp), contentScale = ContentScale.Fit) }
                Text(formatEventDate(item.startTime), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                item.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                item.liveStreamUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
                        Icon(Icons.Default.LiveTv, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.events_watch_live))
                    }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.events_games_shown)) }
        items(item.games, key = { it.igdbId }) { game ->
            ElevatedCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    game.coverUrl?.let { AsyncImage(model = it, contentDescription = game.name, modifier = Modifier.width(86.dp).height(116.dp), contentScale = ContentScale.Crop) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(game.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        SuggestionChip(onClick = {}, label = { Text(highlightLabel(game.highlightType)) })
                        game.summary?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 4, style = MaterialTheme.typography.bodySmall) }
                        game.videos.firstOrNull()?.let { video ->
                            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.youtubeUrl))) }) { Icon(Icons.Default.PlayArrow, null); Text(video.name) }
                        }
                    }
                }
            }
        }
        if (item.videos.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.events_all_videos)) }
            items(item.videos, key = { it.id }) { video ->
                ListItem(headlineContent = { Text(video.name) }, leadingContent = { Icon(Icons.Default.PlayArrow, null) }, modifier = Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.youtubeUrl))) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.events_open_youtube)) }
            }
        }
        item { Text(stringResource(R.string.events_source_igdb), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun highlightLabel(type: EventHighlightType): String = when (type) {
    EventHighlightType.NEW_ANNOUNCEMENT -> stringResource(R.string.event_type_new_announcement)
    EventHighlightType.GAMEPLAY -> stringResource(R.string.event_type_gameplay)
    EventHighlightType.TRAILER -> stringResource(R.string.event_type_trailer)
    EventHighlightType.UPDATE -> stringResource(R.string.event_type_update)
    EventHighlightType.FEATURED -> stringResource(R.string.event_type_featured)
}

private fun eventLocalDate(raw: String): LocalDate? = runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
private fun formatLocalDate(date: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date)
private fun formatEventDate(raw: String): String = runCatching { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.parse(raw).atZone(ZoneId.systemDefault())) }.getOrElse { raw }
private fun eventCountdown(raw: String): String? = runCatching {
    val minutes = Duration.between(Instant.now(), Instant.parse(raw)).toMinutes()
    when {
        minutes <= 0 -> null
        minutes < 60 -> "${minutes} min"
        minutes < 1440 -> "${minutes / 60} h"
        else -> "${minutes / 1440} d"
    }
}.getOrNull()
