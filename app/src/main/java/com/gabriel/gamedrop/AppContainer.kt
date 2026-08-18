package com.gabriel.gamedrop

import android.content.Context
import com.gabriel.gamedrop.data.local.AppDatabase
import com.gabriel.gamedrop.data.local.AppPreferences
import com.gabriel.gamedrop.data.events.EventFeedApi
import com.gabriel.gamedrop.data.events.EventRepository
import com.gabriel.gamedrop.data.events.EventRepositoryImpl
import com.gabriel.gamedrop.data.remote.RawgApiService
import com.gabriel.gamedrop.data.remote.RawgRemoteDataSource
import com.gabriel.gamedrop.data.repository.GameCatalogRepository
import com.gabriel.gamedrop.data.repository.GameCatalogRepositoryImpl
import com.gabriel.gamedrop.notifications.WorkManagerNotificationScheduler
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.google.gson.Gson
import kotlinx.coroutines.flow.first

class AppContainer(context: Context) {
    private val db = AppDatabase.get(context)
    val preferences = AppPreferences(context)
    private val scheduler = WorkManagerNotificationScheduler(context)
    private val rawgApi: RawgApiService = Retrofit.Builder()
        .baseUrl("https://api.rawg.io/api/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RawgApiService::class.java)
    private val remote = RawgRemoteDataSource(rawgApi) {
        preferences.apiKey.first().ifBlank { BuildConfig.RAWG_API_KEY }
    }

    val repository: GameCatalogRepository = GameCatalogRepositoryImpl(
        gameDao = db.gameDao(),
        favoriteDao = db.favoriteDao(),
        changeDao = db.releaseChangeDao(),
        metadataDao = db.syncMetadataDao(),
        remote = remote,
        notificationScheduler = scheduler
    )

    private val eventApi: EventFeedApi = Retrofit.Builder()
        .baseUrl("https://raw.githubusercontent.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(EventFeedApi::class.java)

    val eventRepository: EventRepository = EventRepositoryImpl(context, eventApi, Gson())
}
