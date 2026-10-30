package app.nuviowatch.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FunTest {
    private val zone = ZoneOffset.UTC
    // Thursday 30 October 2026, 21:00
    private val now = LocalDateTime.of(2026, 10, 30, 21, 0).toInstant(zone).toEpochMilli()
    private val min = 60_000L

    private fun at(daysAgo: Long, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, 30, hour, minute).minusDays(daysAgo).toInstant(zone).toEpochMilli()

    private fun ep(p: Int, id: String, e: Int, atMs: Long, done: Boolean = true) =
        Viewing(p, id, true, 1, e, id, 45 * min, atMs, done, if (done) 1f else 0.4f)

    private fun film(p: Int, id: String, atMs: Long, done: Boolean = true) =
        Viewing(p, id, false, null, null, id, 100 * min, atMs, done, if (done) 1f else 0.5f)

    private val titles = mapOf(
        "show" to TitleInfo("Show", genres = listOf("Drama", "Crime")),
        "film" to TitleInfo("Film", genres = listOf("Comedy")),
        "other" to TitleInfo("Other", genres = listOf("Drama")),
    )

    @Test
    fun badgesProgress() {
        val v = (1..4).map { ep(1, "show", it, at(0, 20, it)) } + (0L..6L).map { film(1, "f$it", at(it, 23, 30)) }
        val badges = Fun.badges(v, titles, now, zone).associateBy { it.badge }
        assertTrue(badges.getValue(Badge.BINGE_KING).earned)
        assertTrue(badges.getValue(Badge.NIGHT_OWL).earned)
        assertTrue(badges.getValue(Badge.ON_FIRE).earned)
        assertFalse(badges.getValue(Badge.MOVIE_BUFF).earned)
        assertEquals(0.7f, badges.getValue(Badge.MOVIE_BUFF).progress)
    }

    @Test
    fun leaderboardAndChampion() {
        val v = listOf(
            ep(1, "show", 1, at(0, 19)), ep(1, "show", 2, at(0, 20)), // this week (Mon 26th onwards)
            film(2, "film", at(1, 18)),
            film(2, "film2", at(7, 18)), film(2, "film3", at(8, 18)), // last week
        )
        val board = Fun.leaderboard(v, listOf(1, 2, 3), now, zone)
        assertEquals(listOf(2, 1, 3), board.map { it.profileIndex })
        // Ishti: 100 min = 10 pts, 1 film = 10, streak 1 (yesterday) = 5 → 25
        assertEquals(25, board[0].points)
        // Mani: 90 min = 9 pts, 2 episodes = 6, streak 1 = 5 → 20
        assertEquals(20, board[1].points)
        assertEquals(0, board[2].points)
        assertEquals(2, Fun.champion(v, listOf(1, 2, 3), now, zone))
    }

    @Test
    fun personality() {
        val binger = (0L..3L).flatMap { d -> (1..3).map { ep(1, "show", it, at(d, 14)) } }
        assertEquals(Personality.BINGER, Fun.personality(binger, zone))
        val owl = (0L..5L).map { film(1, "f$it", at(it, 23)) }
        assertEquals(Personality.NIGHT_OWL, Fun.personality(owl, zone))
        assertEquals(Personality.CASUAL, Fun.personality(owl.take(2), zone))
    }

    @Test
    fun abandonedTitles() {
        val v = listOf(
            ep(1, "show", 3, at(40, 20), done = false),
            film(1, "film", at(30, 20), done = true),
            film(1, "other", at(2, 20), done = false),
        )
        val list = Fun.abandoned(v, now)
        assertEquals(listOf("show"), list.map { it.contentId })
        assertEquals(3, list.single().episode)
    }

    @Test
    fun picksForSomeone() {
        val v = (1..6).map { ep(2, "show", it, at(1, 10 + it)) } +
            film(3, "film", at(3, 20)) +
            film(1, "other", at(5, 20))
        val picks = Fun.picks(1, v, titles, mapOf(2 to "Ishti", 3 to "Mani"), zone)
        assertEquals(listOf("show", "film"), picks.map { it.contentId }, "drama fan gets the drama first")
        assertEquals("Ishti watched 6 episodes in 1 day", picks[0].reason)
        assertEquals("Mani finished it", picks[1].reason)
    }

    @Test
    fun wrappedMonth() {
        val v = listOf(ep(1, "show", 1, at(0, 20)), ep(1, "show", 2, at(0, 21)), film(2, "film", at(2, 20)), film(2, "old", at(60, 20)))
        val w = Fun.wrapped(v, titles, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), "October", zone)!!
        assertEquals(190 * min, w.totalMs)
        assertEquals(2 to 100 * min, w.topWatcher)
        assertEquals("film" to 100 * min, w.topTitle)
        assertEquals(Triple(1, "show", 2), w.longestBinge)
        assertEquals(LocalDate.of(2026, 10, 28) to 100 * min, w.busiestDay)
        assertNull(Fun.wrapped(v, titles, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1), "Jan", zone))
    }
}
