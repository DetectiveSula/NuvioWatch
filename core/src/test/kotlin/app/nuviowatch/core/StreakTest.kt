package app.nuviowatch.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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
        // Missed yesterday: the weekly freeze keeps it alive (without adding a day)
        val frozen = Fun.streakRun(listOf(v("2026-10-28"), v("2026-10-29")), now, zone)
        assertEquals(2, frozen.days)
        assertEquals(LocalDate.parse("2026-10-30"), frozen.freezeOn)
        // Missed two days in a row: gone
        assertEquals(0, Fun.streaks(listOf(v("2026-10-27"), v("2026-10-28")), now, zone).first)
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

    @Test
    fun oneFreezeAWeek() {
        val now = at("2026-10-31", 21)
        // Watched 20–24, missed 25, watched 26–28, missed 29, watched 30–31: second miss is within a week, so it ends there.
        val days = listOf("2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24", "2026-10-26", "2026-10-27", "2026-10-28", "2026-10-30", "2026-10-31")
        // The 29th was recorded as a freeze when it happened; the 25th was within a week of it, so not.
        val run = Fun.streakRun(days.map { v(it) }, now, zone, freezes = setOf(LocalDate.parse("2026-10-29")))
        assertEquals(5, run.days) // 26, 27, 28, 30, 31
        assertEquals(LocalDate.parse("2026-10-26"), run.since)
        // Misses eight days apart are both forgiven
        val spaced = listOf("2026-10-20", "2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-31")
        assertEquals(10, Fun.streaks(spaced.map { v(it) }, now, zone, freezes = setOf(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-30"))).first)
        // Gaps from before freezes were recorded don't join streaks up
        assertEquals(9, Fun.streaks(spaced.map { v(it) }, now, zone).first)
    }

    @Test
    fun freezeCoversTodayOnlyIfUnused() {
        val now = at("2026-10-31", 21)
        assertTrue(Fun.freezeCoversToday(listOf(v("2026-10-29"), v("2026-10-30")), now, zone))
        assertFalse(Fun.freezeCoversToday(listOf(v("2026-10-27"), v("2026-10-28"), v("2026-10-30")), now, zone, freezes = setOf(LocalDate.parse("2026-10-29"))))
    }

    @Test
    fun milestones() {
        assertNull(Fun.streakMilestone(6))
        assertEquals(7, Fun.streakMilestone(9))
        assertEquals(30, Fun.streakMilestone(30))
    }

    @Test
    fun oldGapsStayBroken() {
        // Watched 27, missed 28 (long ago, no freeze recorded), watched 29, 30, 31.
        val now = at("2026-10-31", 21)
        val run = Fun.streakRun(listOf(v("2026-10-27"), v("2026-10-29"), v("2026-10-30"), v("2026-10-31")), now, zone)
        assertEquals(3, run.days)
        assertNull(run.freezeOn)
    }
}
