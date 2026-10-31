package app.nuviowatch.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreakTest {
    private val zone = ZoneOffset.UTC
    private fun at(day: String, hour: Int = 20) = LocalDate.parse(day).atTime(hour, 0).toInstant(zone).toEpochMilli()
    private fun v(day: String, ms: Long = 30 * 60_000L) = Viewing(0, "x", false, null, null, "x", ms, at(day), false, 0.5f)

    @Test
    fun recordedDaysJoinViewingsAndAMissedDayEndsIt() {
        val now = at("2026-10-31", 21)
        // Watched yesterday (synced) and today (seen live only)
        assertEquals(2, Fun.streaks(listOf(v("2026-10-30")), now, zone, setOf(LocalDate.parse("2026-10-31"))).first)
        // Nothing today yet: yesterday's streak still stands
        assertEquals(2, Fun.streaks(listOf(v("2026-10-29"), v("2026-10-30")), now, zone).first)
        // Missed yesterday: gone
        assertEquals(0, Fun.streaks(listOf(v("2026-10-28"), v("2026-10-29")), now, zone).first)
    }

    @Test
    fun anyAmountOfWatchingCounts() {
        val now = at("2026-10-31", 21)
        assertEquals(2, Fun.streaks(listOf(v("2026-10-30", 20_000L), v("2026-10-31", 30_000L)), now, zone).first)
    }

    @Test
    fun streakNotAtRiskOnceSeenToday() {
        val now = at("2026-10-31", 21)
        val past = listOf(v("2026-10-29"), v("2026-10-30"))
        assertEquals(2, Fun.streakAtRisk(past, now, zone))
        assertNull(Fun.streakAtRisk(past, now, zone, setOf(LocalDate.parse("2026-10-31"))))
    }
}
