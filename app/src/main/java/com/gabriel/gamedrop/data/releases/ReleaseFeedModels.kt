package com.gabriel.gamedrop.data.releases

data class ReleaseIndex(
    val schemaVersion: Int = 1,
    val generatedAt: String? = null,
    val source: String? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val gameCount: Int = 0,
    val gamesWithTrailers: Int = 0,
    val months: List<ReleaseMonthMeta> = emptyList(),
    val gameMonths: Map<String, String> = emptyMap()
)

data class ReleaseMonthMeta(
    val month: String,
    val gameCount: Int = 0,
    val bytes: Long = 0
)

data class ReleaseMonthFeed(
    val schemaVersion: Int = 1,
    val generatedAt: String? = null,
    val month: String,
    val source: String? = null,
    val gameCount: Int = 0,
    val games: List<IgdbReleaseGame> = emptyList()
)

data class IgdbReleaseGame(
    val igdbId: Int,
    val name: String,
    val slug: String? = null,
    val summary: String? = null,
    val summaryPtBr: String? = null,
    val coverUrl: String? = null,
    val primaryReleaseDate: String? = null,
    val firstReleaseDate: String? = null,
    val gameType: String? = null,
    val gameStatus: String? = null,
    val parentGameId: Int? = null,
    val igdbUrl: String? = null,
    val releaseDates: List<IgdbReleaseDate> = emptyList(),
    val bestTrailer: IgdbTrailer? = null,
    val trailers: List<IgdbTrailer> = emptyList()
)

data class IgdbReleaseDate(
    val date: String? = null,
    val human: String? = null,
    val platformId: Int? = null,
    val platform: String? = null,
    val platformName: String? = null,
    val region: String? = null,
    val status: String? = null,
    val dateFormatId: Int? = null
)

data class IgdbTrailer(
    val id: Long,
    val name: String,
    val youtubeVideoId: String,
    val type: String = "TRAILER",
    val rank: Int = 0
) {
    val youtubeUrl: String get() = "https://www.youtube.com/watch?v=$youtubeVideoId"
    val thumbnailUrl: String get() = "https://i.ytimg.com/vi/$youtubeVideoId/hqdefault.jpg"
}

object IgdbGameIds {
    const val OFFSET: Int = 1_000_000_000
    fun encode(igdbId: Int): Int = OFFSET + igdbId
    fun isIgdb(gameId: Int): Boolean = gameId >= OFFSET
    fun decode(gameId: Int): Int = gameId - OFFSET
}
