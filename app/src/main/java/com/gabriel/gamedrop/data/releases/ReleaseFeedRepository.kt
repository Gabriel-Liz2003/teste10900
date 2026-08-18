package com.gabriel.gamedrop.data.releases

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

interface ReleaseFeedRepository {
    suspend fun games(start: LocalDate, end: LocalDate, force: Boolean = false): List<IgdbReleaseGame>
    suspend fun gameByIgdbId(igdbId: Int, force: Boolean = false): IgdbReleaseGame?
    suspend fun trailersForGameId(gameId: Int): List<IgdbTrailer>
}

class ReleaseFeedRepositoryImpl(
    context: Context,
    private val api: ReleaseFeedApi,
    private val gson: Gson = Gson(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) : ReleaseFeedRepository {
    private val cacheDir = File(context.filesDir, "release-feed").apply { mkdirs() }
    private val ttlMs = 6L * 60L * 60L * 1000L

    override suspend fun games(start: LocalDate, end: LocalDate, force: Boolean): List<IgdbReleaseGame> = withContext(Dispatchers.IO) {
        monthsBetween(start, end).flatMap { month -> loadMonth(month, force).games }
            .filter { game ->
                val date = releaseDate(game) ?: return@filter false
                !date.isBefore(start) && !date.isAfter(end)
            }
            .distinctBy { it.igdbId }
            .sortedWith(compareBy<IgdbReleaseGame> { releaseDate(it) }.thenBy { it.name.lowercase() })
    }

    override suspend fun gameByIgdbId(igdbId: Int, force: Boolean): IgdbReleaseGame? = withContext(Dispatchers.IO) {
        val index = loadIndex(force)
        val month = index.gameMonths[igdbId.toString()] ?: return@withContext null
        loadMonth(YearMonth.parse(month), force).games.firstOrNull { it.igdbId == igdbId }
    }

    override suspend fun trailersForGameId(gameId: Int): List<IgdbTrailer> {
        if (!IgdbGameIds.isIgdb(gameId)) return emptyList()
        return gameByIgdbId(IgdbGameIds.decode(gameId))?.trailers.orEmpty()
    }

    private suspend fun loadIndex(force: Boolean): ReleaseIndex {
        val file = File(cacheDir, "index.json")
        if (!force) readFresh(file, ReleaseIndex::class.java)?.let { return it }
        return runCatching { api.getIndex() }
            .onSuccess { write(file, it) }
            .getOrElse { read(file, ReleaseIndex::class.java) ?: throw it }
    }

    private suspend fun loadMonth(month: YearMonth, force: Boolean): ReleaseMonthFeed {
        val file = File(cacheDir, "$month.json")
        if (!force) readFresh(file, ReleaseMonthFeed::class.java)?.let { return it }
        return runCatching { api.getMonth(month.toString()) }
            .onSuccess { write(file, it) }
            .getOrElse { read(file, ReleaseMonthFeed::class.java) ?: throw it }
    }

    private fun <T> readFresh(file: File, clazz: Class<T>): T? {
        if (!file.exists() || nowMillis() - file.lastModified() >= ttlMs) return null
        return read(file, clazz)
    }

    private fun <T> read(file: File, clazz: Class<T>): T? = runCatching {
        if (!file.exists()) return null
        gson.fromJson(file.readText(), clazz)
    }.getOrNull()

    private fun write(file: File, value: Any) = runCatching { file.writeText(gson.toJson(value)) }

    companion object {
        internal fun monthsBetween(start: LocalDate, end: LocalDate): List<YearMonth> {
            if (end.isBefore(start)) return emptyList()
            val result = mutableListOf<YearMonth>()
            var month = YearMonth.from(start)
            val last = YearMonth.from(end)
            while (!month.isAfter(last)) {
                result += month
                month = month.plusMonths(1)
            }
            return result
        }

        internal fun releaseDate(game: IgdbReleaseGame): LocalDate? = runCatching {
            game.primaryReleaseDate?.take(10)?.let(LocalDate::parse)
        }.getOrNull()
    }
}
