package app.nuviowatch.core

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DigestTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 30, 21, 0).toInstant(zone).toEpochMilli()
    private val min = 60_000L
    private val names = mapOf(1 to "Mani", 2 to "Ishti")

    private fun at(daysAgo: Long, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, 30, hour, minute).minusDays(daysAgo).toInstant(zone).toEpochMilli()

    private fun v(profile: Int, id: String, series: Boolean, ep: Int?, ms: Long, atMs: Long) =
        Viewing(profile, id, series, if (series) 6 else null, ep, id, ms, atMs, completed = true, fraction = 1f)

    private val viewings = listOf(
        v(1, "show", true, 1, 45 * min, at(0, 19)),
        v(1, "show", true, 2, 45 * min, at(0, 20)),
        v(2, "film", false, null, 100 * min, at(0, 16)),
        v(1, "show", true, 3, 45 * min, at(2, 23, 40)),
        v(2, "old", false, null, 90 * min, at(10, 20)),
    )
    private val titles = mapOf("show" to "Shakespeare & Hathaway", "film" to "The Social Reckoning")

    @Test
    fun daily() {
        val d = DigestBuilder.build(DigestPeriod.DAY, viewings, names, titles::get, now, zone)!!
        assertEquals("Today: 3h 10m watched", d.title)
        assertEquals("Ishti 1h 40m · Mani 1h 30m", d.summary)
        assertEquals(listOf("Ishti: 1h 40m · The Social Reckoning", "Mani: 1h 30m · Shakespeare & Hathaway"), d.lines)
    }

    @Test
    fun weeklyAwards() {
        val d = DigestBuilder.build(DigestPeriod.WEEK, viewings, names, titles::get, now, zone)!!
        assertEquals("This week: 3h 55m watched", d.title)
        assertEquals("🏆 Top watcher: Mani (2h 15m)", d.lines[0])
        assertEquals("📺 Show of the week: Shakespeare & Hathaway", d.lines[1])
        assertEquals("🍿 Binge of the week: Mani, 3 episodes of Shakespeare & Hathaway", d.lines[2])
        assertEquals("🌙 Night owl: Mani, still watching at 23:40", d.lines[3])
    }

    @Test
    fun nothingWatched() {
        assertNull(DigestBuilder.build(DigestPeriod.DAY, viewings.filter { it.atMs < at(1, 0) }, names, titles::get, now, zone))
        assertTrue(DigestBuilder.build(DigestPeriod.WEEK, emptyList(), names, titles::get, now, zone) == null)
    }
}
