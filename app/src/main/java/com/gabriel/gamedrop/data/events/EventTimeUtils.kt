package com.gabriel.gamedrop.data.events

import java.time.Instant

object EventTimeUtils {
    fun isUpcoming(event: GamingEvent, now: Instant = Instant.now()): Boolean {
        val end = event.endTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val start = runCatching { Instant.parse(event.startTime) }.getOrNull()
        return (end ?: start)?.isBefore(now) != true
    }
}
