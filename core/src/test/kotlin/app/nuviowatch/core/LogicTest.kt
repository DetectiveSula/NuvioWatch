package app.nuviowatch.core

import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertPolicyTest {
    private val profile = Profile(index = 2, name = "Sam")
    private val now = 1_700_000_000_000L

    private fun event(id: Long, content: String, position: Long, duration: Long = 3_000_000, agoMs: Long = 60_000, key: String = "$content:1:$id") =
        ProgressEvent(id, "upsert", key, content, "series", "$content:1:1", 1, 1, position, duration, now - agoMs)

    @Test
    fun freshProgressTriggersWatchingAlert() {
        val alerts = AlertPolicy().evaluate(profile, listOf(event(1, "tt1", 600_000)), now) { null }
        assertEquals(AlertKind.WATCHING, alerts.single().kind)
    }

    @Test
    fun oldProgressIsIgnored() {
        val alerts = AlertPolicy().evaluate(profile, listOf(event(1, "tt1", 600_000, agoMs = 60 * 60_000)), now) { null }
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun bingingTheSameShowAlertsOnce() {
        val policy = AlertPolicy()
        val first = policy.evaluate(profile, listOf(event(1, "tt1", 600_000), event(2, "tt1", 900_000)), now) { null }
        assertEquals(1, first.size)
        // Negative "ago" puts the change just before the later check time.
        val again = policy.evaluate(profile, listOf(event(3, "tt1", 100_000, agoMs = -29 * 60_000)), now + 30 * 60_000) { now }
        assertTrue(again.isEmpty())
        val later = policy.evaluate(profile, listOf(event(4, "tt1", 100_000, agoMs = -119 * 60_000)), now + 2 * 60 * 60_000) { now }
        assertEquals(1, later.size)
    }

    @Test
    fun finishedOnlyWhenEnabled() {
        val done = listOf(event(1, "tt1", 2_900_000))
        assertTrue(AlertPolicy(notifyFinished = false).evaluate(profile, done, now) { null }.isEmpty())
        assertEquals(AlertKind.FINISHED, AlertPolicy(notifyFinished = true).evaluate(profile, done, now) { null }.single().kind)
    }

    @Test
    fun deletesAreIgnored() {
        val alerts = AlertPolicy().evaluate(profile, listOf(event(1, "tt1", 600_000).copy(operation = "delete")), now) { null }
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun liveState() {
        val entry = event(1, "tt1", 600_000, agoMs = 5 * 60_000).toEntry()
        assertEquals(LiveState.ACTIVE_NOW, AlertPolicy.liveState(entry, now))
        assertEquals(LiveState.RECENT, AlertPolicy.liveState(entry, now + 60 * 60_000))
        assertEquals(LiveState.IDLE, AlertPolicy.liveState(entry, now + 24 * 60 * 60_000))
        assertEquals(LiveState.IDLE, AlertPolicy.liveState(null, now))
    }
}

class StatsTest {
    private val now = 1_700_000_000_000L // 2023-11-14 22:13 UTC

    @Test
    fun mergesProgressAndHistory() {
        val progress = listOf(
            ProgressEntry("k1", "tt1", "series", "tt1:1:1", 1, 1, position = 2_700_000, duration = 2_800_000, lastWatched = now - 1000),
            ProgressEntry("k2", "tt1", "series", "tt1:1:2", 1, 2, position = 600_000, duration = 2_800_000, lastWatched = now),
            ProgressEntry("k3", "tt9", "movie", "tt9", position = 1_200_000, duration = 6_000_000, lastWatched = (now - 3_600_000) / 1000),
        )
        val watched = listOf(
            WatchedItem("tt1", "series", "Show", 1, 1, now - 1000),
            WatchedItem("tt2", "movie", "Film", watchedAt = now - 86_400_000),
            WatchedItem("tt3", "series", "Whole show"),
        )
        val titles = mapOf("tt2" to TitleInfo("Film", genres = listOf("Drama"), runtimeMinutes = 100), "tt1" to TitleInfo("Show", genres = listOf("Drama", "Crime")))

        val viewings = StatsCalculator.viewings(1, progress, watched, titles)
        assertEquals(4, viewings.size)
        val stats = StatsCalculator.compute(viewings, titles, now, ZoneOffset.UTC)

        assertEquals(1, stats.movies)
        assertEquals(1, stats.episodes)
        assertEquals(1, stats.shows)
        assertEquals(2, stats.inProgress)
        assertEquals(2_800_000L + 600_000 + 1_200_000 + 6_000_000, stats.totalWatchedMs)
        assertEquals("tt2", stats.topTitles.first().contentId)
        assertEquals("Drama" to 2, stats.topGenres.first())
        assertEquals(30, stats.days.size)
        assertEquals(100L, stats.days[28].minutes)
        assertEquals(now, stats.lastActivityMs)
        assertEquals(stats.totalWatchedMs / 60_000, stats.hours.sum(), "minutes are spread over hours")
    }

    @Test
    fun emptyInput() {
        assertEquals(Stats.EMPTY, StatsCalculator.compute(emptyList(), emptyMap(), now))
    }
}

class KeyExtractorTest {
    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "eyJhbGciOiJIUzI1NiJ9." + enc.encodeToString(payload.toByteArray()) + "." + "s".repeat(43)
    }

    @Test
    fun findsKeysAndUrlsInsideBinaryNoise() {
        val anon = jwt("""{"iss":"supabase","ref":"abcdefghijklmnopqrst","role":"anon"}""")
        val service = jwt("""{"iss":"supabase","role":"service_role"}""")
        val blob = "\u0000\u0013https://api.nuvio.tv\u0000.sb_publishable_AbCdEf123456_-xyz7890\u0000$anon\u0001$service\u0000https://abcdefghijklmnopqrst.supabase.co/rest"
        val found = KeyExtractor.scan(blob.toByteArray(Charsets.ISO_8859_1))
        assertEquals(listOf("sb_publishable_AbCdEf123456_-xyz7890", anon), found.keys)
        assertEquals(listOf("https://api.nuvio.tv", "https://abcdefghijklmnopqrst.supabase.co"), found.urls)
    }
}

class CinemetaTest {
    @Test
    fun runtimes() {
        assertEquals(142, Cinemeta.parseRuntimeMinutes("142 min"))
        assertEquals(90, Cinemeta.parseRuntimeMinutes("1h 30min"))
        assertEquals(120, Cinemeta.parseRuntimeMinutes("2h"))
        assertEquals(45, Cinemeta.parseRuntimeMinutes("45"))
        assertNull(Cinemeta.parseRuntimeMinutes("N/A"))
        assertNull(Cinemeta.parseRuntimeMinutes(null))
    }

    @Test
    fun timestampsInSecondsAreNormalised() {
        assertEquals(1_700_000_000_000L, normalizeEpochMs(1_700_000_000L))
        assertEquals(1_700_000_000_000L, normalizeEpochMs(1_700_000_000_000L))
        assertTrue(isComplete(2_700_000, 3_000_000))
        assertTrue(!isComplete(60_000, 100_000), "short placeholder durations never count")
    }
}
