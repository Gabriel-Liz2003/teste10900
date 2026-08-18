package com.gabriel.gamedrop.data.events

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EventTimeUtilsTest {
    private fun event(start: String, end: String? = null) = GamingEvent(
        id = 1,
        name = "Event",
        description = null,
        startTime = start,
        endTime = end,
        timeZone = "UTC",
        liveStreamUrl = null,
        logoUrl = null,
        games = emptyList(),
        videos = emptyList()
    )

    @Test
    fun `future event is upcoming`() {
        val now = Instant.parse("2026-08-18T12:00:00Z")
        assertTrue(EventTimeUtils.isUpcoming(event("2026-08-19T18:00:00Z"), now))
    }

    @Test
    fun `event remains upcoming until its end`() {
        val now = Instant.parse("2026-08-18T12:00:00Z")
        assertTrue(EventTimeUtils.isUpcoming(event("2026-08-18T10:00:00Z", "2026-08-18T14:00:00Z"), now))
    }

    @Test
    fun `ended event is past`() {
        val now = Instant.parse("2026-08-18T12:00:00Z")
        assertFalse(EventTimeUtils.isUpcoming(event("2026-08-17T10:00:00Z", "2026-08-17T14:00:00Z"), now))
    }

    @Test
    fun `event video builds youtube url`() {
        val video = EventVideo(1, 2, "Trailer", "abc123")
        assertTrue(video.youtubeUrl.endsWith("watch?v=abc123"))
    }
}
