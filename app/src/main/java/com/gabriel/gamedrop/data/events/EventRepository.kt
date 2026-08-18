package com.gabriel.gamedrop.data.events

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface EventFeedResult {
    data class Success(val feed: EventFeed, val fromCache: Boolean) : EventFeedResult
    data class Failure(val cached: EventFeed? = null) : EventFeedResult
}

interface EventRepository {
    suspend fun load(force: Boolean = false): EventFeedResult
}

class EventRepositoryImpl(
    context: Context,
    private val api: EventFeedApi,
    private val gson: Gson = Gson()
) : EventRepository {
    private val cacheFile = File(context.filesDir, "events-feed.json")
    private val cacheTtlMs = 6L * 60L * 60L * 1000L

    override suspend fun load(force: Boolean): EventFeedResult = withContext(Dispatchers.IO) {
        val cached = readCache()
        if (!force && cached != null && System.currentTimeMillis() - cacheFile.lastModified() < cacheTtlMs) {
            return@withContext EventFeedResult.Success(cached, fromCache = true)
        }
        runCatching { api.getFeed() }
            .fold(
                onSuccess = { feed ->
                    runCatching { cacheFile.writeText(gson.toJson(feed)) }
                    EventFeedResult.Success(feed, fromCache = false)
                },
                onFailure = {
                    if (cached != null) EventFeedResult.Success(cached, fromCache = true)
                    else EventFeedResult.Failure()
                }
            )
    }

    private fun readCache(): EventFeed? = runCatching {
        if (!cacheFile.exists()) return null
        gson.fromJson(cacheFile.readText(), EventFeed::class.java)
    }.getOrNull()
}
