package app.nuviowatch.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FamilyDbTest {
    @Test
    fun storesEncryptedRowsAndReadsThemBack() = runBlocking {
        val server = MockWebServer().apply { start() }
        val db = FamilyDb(server.url("/").toString(), "pk", "user-1")
        assertEquals(FamilyDb.sha256Hex(db.familyKey), db.family)
        assertNotEquals(db.family, FamilyDb("x", "pk", "user-2").family)

        server.enqueue(MockResponse().setResponseCode(201))
        db.add("r1", "reaction", """{"type":"reaction"}""", 42)
        val sent = server.takeRequest()
        assertEquals("/rest/v1/messages", sent.path)
        assertEquals(db.familyKey, sent.getHeader("x-family-key"))
        assertEquals("pk", sent.getHeader("apikey"))
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"family\":\"${db.family}\""))
        assertTrue(!body.contains("reaction\\\""), "payload is encrypted")

        val sealed = LiveChannel("user-1").encryptText("""{"type":"vote"}""")
        server.enqueue(MockResponse().setBody("""[{"id":"v1","family":"f","kind":"vote","payload":"$sealed","at":50},{"id":"bad","family":"f","kind":"vote","payload":"junk","at":51}]"""))
        val rows = db.since(40, setOf("vote"))
        assertEquals(listOf("""{"type":"vote"}"""), rows.map { it.text })
        assertTrue(server.takeRequest().path!!.contains("at=gt.40&kind=in.(vote)"))
        server.shutdown()
    }

    @Test
    fun exactPlayingTime() {
        val m = 60_000L
        fun e(state: PlayState, at: Long, device: String = "tv", title: String = "Show") = LiveEvent(device, device, null, title, null, state, at = at)
        val events = listOf(
            e(PlayState.PLAYING, 0), e(PlayState.PLAYING, 1 * m), e(PlayState.PAUSED, 3 * m), // 3 min playing
            e(PlayState.PLAYING, 10 * m),                                                        // then silent: capped at 5 min
            e(PlayState.PLAYING, 0, device = "mac", title = "Film"), e(PlayState.STOPPED, 2 * m, device = "mac", title = "Film"),
        )
        val totals = LiveSessions.playingTime(events, 0, 100 * m)
        assertEquals(8 * m, totals["tv" to "Show"])
        assertEquals(2 * m, totals["mac" to "Film"])
        assertEquals(2 * m, LiveSessions.playingTime(events, 11 * m, 13 * m)["tv" to "Show"], "clipped to the range")
    }
}
