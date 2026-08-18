package com.gabriel.gamedrop.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gabriel.gamedrop.core.AppError
import com.gabriel.gamedrop.data.releases.IgdbTrailer
import com.gabriel.gamedrop.data.releases.ReleaseFeedRepository
import com.gabriel.gamedrop.data.repository.GameCatalogRepository
import com.gabriel.gamedrop.data.repository.SyncOutcome
import com.gabriel.gamedrop.domain.Game
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DetailUiState(
    val game: Game? = null,
    val notificationOffsets: Set<Int> = emptySet(),
    val trailers: List<IgdbTrailer> = emptyList(),
    val isLoading: Boolean = true,
    val error: AppError? = null
)

class DetailViewModel(
    private val repository: GameCatalogRepository,
    private val releaseRepository: ReleaseFeedRepository,
    private val gameId: Int
) : ViewModel() {
    private val refresh = MutableStateFlow<Pair<Boolean, AppError?>>(true to null)
    private val trailers = MutableStateFlow<List<IgdbTrailer>>(emptyList())

    val uiState: StateFlow<DetailUiState> = combine(
        repository.observeGame(gameId),
        repository.observeFavoriteOffsets(gameId),
        trailers,
        refresh
    ) { game, offsets, videos, state ->
        DetailUiState(game, offsets, videos, isLoading = state.first && game == null, error = state.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init { refresh(force = false) }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            refresh.value = true to null
            val result = repository.refreshDetails(gameId, force)
            trailers.value = runCatching { releaseRepository.trailersForGameId(gameId) }.getOrDefault(emptyList())
            refresh.value = when (result) {
                is SyncOutcome.Failure -> false to result.error
                else -> false to null
            }
        }
    }

    fun toggleFavorite() { viewModelScope.launch { repository.toggleFavorite(gameId) } }

    fun setOffset(offset: Int, enabled: Boolean) {
        viewModelScope.launch {
            val current = uiState.value.notificationOffsets.toMutableSet()
            if (enabled) current += offset else current -= offset
            repository.setNotificationOffsets(gameId, current)
        }
    }
}
