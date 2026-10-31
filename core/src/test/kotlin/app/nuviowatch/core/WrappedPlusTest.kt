package app.nuviowatch.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WrappedPlusTest {
    private val zone = ZoneOffset.UTC
    private fun at(day: String, hour: Int, minute: Int = 0) =
        LocalDate.parse(day).atTime(hour, minute).toInstant(zone).toEpochMilli()

    private fun v(p: Int, id: String, ms: Long, atMs: Long, series: Boolean = false, ep: Int? = null, completed: Boolean = true) =
        Viewing(p, id, series, if (series) 1 else null, ep, id, ms, atMs, completed, if (completed) 1f else 0.5f)

    private val hour = 3_600_000L

    @Test
    fun topTitlesQuizHeatAndAwards() {
        val titles = mapOf(
            "scary" to TitleInfo(name = "Scary", genres = listOf("Horror")),
            "scary2" to TitleInfo(name = "Scary 2", genres = listOf("Horror")),
            "show" to TitleInfo(name = "Show", genres = listOf("Comedy")),
        )
        val views = listOf(
            // Mum: two horror films after midnight (Saturday night into Sunday)
            v(1, "scary", 2 * hour, at("2026-03-08", 1, 30)),
            v(1, "scary2", 2 * hour, at("2026-03-08", 2, 15)),
            // Everyone watches the show on Sunday evenings
            v(0, "show", hour, at("2026-03-08", 20), series = true, ep = 1),
            v(1, "show", hour, at("2026-03-08", 20), series = true, ep = 1),
            v(2, "show", hour, at("2026-03-15", 20), series = true, ep = 2),
            v(0, "show", hour, at("2026-03-15", 20), series = true, ep = 2),
            // Early morning
            v(2, "show", hour / 2, at("2026-03-10", 6, 10), series = true, ep = 3),
            // Last year
            v(0, "show", 5 * hour, at("2025-12-01", 20), series = true, ep = 9),
        )
        val w = Fun.wrappedPlus(views, titles, LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"), at("2026-12-31", 12), zone = zone)

        assertEquals("show", w.topTitles.first().contentId)
        assertEquals(3, w.topTitles.first().watchers)
        assertEquals(5 * hour, w.previousMs)

        val quiz = assertNotNull(w.quiz)
        assertEquals("Horror", quiz.genre)
        assertEquals(1, quiz.answer)

        // Sunday (7) at 8pm, two different weeks
        assertEquals(7 to 3, w.peak)
        assertEquals(2, w.peakWeeks)
        // 1:30am and 2:15am count as Saturday night
        assertEquals(4 * hour, w.heat[5][5])

        assertEquals(1, w.latest?.first)
        assertEquals(at("2026-03-08", 2, 15), w.latest?.second)
        assertEquals(2, w.earliest?.first)

        val late = w.awards.first { it.key == "late" }
        assertEquals(1, late.profileIndex)
        assertTrue(w.awards.none { it.key == "reactions" })
    }

    @Test
    fun reactionsAndCriticAwards() {
        val views = listOf(v(0, "a", hour, at("2026-02-01", 20)), v(1, "b", hour, at("2026-02-02", 20)))
        val ratings = listOf(
            Rating(id = "1", profileIndex = 0, contentId = "a", stars = 2, at = at("2026-02-01", 22)),
            Rating(id = "2", profileIndex = 0, contentId = "b", stars = 3, at = at("2026-02-02", 22)),
            Rating(id = "3", profileIndex = 0, contentId = "c", stars = 1, at = at("2026-02-03", 22)),
            Rating(id = "4", profileIndex = 1, contentId = "a", stars = 5, at = at("2026-02-01", 22)),
        )
        val w = Fun.wrappedPlus(views, emptyMap(), LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"), at("2026-03-01", 12),
            ratings = ratings, reactionsSent = mapOf(1 to 4, 0 to 1), zone = zone)
        assertEquals(1, w.awards.first { it.key == "reactions" }.profileIndex)
        val critic = w.awards.first { it.key == "critic" }
        assertEquals(0, critic.profileIndex)
        assertEquals("average 2.0 stars", critic.value)
    }
}
