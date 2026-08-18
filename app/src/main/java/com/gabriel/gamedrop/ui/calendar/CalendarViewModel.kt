package com.gabriel.gamedrop.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gabriel.gamedrop.core.AppError
import com.gabriel.gamedrop.data.releases.IgdbGameIds
import com.gabriel.gamedrop.data.releases.ReleaseFeedRepository
import com.gabriel.gamedrop.data.repository.GameCatalogRepository
import com.gabriel.gamedrop.data.repository.SyncOutcome
import com.gabriel.gamedrop.domain.Game
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

enum class CalendarMode { MONTH, LIST }

data class CalendarUiState(
    val month: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val mode: CalendarMode = CalendarMode.MONTH,
    val gamesInMonth: List<Game> = emptyList(),
    val selectedGames: List<Game> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: AppError? = null
)

class CalendarViewModel(
    private val repository: GameCatalogRepository,
    private val releaseRepository: ReleaseFeedRepository
) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now())
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val mode = MutableStateFlow(CalendarMode.MONTH)
    private val refresh = MutableStateFlow<Pair<Boolean, AppError?>>(false to null)
    private val popularity = MutableStateFlow<Map<Int, Double>>(emptyMap())

    val uiState: StateFlow<CalendarUiState> = combine(
        repository.observeCatalog(), month, selectedDate, mode, refresh, popularity
    ) { games, currentMonth, selected, currentMode, refreshState, popularityScores ->
        val candidates = games.filter { game ->
            game.releaseDate?.let { YearMonth.from(it) == currentMonth } == true
        }
        val deduped = preferIgdbDuplicates(candidates)
        fun score(game: Game): Double = if (IgdbGameIds.isIgdb(game.id)) {
            popularityScores[IgdbGameIds.decode(game.id)] ?: 0.0
        } else 0.0

        val monthGames = deduped.sortedWith(
            compareByDescending<Game> { score(it) }.thenBy { it.name.lowercase() }
        )
        val selectedGames = monthGames.filter { it.releaseDate == selected }

        CalendarUiState(
            month = currentMonth,
            selectedDate = selected,
            mode = currentMode,
            gamesInMonth = monthGames,
            selectedGames = selectedGames,
            isRefreshing = refreshState.first,
            error = refreshState.second
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    init { syncMonth(force = true) }

    fun moveMonths(amount: Long) {
        val next = month.value.plusMonths(amount)
        month.value = next
        selectedDate.value = next.atDay(1)
        syncMonth()
    }

    fun moveYears(amount: Long) = moveMonths(amount * 12)

    fun goToday() {
        month.value = YearMonth.now()
        selectedDate.value = LocalDate.now()
        syncMonth()
    }

    fun selectDate(date: LocalDate) { selectedDate.value = date }
    fun setMode(value: CalendarMode) { mode.value = value }
    fun retry() = syncMonth(force = true)

    private fun syncMonth(force: Boolean = false) {
        viewModelScope.launch {
            refresh.value = true to null
            val m = month.value
            val result = repository.syncRange(m.atDay(1), m.atEndOfMonth(), force)
            popularity.value = runCatching { releaseRepository.popularityScores(force) }
                .getOrDefault(popularity.value)
            refresh.value = when (result) {
                is SyncOutcome.Failure -> false to result.error
                else -> false to null
            }
        }
    }

    companion object {
        internal fun preferIgdbDuplicates(games: List<Game>): List<Game> = games
            .groupBy { normalizedGameName(it.name) }
            .values
            .map { versions -> versions.firstOrNull { IgdbGameIds.isIgdb(it.id) } ?: versions.first() }

        private fun normalizedGameName(name: String): String = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "")
    }
}
