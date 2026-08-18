package com.gabriel.gamedrop.ui.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gabriel.gamedrop.data.events.EventFeedResult
import com.gabriel.gamedrop.data.events.EventRepository
import com.gabriel.gamedrop.data.events.EventTimeUtils
import com.gabriel.gamedrop.data.events.GamingEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant


data class EventsUiState(
    val upcoming: List<GamingEvent> = emptyList(),
    val past: List<GamingEvent> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isFromCache: Boolean = false,
    val generatedAt: String? = null,
    val error: Boolean = false
)

class EventsViewModel(private val repository: EventRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(EventsUiState())
    val uiState: StateFlow<EventsUiState> = _uiState.asStateFlow()

    init { refresh(force = false) }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = _uiState.value.upcoming.isEmpty() && _uiState.value.past.isEmpty(),
                isRefreshing = _uiState.value.upcoming.isNotEmpty() || _uiState.value.past.isNotEmpty(),
                error = false
            )
            when (val result = repository.load(force)) {
                is EventFeedResult.Success -> {
                    val now = Instant.now()
                    val sorted = result.feed.events.sortedBy { runCatching { Instant.parse(it.startTime) }.getOrNull() }
                    val upcoming = sorted.filter { event -> EventTimeUtils.isUpcoming(event, now) }
                    val past = sorted.filterNot { it in upcoming }.sortedByDescending { it.startTime }
                    _uiState.value = EventsUiState(
                        upcoming = upcoming,
                        past = past,
                        isLoading = false,
                        isRefreshing = false,
                        isFromCache = result.fromCache,
                        generatedAt = result.feed.generatedAt,
                        error = false
                    )
                }
                is EventFeedResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, isRefreshing = false, error = true)
                }
            }
        }
    }
}
