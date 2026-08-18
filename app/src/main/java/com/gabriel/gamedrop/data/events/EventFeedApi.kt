package com.gabriel.gamedrop.data.events

import retrofit2.http.GET

interface EventFeedApi {
    @GET("Gabriel-Liz2003/teste10900/main/data/events-feed.json")
    suspend fun getFeed(): EventFeed
}
