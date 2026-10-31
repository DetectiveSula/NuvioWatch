package app.nuviowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LiveRunsTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    private fun e(state: PlayState, at: Long, title: String = "Show", sub: String? = "S2 E1", pos: Long = 0, dur: Long = 45 * min) =
        LiveEvent("tv", "Living room TV", 1, title, sub, state, pos, dur, at)

    @Test
    fun episodesAddUpAndStopGivesSummary() {
        val runs = LiveRuns()
        assertNull(runs.apply(e(PlayState.PLAYING, t0, pos = 0)))
        // Heartbeats every minute for 45 minutes
        for (m in 1..44) assertNull(runs.apply(e(PlayState.PLAYING, t0 + m * min, pos = m * min)))
        assertNull(runs.apply(e(PlayState.PLAYING, t0 + 45 * min, sub = "S2 E2", pos = 0)))
        for (m in 1..9) runs.apply(e(PlayState.PLAYING, t0 + (45 + m) * min, sub = "S2 E2", pos = m * min))
        val done = assertNotNull(runs.apply(e(PlayState.STOPPED, t0 + 55 * min, sub = "S2 E2", pos = 10 * min)))
        assertEquals(55 * min, done.playedMs)
        assertEquals(
            "Mum watched 2 episodes of Show" to "S2 E1–E2 · 55m",
            LiveRuns.summarize("Mum", done),
        )
        assertEquals(emptyMap(), runs.states)
    }

    @Test
    fun singleEpisodeSaysWhereTheyStopped() {
        val runs = LiveRuns()
        for (m in 0..19) runs.apply(e(PlayState.PLAYING, t0 + m * min, pos = m * min))
        runs.apply(e(PlayState.PAUSED, t0 + 20 * min, pos = 20 * min))
        val done = assertNotNull(runs.apply(e(PlayState.STOPPED, t0 + 30 * min, pos = 20 * min)))
        // Paused time doesn't count
        assertEquals(20 * min, done.playedMs)
        assertEquals("Mum watched Show" to "S2 · E1 · 20m · stopped at 44%", LiveRuns.summarize("Mum", done))
    }

    @Test
    fun finishedFilmAndSwitchingTitles() {
        val runs = LiveRuns()
        runs.apply(e(PlayState.PLAYING, t0, title = "Film", sub = null, pos = 100 * min, dur = 110 * min))
        val film = assertNotNull(runs.apply(e(PlayState.PLAYING, t0 + 5 * min, title = "Other", sub = null, pos = 0, dur = 90 * min)))
        assertEquals("Mum finished Film" to "5m · finished", LiveRuns.summarize("Mum", film))
        assertEquals("Other", runs.states.getValue("tv").title)
    }

    @Test
    fun openedPausedIsNothingAndSilentDevicesFinish() {
        val runs = LiveRuns()
        assertNull(runs.apply(e(PlayState.PAUSED, t0)))
        assertNull(runs.apply(e(PlayState.STOPPED, t0 + min)))
        runs.apply(e(PlayState.PLAYING, t0 + 2 * min))
        runs.apply(e(PlayState.PLAYING, t0 + 3 * min, pos = min))
        assertEquals(emptyList(), runs.expire(t0 + 5 * min))
        val gone = runs.expire(t0 + 10 * min).single()
        // A minute of reports, plus up to five more minutes after the last one
        assertEquals(6 * min, gone.playedMs)
    }

    @Test
    fun pausedDevicesGetLongerBeforeTheyCountAsStopped() {
        val tracker = LiveTracker()
        tracker.apply(e(PlayState.PLAYING, t0))
        tracker.apply(e(PlayState.PAUSED, t0 + min))
        assertEquals(emptyList(), tracker.expire(t0 + 10 * min))
        assertEquals(1, tracker.expire(t0 + 17 * min).size)
    }
}
