package app.nuviowatch.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoreFunTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 30, 21, 0).toInstant(zone).toEpochMilli()
    private val m = 60_000L
    private fun at(day: Int, hour: Int = 20) = LocalDateTime.of(2026, 10, day, hour, 0).toInstant(zone).toEpochMilli()
    private fun film(p: Int, id: String, atMs: Long, done: Boolean = true, fraction: Float = if (done) 1f else 0.4f) =
        Viewing(p, id, false, null, null, id, 100 * m, atMs, done, fraction)
    private fun ep(p: Int, id: String, s: Int, e: Int, atMs: Long, done: Boolean = true) = Viewing(p, id, true, s, e, id, 45 * m, atMs, done, if (done) 1f else 0.5f)
    private val names = mapOf(1 to "Mani", 2 to "Ishti", 3 to "Haris")

    @Test
    fun partyCodesAndScoresAndSpoilerSafeComments() {
        assertEquals("AB12CD", Social.partyCode("Join me! https://nuvio.tv/party/AB12CD"))
        assertEquals("XY9", Social.partyCode("nuvio://party/XY9"))
        assertEquals("QWERTY", Social.partyCode(" QWERTY "))
        assertNull(Social.partyCode("not a code!"))

        val ratings = listOf(Rating(id = "a", profileIndex = 1, contentId = "f", stars = 2, at = 1), Rating(id = "b", profileIndex = 1, contentId = "f", stars = 4, at = 2), Rating(id = "c", profileIndex = 2, contentId = "f", stars = 5, at = 3))
        assertEquals(4.5 to mapOf(1 to 4, 2 to 5), Social.familyScore("f", ratings))
        assertNull(Social.familyScore("other", ratings))

        fun c(s: Int?, e: Int?) = Comment(id = "$s$e", contentId = "show", season = s, episode = e, fromName = "Mani", text = "!", at = 1)
        val comments = listOf(c(1, 2), c(1, 5), c(2, 1), c(null, null))
        val (visible, hidden) = Social.visibleComments(comments, "show", 1 to 3)
        assertEquals(2, visible.size)
        assertEquals(2, hidden)
        assertEquals(1, Social.visibleComments(comments, "show", null).first.size)
    }

    @Test
    fun rouletteIdeas() {
        val v = listOf(
            ep(1, "both", 1, 2, at(20)), ep(2, "both", 1, 1, at(21)),
            film(2, "loved", at(10)),
            film(1, "dropped", at(5), done = false, fraction = 0.4f),
        )
        val ideas = Fun.roulette(setOf(1, 2), v, names = names)
        assertEquals(listOf("both", "loved", "dropped"), ideas.map { it.contentId })
        assertEquals("Mani and Ishti are both on it", ideas[0].reason)
        assertEquals("Ishti loved it; Mani hasn't seen it", ideas[1].reason)
        assertEquals("Mani left it at 40%", ideas[2].reason)
    }

    @Test
    fun newEpisodeCalendar() {
        val eps = mapOf("show" to listOf(EpisodeInfo(2, 1, releasedMs = at(30, 8)), EpisodeInfo(2, 2, releasedMs = at(30, 8) + 7 * 86_400_000L)))
        val v = listOf(ep(1, "show", 1, 8, at(20)), ep(3, "show", 1, 8, at(22)))
        val today = Fun.newEpisodes(v, eps, at(30, 0), at(30, 0) + 86_400_000L, now)
        assertEquals(listOf(1), today.map { it.episode.episode })
        assertEquals(listOf(1, 3), today.single().followers)
        assertEquals(2, Fun.newEpisodes(v, eps, at(30, 0), at(30, 0) + 8 * 86_400_000L, now).size)
    }

    @Test
    fun challenges() {
        val month = YearMonth.of(2026, 10)
        assertEquals(3, Fun.challengesFor(month).size)
        val titles = mapOf("a" to TitleInfo("A", genres = listOf("Drama")), "b" to TitleInfo("B", genres = listOf("Comedy")), "c" to TitleInfo("C", genres = listOf("Horror")))
        val mine = listOf(film(1, "a", at(3)), film(1, "b", at(9)), film(1, "c", at(28)))
        val genre = Fun.challengeProgress(Challenge.GENRE_FILMS, mine, titles, emptyMap(), month, zone)
        assertTrue(genre.done)
        assertEquals(at(28), genre.completedAt)

        val comeback = listOf(film(1, "x", LocalDateTime.of(2026, 9, 1, 20, 0).toInstant(zone).toEpochMilli(), done = false), film(1, "x", at(5)))
        assertTrue(Fun.challengeProgress(Challenge.COMEBACK, comeback, titles, emptyMap(), month, zone).done)

        val season = (1..3).map { ep(1, "s", 1, it, at(10 + it)) }
        val eps = mapOf("s" to (1..3).map { EpisodeInfo(1, it, releasedMs = at(1)) })
        assertEquals(at(13), Fun.challengeProgress(Challenge.SEASON, season, titles, eps, month, zone).completedAt)

        val variety = Fun.challengeProgress(Challenge.VARIETY, mine, titles, emptyMap(), month, zone)
        assertEquals(3, variety.count)
        assertNull(variety.completedAt)
    }

    @Test
    fun yearlyWrappedExtras() {
        val v = listOf(film(1, "first", at(1)), film(2, "x", at(2)), film(2, "y", at(3)), film(2, "z", at(4)))
        val w = Fun.wrapped(v, emptyMap(), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "2026", zone)!!
        assertEquals("first" to at(1), w.firstTitle)
        assertEquals(2 to 3, w.longestStreak)
    }
}
