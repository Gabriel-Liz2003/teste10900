package com.gabriel.gamedrop.data.releases

import retrofit2.http.GET
import retrofit2.http.Path

interface ReleaseFeedApi {
    @GET("Gabriel-Liz2003/teste10900/main/data/releases/index.json")
    suspend fun getIndex(): ReleaseIndex

    @GET("Gabriel-Liz2003/teste10900/main/data/releases/{month}.json")
    suspend fun getMonth(@Path("month") month: String): ReleaseMonthFeed
}
