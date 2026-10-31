package app.nuviowatch.core

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AnnounceTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 30, 20, 0).toInstant(zone).toEpochMilli()
    private fun at(daysAgo: Long) = LocalDateTime.of(2026, 10, 30, 12, 0).minusDays(daysAgo).toInstant(zone).toEpochMilli()
    private fun v(daysAgo: Long) = Viewing(1, "show", true, 1, daysAgo.toInt() + 1, "Show", 45 * 60_000L, at(daysAgo), true, 1f)

    @Test
    fun newBadgesOnlyOnce() {
        val current = listOf(BadgeProgress(Badge.NIGHT_OWL, true, 1f), BadgeProgress(Badge.MOVIE_BUFF, false, 0.5f), BadgeProgress(Badge.ON_FIRE, true, 1f))
        assertEquals(listOf(Badge.NIGHT_OWL, Badge.ON_FIRE), Fun.newBadges(2, current, emptySet()))
        assertEquals(listOf(Badge.ON_FIRE), Fun.newBadges(2, current, setOf("2|NIGHT_OWL")))
    }

    @Test
    fun streakAtRiskOnlyWhenNothingToday() {
        val upToYesterday = listOf(v(1), v(2), v(3), v(4), v(5))
        assertEquals(5, Fun.streakAtRisk(upToYesterday, now, zone))
        assertNull(Fun.streakAtRisk(upToYesterday + v(0), now, zone), "already watched today")
        assertNull(Fun.streakAtRisk(listOf(v(1)), now, zone), "a one-day streak isn't worth a reminder")
    }

    @Test
    fun episodesFromPlayerSubtitles() {
        assertEquals(6 to 1, Fun.episodeOf("S6, E1: By Love's Own Sweet Constraint"))
        assertEquals(2 to 10, Fun.episodeOf("S2:E10 – Title"))
        assertEquals(3 to 4, Fun.episodeOf("s3 · e4"))
        assertNull(Fun.episodeOf("2024"))
    }

    @Test
    fun spoilerDistance() {
        val list = (1..8).map { EpisodeInfo(1, it) } + (1..8).map { EpisodeInfo(2, it) }
        assertEquals(3, Fun.episodesAhead(1 to 6, 1 to 3, list))
        assertEquals(5, Fun.episodesAhead(2 to 2, 1 to 5, list))
        assertEquals(-2, Fun.episodesAhead(1 to 2, 1 to 4, list))
        assertEquals(3, Fun.episodesAhead(1 to 3, null, list), "never watched: everything's ahead")
        assertEquals(2, Fun.episodesAhead(4 to 7, 4 to 5, null))
        assertEquals(1, Fun.episodesAhead(5 to 1, 4 to 9, null))
    }

    @Test
    fun pingRoundTrip() {
        val ping = Ping(id = "p", kind = "join", fromDeviceId = "a", fromName = "Mani", toProfile = 2, title = "Show", at = 1)
        assertIs<Ping>(Social.parse(Social.encode(ping)))
    }
}
