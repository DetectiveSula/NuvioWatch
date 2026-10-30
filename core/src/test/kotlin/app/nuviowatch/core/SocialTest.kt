package app.nuviowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SocialTest {
    private val t0 = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test
    fun messagesRoundTrip() {
        val reaction = Reaction(id = "r1", fromDeviceId = "a", fromName = "Mani", toProfile = 2, toDeviceId = "tv", emoji = "😱", title = "Show", at = t0)
        val vote = Vote(predictionId = "p1", voterId = "a", voterName = "Mani", yes = true, at = t0)
        val event = LiveEvent("tv", "TV", null, "Show", null, PlayState.PLAYING, at = t0)
        assertEquals(reaction, Social.parse(Social.encode(reaction)))
        assertEquals(vote, Social.parse(Social.encode(vote)))
        assertIs<LiveEvent>(Social.parse(Social.encode(event)))
        assertNull(Social.parse("{\"type\":\"reaction\"}"))
    }

    @Test
    fun settlesPredictions() {
        val eps = (1..3).map { EpisodeInfo(6, it, releasedMs = t0 - 30 * day) }
        val p = Prediction(id = "p1", byName = "Ishti", profileIndex = 1, kind = PredictionKind.FINISH_SEASON, contentId = "show", season = 6, deadlineMs = t0 + 3 * day, createdAt = t0)
        fun ep(e: Int, at: Long) = Viewing(1, "show", true, 6, e, "Show", 45 * 60_000L, at, true, 1f)

        assertEquals(Outcome.PENDING, Social.resolve(p, listOf(ep(1, t0 + 1000), ep(2, t0 + 2000)), mapOf("show" to eps), t0 + day))
        assertEquals(Outcome.YES, Social.resolve(p, listOf(ep(1, t0 - day), ep(2, t0 + 1000), ep(3, t0 + 2000)), mapOf("show" to eps), t0 + day))
        assertEquals(Outcome.NO, Social.resolve(p, listOf(ep(1, t0 + 1000)), mapOf("show" to eps), t0 + 4 * day))

        val hours = p.copy(kind = PredictionKind.WATCH_HOURS, hours = 1, contentId = null, season = null)
        assertEquals(Outcome.YES, Social.resolve(hours, listOf(ep(1, t0 + 1000), ep(2, t0 + 2000)), emptyMap(), t0 + day))
    }

    @Test
    fun latestVoteCounts() {
        val votes = listOf(
            Vote(predictionId = "p1", voterId = "a", voterName = "Mani", yes = true, at = 1),
            Vote(predictionId = "p1", voterId = "a", voterName = "Mani", yes = false, at = 2),
            Vote(predictionId = "p1", voterId = "b", voterName = "Haris", yes = true, at = 3),
            Vote(predictionId = "p2", voterId = "c", voterName = "Libby", yes = true, at = 4),
        )
        val t = Social.tally("p1", votes)
        assertEquals(listOf("Haris"), t.yes)
        assertEquals(listOf("Mani"), t.no)
    }
}
