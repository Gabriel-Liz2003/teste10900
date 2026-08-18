package com.gabriel.gamedrop.data.events

data class GamingEvent(
    val id: Long,
    val name: String,
    val description: String?,
    val startTime: String,
    val endTime: String?,
    val timeZone: String?,
    val liveStreamUrl: String?,
    val logoUrl: String?,
    val games: List<EventGame>,
    val videos: List<EventVideo>
)

data class EventGame(
    val igdbId: Long,
    val name: String,
    val coverUrl: String?,
    val summary: String?,
    val highlightType: EventHighlightType,
    val videos: List<EventVideo>
)

data class EventVideo(
    val id: Long,
    val gameId: Long?,
    val name: String,
    val youtubeVideoId: String
) {
    val youtubeUrl: String get() = "https://www.youtube.com/watch?v=$youtubeVideoId"
}

enum class EventHighlightType {
    NEW_ANNOUNCEMENT,
    GAMEPLAY,
    TRAILER,
    UPDATE,
    FEATURED
}

data class EventFeed(
    val generatedAt: String?,
    val source: String,
    val events: List<GamingEvent>
)
