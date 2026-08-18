package com.gabriel.gamedrop.data.remote

import com.gabriel.gamedrop.data.releases.IgdbGameIds
import com.gabriel.gamedrop.data.releases.IgdbReleaseGame
import com.gabriel.gamedrop.data.releases.ReleaseFeedRepository
import com.gabriel.gamedrop.domain.Game
import com.gabriel.gamedrop.domain.ReleasePrecision
import java.time.LocalDate

class HybridGameRemoteDataSource(
    private val releases: ReleaseFeedRepository,
    private val rawg: GameRemoteDataSource
) : GameRemoteDataSource {
    override suspend fun games(
        start: LocalDate?,
        end: LocalDate?,
        search: String?,
        ordering: String?,
        pages: Int
    ): List<Game> {
        val calendarQuery = start != null && end != null && search.isNullOrBlank()
        if (!calendarQuery) return rawg.games(start, end, search, ordering, pages)

        val igdb = runCatching { releases.games(start!!, end!!) }.getOrDefault(emptyList())
        return if (igdb.isNotEmpty()) igdb.map(::toDomain)
        else rawg.games(start, end, search, ordering, pages)
    }

    override suspend fun gameDetails(id: Int, includeMedia: Boolean): Game {
        if (!IgdbGameIds.isIgdb(id)) return rawg.gameDetails(id, includeMedia)
        val game = releases.gameByIgdbId(IgdbGameIds.decode(id), force = includeMedia)
            ?: throw IllegalStateException("IGDB game not found: $id")
        return toDomain(game)
    }

    private fun toDomain(game: IgdbReleaseGame): Game {
        val date = game.primaryReleaseDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val platforms = game.releaseDates.mapNotNull { it.platform ?: it.platformName }.distinct()
        return Game(
            id = IgdbGameIds.encode(game.igdbId),
            slug = game.slug.orEmpty(),
            name = game.name,
            coverUrl = game.coverUrl,
            artworkUrl = game.coverUrl,
            releaseDate = date,
            releasePrecision = if (date != null) ReleasePrecision.EXACT else ReleasePrecision.UNKNOWN,
            platforms = platforms,
            description = game.summaryPtBr ?: game.summary,
            screenshots = emptyList(),
            trailerUrl = game.bestTrailer?.youtubeUrl,
            website = game.igdbUrl,
            isEarlyAccess = game.gameStatus?.contains("early", ignoreCase = true) == true
        )
    }
}
