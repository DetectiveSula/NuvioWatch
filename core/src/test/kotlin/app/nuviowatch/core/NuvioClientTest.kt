package app.nuviowatch.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NuvioClientTest {
    private lateinit var server: MockWebServer
    private val store = object : SessionStore {
        override var session: Session? = null
    }
    private var now = 1_700_000_000_000L

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun client() = NuvioClient(ServerConfig(server.url("/").toString(), "pk"), store, clock = { now })

    private fun tokenJson(access: String, expiresAt: Long) =
        """{"access_token":"$access","refresh_token":"r-$access","expires_at":$expiresAt,"user":{"id":"u1","email":"a@b.c"}}"""

    @Test
    fun signInStoresSessionAndSendsKey() = runBlocking {
        server.enqueue(MockResponse().setBody(tokenJson("t1", now / 1000 + 3600)))
        client().signIn(" a@b.c ", "pw")

        val request = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", request.path)
        assertEquals("pk", request.getHeader("apikey"))
        assertTrue(request.body.readUtf8().contains("\"email\":\"a@b.c\""))
        assertEquals("t1", store.session?.accessToken)
        assertEquals("a@b.c", store.session?.email)
    }

    @Test
    fun wrongPasswordGivesFriendlyMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Invalid login credentials"}"""))
        val error = assertFailsWith<NuvioException> { client().signIn("a@b.c", "bad") }
        assertEquals("Wrong email or password", error.message)
        assertNull(store.session)
    }

    @Test
    fun profilesAreReadWithBearerToken() = runBlocking {
        store.session = Session("t1", "r1", now / 1000 + 3600)
        server.enqueue(MockResponse().setBody("""[{"profile_index":2,"name":"Sam","avatar_color_hex":"#ff0000","extra":1},{"profile_index":1,"name":"Ali"}]"""))
        val profiles = client().profiles()

        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/sync_pull_profiles", request.path)
        assertEquals("Bearer t1", request.getHeader("Authorization"))
        assertEquals(listOf("Ali", "Sam"), profiles.map { it.name })
    }

    @Test
    fun expiredTokenIsRefreshedFirst() = runBlocking {
        store.session = Session("old", "r-old", now / 1000 + 10)
        server.enqueue(MockResponse().setBody(tokenJson("new", now / 1000 + 3600)))
        server.enqueue(MockResponse().setBody("42"))
        assertEquals(42L, client().progressCursor(1))

        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        assertEquals("new", store.session?.accessToken)
    }

    @Test
    fun unauthorisedCallRefreshesAndRetriesOnce() = runBlocking {
        store.session = Session("old", "r-old", now / 1000 + 3600)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"JWT expired"}"""))
        server.enqueue(MockResponse().setBody(tokenJson("new", now / 1000 + 3600)))
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(emptyList(), client().progress(1))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun rejectedRefreshSignsOut() = runBlocking {
        store.session = Session("old", "r-old", now / 1000 - 10)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error_description":"Refresh Token Not Found"}"""))
        assertFailsWith<NuvioException> { client().profiles() }
        assertNull(store.session)
    }

    @Test
    fun progressChangesPagesThroughTheFeed() = runBlocking {
        store.session = Session("t", "r", now / 1000 + 3600)
        server.enqueue(MockResponse().setBody("""[{"event_id":11,"operation":"upsert","content_id":"tt1"},{"event_id":12,"operation":"delete","content_id":"tt2"}]"""))
        server.enqueue(MockResponse().setBody("""[{"event_id":13,"operation":"upsert","content_id":"tt3"}]"""))
        val events = client().progressChanges(profileId = 1, sinceEventId = 10, pageSize = 2)

        assertEquals(listOf(11L, 12L, 13L), events.map { it.eventId })
        assertTrue(server.takeRequest().body.readUtf8().contains("\"p_since_event_id\":10"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"p_since_event_id\":12"))
    }

    @Test
    fun watchedHistoryPages() = runBlocking {
        store.session = Session("t", "r", now / 1000 + 3600)
        server.enqueue(MockResponse().setBody("""[{"content_id":"tt1","content_type":"movie","title":"A","watched_at":1}]"""))
        val items = client().watched(profileId = 3, pageSize = 2)
        assertEquals("A", items.single().title)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"p_page\":1"))
    }
}
