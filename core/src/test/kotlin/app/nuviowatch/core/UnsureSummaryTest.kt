package app.nuviowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals

class UnsureSummaryTest {
    private val min = 60_000L

    @Test
    fun unsureWordingForUnfinished() {
        val film = SessionItem("tt1", "tt1", "movie", position = 40 * min, duration = 120 * min)
        val session = WatchSession(1, 0, 0, listOf(film))
        val line = SessionTracker.summarize("Mani", session, unsure = true) { "Film" }.single()
        assertEquals("Mani was watching Film", line.title)
        assertEquals("last seen at 33%", line.text)
        val sure = SessionTracker.summarize("Mani", session) { "Film" }.single()
        assertEquals("Mani watched Film", sure.title)
    }

    @Test
    fun finishedIsAlwaysFinished() {
        val film = SessionItem("tt1", "tt1", "movie", position = 118 * min, duration = 120 * min)
        val line = SessionTracker.summarize("Mani", WatchSession(1, 0, 0, listOf(film)), unsure = true) { "Film" }.single()
        assertEquals("Mani finished Film", line.title)
    }
}
