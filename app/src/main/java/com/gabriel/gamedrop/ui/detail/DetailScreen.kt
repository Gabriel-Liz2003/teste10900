package com.gabriel.gamedrop.ui.detail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gabriel.gamedrop.R
import com.gabriel.gamedrop.core.SimpleViewModelFactory
import com.gabriel.gamedrop.core.SynopsisTranslation
import com.gabriel.gamedrop.core.SynopsisTranslator
import com.gabriel.gamedrop.data.repository.GameCatalogRepository
import com.gabriel.gamedrop.data.releases.IgdbTrailer
import com.gabriel.gamedrop.data.releases.ReleaseFeedRepository
import com.gabriel.gamedrop.ui.components.*

@Composable
fun DetailRoute(repository: GameCatalogRepository, releaseRepository: ReleaseFeedRepository, gameId: Int, onBack: () -> Unit) {
    val factory = remember(repository, releaseRepository, gameId) { SimpleViewModelFactory { DetailViewModel(repository, releaseRepository, gameId) } }
    val vm: DetailViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    DetailScreen(state, onBack, vm::toggleFavorite, vm::setOffset, vm::refresh)
}

@Composable
private fun DetailScreen(
    state: DetailUiState,
    onBack: () -> Unit,
    toggleFavorite: () -> Unit,
    setOffset: (Int, Boolean) -> Unit,
    retry: () -> Unit
) {
    val game = state.game
    val context = LocalContext.current
    var pendingOffset by remember { mutableStateOf<Int?>(null) }
    var notificationPermissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val offset = pendingOffset
        if (granted && offset != null) {
            setOffset(offset, true)
            notificationPermissionDenied = false
        } else if (!granted) {
            notificationPermissionDenied = true
        }
        pendingOffset = null
    }

    if (game == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.isLoading) CircularProgressIndicator() else ErrorState(state.error, false, retry)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.error != null) item { CacheBanner(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) }
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                AsyncImage(
                    model = game.artworkUrl ?: game.coverUrl,
                    contentDescription = game.name,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
                IconButton(onClick = onBack, modifier = Modifier.padding(12.dp).align(Alignment.TopStart)) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = .78f)) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back), modifier = Modifier.padding(10.dp))
                    }
                }
                IconButton(onClick = toggleFavorite, modifier = Modifier.padding(12.dp).align(Alignment.TopEnd)) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = .78f)) {
                        Icon(if (game.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (game.isFavorite) stringResource(R.string.unfavorite) else stringResource(R.string.favorite), modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(game.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(formatGameReleaseDate(game), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(countdownText(context, game), style = MaterialTheme.typography.labelLarge)
                if (game.platforms.isNotEmpty()) FlowPills(game.platforms)
            }
        }
        if (game.description != null) item {
            DetailSection(stringResource(R.string.details_description)) {
                LocalizedSynopsis(game.description, game.name)
            }
        } else item {
            DetailSection(stringResource(R.string.details_description)) {
                Text(stringResource(R.string.details_missing_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (game.developers.isNotEmpty()) item { DetailLine(stringResource(R.string.details_developer), game.developers.joinToString()) }
        if (game.publishers.isNotEmpty()) item { DetailLine(stringResource(R.string.details_publisher), game.publishers.joinToString()) }
        if (game.genres.isNotEmpty()) item { DetailLine(stringResource(R.string.details_genres), game.genres.joinToString()) }
        game.ageRating?.let { rating -> item { DetailLine(stringResource(R.string.details_age_rating), rating) } }

        item {
            DetailSection(stringResource(R.string.details_trailer_section)) {
                val trailerUrl = game.trailerUrl
                if (state.trailers.isNotEmpty()) {
                    val visibleTrailers = state.trailers.take(6)
                    visibleTrailers.forEachIndexed { index, trailer ->
                        IgdbTrailerCard(trailer, featured = index == 0)
                        if (index != visibleTrailers.lastIndex) Spacer(Modifier.height(10.dp))
                    }
                } else if (trailerUrl != null) {
                    val youtubeId = youtubeVideoId(trailerUrl)
                    if (youtubeId != null) {
                        IgdbTrailerCard(IgdbTrailer(0, "Trailer", youtubeId), featured = true)
                    } else {
                        TrailerPlayer(trailerUrl)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { openUrl(context, trailerUrl) }) {
                            Icon(Icons.Default.OpenInNew, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.details_open_video_external))
                        }
                    }
                } else {
                    Text(stringResource(R.string.details_trailer_rawg_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { openUrl(context, youtubeTrailerSearch(game.name)) }) {
                    Icon(Icons.Default.PlayCircle, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.details_trailer_youtube))
                }
            }
        }

        if (game.screenshots.isNotEmpty()) {
            item {
                DetailSection(stringResource(R.string.details_screenshots)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(game.screenshots) { url -> AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(width = 250.dp, height = 140.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop) }
                    }
                }
            }
        }
        val website = game.website
        if (website != null) {
            item {
                Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { openUrl(context, website) }) { Text(stringResource(R.string.details_website)) }
                }
            }
        }
        if (game.isFavorite) {
            item {
                DetailSection(stringResource(R.string.notifications)) {
                    Text(stringResource(R.string.notifications_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    NotificationChips(state.notificationOffsets) { offset, enable ->
                        if (!enable) setOffset(offset, false)
                        else if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            setOffset(offset, true)
                            notificationPermissionDenied = false
                        } else {
                            pendingOffset = offset
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (notificationPermissionDenied) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.notification_permission_denied), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item { RawgAttribution(Modifier.padding(horizontal = 18.dp)) }
    }
}

@Composable
private fun LocalizedSynopsis(original: String, gameName: String) {
    val language = Locale.current.language.lowercase()
    if (!language.startsWith("pt")) {
        Text(original)
        return
    }

    val translator = remember { SynopsisTranslator() }
    DisposableEffect(translator) { onDispose { translator.close() } }
    val result by produceState<Result<SynopsisTranslation>?>(initialValue = null, original, gameName, language) {
        value = runCatching { translator.toPortuguese(original, listOf(gameName)) }
    }

    when {
        result == null -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.translation_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(original, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        result!!.isFailure -> {
            Text(original)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.translation_failed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val translation = result!!.getOrThrow()
            Text(translation.text)
            if (translation.translated) {
                val context = LocalContext.current
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.translation_attribution),
                    modifier = Modifier.clickable { openUrl(context, "https://translate.google.com") },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun IgdbTrailerCard(trailer: IgdbTrailer, featured: Boolean) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AsyncImage(
            model = trailer.thumbnailUrl,
            contentDescription = trailer.name,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).clickable { openUrl(context, trailer.youtubeUrl) },
            contentScale = ContentScale.Crop
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.PlayCircle, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(trailerTypeLabel(trailer.type), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(trailer.name.ifBlank { "Trailer" }, fontWeight = if (featured) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

private fun trailerTypeLabel(type: String): String = when (type.uppercase()) {
    "LAUNCH" -> "Trailer de lançamento"
    "GAMEPLAY" -> "Gameplay"
    "STORY" -> "Trailer de história"
    "ANNOUNCEMENT" -> "Trailer de anúncio"
    "TEASER" -> "Teaser"
    "DEV_DIARY" -> "Bastidores / Dev Diary"
    else -> "Trailer"
}

private fun youtubeVideoId(url: String): String? = runCatching {
    val uri = Uri.parse(url)
    when {
        uri.host?.contains("youtu.be", true) == true -> uri.lastPathSegment
        uri.host?.contains("youtube.com", true) == true -> uri.getQueryParameter("v")
        else -> null
    }?.takeIf { it.isNotBlank() }
}.getOrNull()

@Composable
private fun TrailerPlayer(url: String) {
    val context = LocalContext.current
    val videoView = remember(url) {
        VideoView(context).apply {
            val controls = MediaController(context)
            controls.setAnchorView(this)
            setMediaController(controls)
            setVideoURI(Uri.parse(url))
            setOnPreparedListener { player ->
                player.isLooping = false
                seekTo(1)
            }
        }
    }
    DisposableEffect(videoView) { onDispose { videoView.stopPlayback() } }
    AndroidView(
        factory = { videoView },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowPills(values: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        values.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationChips(selected: Set<Int>, onToggle: (Int, Boolean) -> Unit) {
    val options = listOf(0 to R.string.notify_today, 1 to R.string.notify_1_day, 3 to R.string.notify_3_days, 7 to R.string.notify_7_days)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { (offset, label) ->
            FilterChip(selected = offset in selected, onClick = { onToggle(offset, offset !in selected) }, label = { Text(stringResource(label)) })
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value)
    }
}

private fun youtubeTrailerSearch(gameName: String): String = Uri.Builder()
    .scheme("https")
    .authority("www.youtube.com")
    .path("results")
    .appendQueryParameter("search_query", "$gameName official trailer")
    .build()
    .toString()

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
