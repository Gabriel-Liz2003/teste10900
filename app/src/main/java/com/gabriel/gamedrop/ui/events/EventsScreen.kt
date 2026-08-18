package com.gabriel.gamedrop.ui.events

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gabriel.gamedrop.R
import com.gabriel.gamedrop.core.SimpleViewModelFactory
import com.gabriel.gamedrop.data.events.EventHighlightType
import com.gabriel.gamedrop.data.events.EventRepository
import com.gabriel.gamedrop.data.events.GamingEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsRoute(
    repository: EventRepository,
    contentPadding: PaddingValues,
    onEventClick: (Long) -> Unit
) {
    val factory = remember(repository) { SimpleViewModelFactory { EventsViewModel(repository) } }
    val vm: EventsViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()

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
private fun SectionTitle(title: String) {
    Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun EmptyEventsText(textRes: Int) {
    Text(stringResource(textRes), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EventCard(event: GamingEvent, onEventClick: (Long) -> Unit) {
    ElevatedCard(onClick = { onEventClick(event.id) }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!event.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = event.logoUrl,
                    contentDescription = event.name,
                    modifier = Modifier.size(88.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(event.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(formatEventDate(event.startTime), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.events_games_count, event.games.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!event.liveStreamUrl.isNullOrBlank() && runCatching { Instant.parse(event.startTime).isAfter(Instant.now()) }.getOrDefault(false)) {
                    AssistChip(onClick = { onEventClick(event.id) }, label = { Text(stringResource(R.string.events_live_stream_available)) }, leadingIcon = { Icon(Icons.Default.LiveTv, null) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailRoute(
    repository: EventRepository,
    eventId: Long,
    onBack: () -> Unit
) {
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

    if (loading) {
        Box(Modifier.fillMaxSize().padding(24.dp)) { CircularProgressIndicator() }
        return
    }
    val item = event ?: run {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.events_not_found))
            Button(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            TopAppBar(
                title = { Text(item.name) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }
            )
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.logoUrl?.let { AsyncImage(model = it, contentDescription = item.name, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp), contentScale = ContentScale.Fit) }
                Text(formatEventDate(item.startTime), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                item.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                item.liveStreamUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
                        Icon(Icons.Default.LiveTv, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.events_watch_live))
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
                            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.youtubeUrl))) }) {
                                Icon(Icons.Default.PlayArrow, null)
                                Text(video.name)
                            }
                        }
                    }
                }
            }
        }
        if (item.videos.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.events_all_videos)) }
            items(item.videos, key = { it.id }) { video ->
                ListItem(
                    headlineContent = { Text(video.name) },
                    leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                TextButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.youtubeUrl))) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { Text(stringResource(R.string.events_open_youtube)) }
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

private fun formatEventDate(raw: String): String = runCatching {
    val zoned = Instant.parse(raw).atZone(ZoneId.systemDefault())
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(zoned)
}.getOrElse { raw }
