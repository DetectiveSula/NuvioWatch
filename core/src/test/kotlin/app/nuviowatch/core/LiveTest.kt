package app.nuviowatch.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTest {
    private val t0 = 1_700_000_000_000L
    private fun event(state: PlayState, at: Long, title: String = "Film", device: String = "tv") =
        LiveEvent(device, "Living room TV", null, title, null, state, 60_000, 6_000_000, at)

    @Test
    fun sameAccountSharesChannelAndKey() {
        val a = LiveChannel("user-1")
        val b = LiveChannel("user-1")
        val other = LiveChannel("user-2")
        assertEquals(a.topic, b.topic)
        assertNotEquals(a.topic, other.topic)

        val sealed = a.encrypt(event(PlayState.PLAYING, t0))
        assertEquals("Film", b.decrypt(sealed)?.title)
        assertNull(other.decrypt(sealed), "another account can't read it")
        assertNull(a.decrypt("not a message"))
    }

    @Test
    fun startStopAndTitleChanges() {
        val tracker = LiveTracker()
        assertEquals(LiveChange.STARTED, tracker.apply(event(PlayState.PLAYING, t0)))
        assertNull(tracker.apply(event(PlayState.PAUSED, t0 + 1000)), "pausing isn't news")
        assertNull(tracker.apply(event(PlayState.PLAYING, t0 + 2000)), "resuming the same title isn't news")
        assertEquals(LiveChange.STARTED, tracker.apply(event(PlayState.PLAYING, t0 + 3000, title = "Next")))
        assertEquals(LiveChange.STOPPED, tracker.apply(event(PlayState.STOPPED, t0 + 4000, title = "Next")))
        assertNull(tracker.apply(event(PlayState.STOPPED, t0 + 5000, title = "Next")))
        assertNull(tracker.apply(event(PlayState.PLAYING, t0 + 100)), "older than what we have")
    }

    @Test
    fun silentDevicesExpire() {
        val tracker = LiveTracker(staleMs = 60_000)
        tracker.apply(event(PlayState.PLAYING, t0))
        assertTrue(tracker.expire(t0 + 30_000).isEmpty())
        assertEquals(PlayState.STOPPED, tracker.expire(t0 + 90_000).single().state)
        assertEquals(PlayState.STOPPED, tracker.states.getValue("tv").state)
    }

    @Test
    fun positionKeepsMovingWhilePlaying() {
        assertEquals(90_000, LiveTracker.estimatedPosition(event(PlayState.PLAYING, t0), t0 + 30_000))
        assertEquals(60_000, LiveTracker.estimatedPosition(event(PlayState.PAUSED, t0), t0 + 30_000))
    }

    @Test
    fun relayPublishesAndStreamsEncryptedEvents() = runBlocking {
        val server = MockWebServer().apply { start() }
        val channel = LiveChannel("user-1")
        val relay = LiveRelay(channel, baseUrl = server.url("/").toString().trimEnd('/'))

        server.enqueue(MockResponse().setBody("{\"id\":\"x\"}"))
        relay.publish(event(PlayState.PLAYING, t0))
        val sent = server.takeRequest()
        assertEquals("/${channel.topic}", sent.path)
        assertEquals("Film", channel.decrypt(sent.body.readUtf8())?.title)

        val sealed = channel.encrypt(event(PlayState.PAUSED, t0 + 1))
        server.enqueue(
            MockResponse().setBody(
                "{\"id\":\"o1\",\"event\":\"open\"}\n" +
                    "{\"id\":\"m1\",\"event\":\"message\",\"message\":\"$sealed\"}\n" +
                    "{\"id\":\"m2\",\"event\":\"message\",\"message\":\"junk\"}\n" +
                    "{\"id\":\"k1\",\"event\":\"keepalive\"}\n",
            ),
        )
        val received = mutableListOf<LiveEvent>()
        val last = relay.listen("10m") { _, text -> (Social.parse(text) as? LiveEvent)?.let { received += it } }
        assertEquals(listOf(PlayState.PAUSED), received.map { it.state })
        assertEquals("m2", last)
        assertTrue(server.takeRequest().path!!.endsWith("/json?since=10m"))
        server.shutdown()
    }
}
