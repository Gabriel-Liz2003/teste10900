package com.gabriel.gamedrop.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gabriel.gamedrop.R
import com.gabriel.gamedrop.core.SimpleViewModelFactory
import com.gabriel.gamedrop.data.releases.ReleaseFeedRepository
import com.gabriel.gamedrop.data.repository.GameCatalogRepository
import com.gabriel.gamedrop.ui.components.CacheBanner
import com.gabriel.gamedrop.ui.components.CompactGameRow
import com.gabriel.gamedrop.ui.components.ErrorState
import com.gabriel.gamedrop.ui.components.RawgAttribution
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CalendarRoute(
    repository: GameCatalogRepository,
    releaseRepository: ReleaseFeedRepository,
    contentPadding: PaddingValues,
    onGameClick: (Int) -> Unit
) {
    val factory = remember(repository, releaseRepository) {
        SimpleViewModelFactory { CalendarViewModel(repository, releaseRepository) }
    }
    val vm: CalendarViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    CalendarScreen(state, contentPadding, onGameClick, vm::moveMonths, vm::moveYears, vm::goToday, vm::selectDate, vm::setMode, vm::retry)
}

@Composable
private fun CalendarScreen(
    state: CalendarUiState,
    contentPadding: PaddingValues,
    onGameClick: (Int) -> Unit,
    moveMonths: (Long) -> Unit,
    moveYears: (Long) -> Unit,
    goToday: () -> Unit,
    selectDate: (LocalDate) -> Unit,
    setMode: (CalendarMode) -> Unit,
    retry: () -> Unit
) {
    val ptBr = remember { Locale("pt", "BR") }
    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", ptBr) }
    val dayFormatter = remember { DateTimeFormatter.ofPattern("dd 'de' MMMM", ptBr) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.calendar_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = goToday) { Text(stringResource(R.string.go_today)) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.mode == CalendarMode.MONTH, onClick = { setMode(CalendarMode.MONTH) }, label = { Text(stringResource(R.string.calendar_mode_month)) })
                FilterChip(selected = state.mode == CalendarMode.LIST, onClick = { setMode(CalendarMode.LIST) }, label = { Text(stringResource(R.string.calendar_mode_list)) })
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { moveYears(-1) }) { Icon(Icons.Default.FirstPage, stringResource(R.string.previous_year)) }
                IconButton(onClick = { moveMonths(-1) }) { Icon(Icons.Default.ChevronLeft, stringResource(R.string.previous_month)) }
                Text(state.month.atDay(1).format(monthFormatter).replaceFirstChar { it.titlecase(ptBr) }, fontWeight = FontWeight.Bold)
                IconButton(onClick = { moveMonths(1) }) { Icon(Icons.Default.ChevronRight, stringResource(R.string.next_month)) }
                IconButton(onClick = { moveYears(1) }) { Icon(Icons.Default.LastPage, stringResource(R.string.next_year)) }
            }
        }
        if (state.isRefreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error != null && state.gamesInMonth.isEmpty()) item { ErrorState(state.error, false, retry) }
        if (state.error != null && state.gamesInMonth.isNotEmpty()) item { CacheBanner() }
        if (state.mode == CalendarMode.MONTH) {
            item { MonthGrid(state, selectDate) }
            item { Text(stringResource(R.string.games_on_date, state.selectedDate.format(dayFormatter)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (state.selectedGames.isEmpty()) item { Text(stringResource(R.string.no_games_on_date), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.selectedGames, key = { it.id }) { game -> CompactGameRow(game, { onGameClick(game.id) }) }
        } else {
            if (state.gamesInMonth.isEmpty() && state.error == null) {
                item { Text(stringResource(R.string.empty_releases), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.gamesInMonth, key = { "list-${it.id}" }) { game ->
                    CompactGameRow(game, { onGameClick(game.id) })
                }
            }
        }
        item { RawgAttribution() }
    }
}

@Composable
private fun MonthGrid(state: CalendarUiState, selectDate: (LocalDate) -> Unit) {
    val first = state.month.atDay(1)
    val offset = first.dayOfWeek.value - 1
    val datesWithGames = state.gamesInMonth.mapNotNull { it.releaseDate }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf(R.string.weekday_mon, R.string.weekday_tue, R.string.weekday_wed, R.string.weekday_thu, R.string.weekday_fri, R.string.weekday_sat, R.string.weekday_sun).forEach { Text(stringResource(it), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth().height(336.dp),
            userScrollEnabled = false
        ) {
            gridItems((0 until 42).toList()) { cell ->
                val day = cell - offset + 1
                if (day !in 1..state.month.lengthOfMonth()) Spacer(Modifier.size(48.dp)) else {
                    val date = state.month.atDay(day)
                    val selected = date == state.selectedDate
                    Box(
                        modifier = Modifier.padding(3.dp).aspectRatio(1f).clip(MaterialTheme.shapes.medium)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .clickable { selectDate(date) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day.toString(), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                            if (date in datesWithGames) Box(Modifier.padding(top = 3.dp).size(5.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
    }
}
