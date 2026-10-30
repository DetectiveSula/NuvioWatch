package app.nuviowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {
    private val tracker = SessionTracker()
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L
    private var nextId = 1L

    private fun episode(ep: Int, position: Long, at: Long, duration: Long = 45 * min) =
        ProgressEvent(nextId++, "upsert", "tt5:6:$ep", "tt5", "series", "tt5:6:$ep", 6, ep, position, duration, at)

    private fun film(position: Long, at: Long, duration: Long = 106 * min) =
        ProgressEvent(nextId++, "upsert", "tt9", "tt9", "movie", "tt9", null, null, position, duration, at)

    @Test
    fun bingeBecomesOneSummaryAfterQuietPeriod() {
        var s = tracker.record(null, 1, listOf(episode(1, 20 * min, t0)), t0, { null })
        s = tracker.record(s, 1, listOf(episode(1, 44 * min, t0 + 25 * min), episode(2, 44 * min, t0 + 70 * min)), t0 + 70 * min) { null }
        s = tracker.record(s, 1, listOf(episode(3, 30 * min, t0 + 105 * min)), t0 + 105 * min) { null }
        assertNotNull(s)
        assertFalse(tracker.isDue(s, t0 + 115 * min), "still within the quiet period")
        assertTrue(tracker.isDue(s, t0 + 126 * min))

        val lines = SessionTracker.summarize("Mani", s) { "Show" }
        assertEquals("Mani watched 3 episodes of Show", lines.single().title)
        assertEquals("S6 E1–E3 · 1h 58m", lines.single().text)
    }

    @Test
    fun resumedFilmCountsOnlyNewTime() {
        val s = tracker.record(null, 2, listOf(film(63 * min, t0)), t0) { key -> if (key == "tt9") 20 * min else null }!!
        val line = SessionTracker.summarize("Ishti", s) { "The Social Reckoning" }.single()
        assertEquals("Ishti watched The Social Reckoning", line.title)
        assertEquals("43m · stopped at 59%", line.text)
    }

    @Test
    fun finishedFilm() {
        val s = tracker.record(null, 2, listOf(film(105 * min, t0)), t0) { null }!!
        assertEquals("Ishti finished Film", SessionTracker.summarize("Ishti", s) { "Film" }.single().title)
    }

    @Test
    fun laterActivityUpdatesTheSameSession() {
        var s = tracker.record(null, 1, listOf(episode(1, 5 * min, t0)), t0) { null }!!
        assertTrue(tracker.isDue(s, t0 + 21 * min))
        s = s.copy(notifiedAt = t0 + 21 * min)
        assertFalse(tracker.isDue(s, t0 + 30 * min), "nothing new since the summary")

        s = tracker.record(s, 1, listOf(episode(1, 45 * min, t0 + 45 * min)), t0 + 45 * min) { null }!!
        assertEquals(t0, s.startedAt, "same session, so the same notification is updated")
        assertTrue(s.isUpdate)
        assertTrue(tracker.isDue(s, t0 + 66 * min))
        assertEquals("S6 · E1 · 45m · finished", SessionTracker.summarize("Mani", s) { "Show" }.single().text)
    }

    @Test
    fun oldEventsAndDeletesAreIgnored() {
        val old = tracker.record(null, 1, listOf(episode(1, 5 * min, t0 - 5 * 60 * min)), t0) { null }
        assertNull(old)
        val deleted = tracker.record(null, 1, listOf(episode(1, 5 * min, t0).copy(operation = "delete")), t0) { null }
        assertNull(deleted)
    }

    @Test
    fun sessionsExpire() {
        val s = tracker.record(null, 1, listOf(episode(1, 5 * min, t0)), t0) { null }!!
        assertFalse(tracker.isExpired(s, t0 + 3 * 60 * min))
        assertTrue(tracker.isExpired(s, t0 + 5 * 60 * min))
    }
}
