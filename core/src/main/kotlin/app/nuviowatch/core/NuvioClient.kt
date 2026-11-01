package app.nuviowatch.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Persists the signed-in session so the app stays logged in. */
interface SessionStore {
    var session: Session?
}

class NuvioException(message: String, val status: Int = 0) : Exception(message) {
    val isAuthError: Boolean get() = status == 401 || status == 403
}

/**
 * Read-only client for the Nuvio sync backend (a Supabase project).
 *
 * It only calls Nuvio's "pull" functions, so it never changes anything in the account.
 */
class NuvioClient(
    private val config: ServerConfig,
    private val sessions: SessionStore,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val refreshLock = Mutex()

    suspend fun signIn(email: String, password: String): Session {
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }
        val session = tokenRequest("password", body)
        sessions.session = session
        return session
    }

    fun signOut() {
        sessions.session = null
    }

    suspend fun profiles(): List<Profile> =
        json.decodeFromJsonElement<List<Profile>>(rpc("sync_pull_profiles")).sortedBy { it.index }

    suspend fun progress(profileId: Int): List<ProgressEntry> =
        json.decodeFromJsonElement(rpc("sync_pull_watch_progress", buildJsonObject { put("p_profile_id", profileId) }))

    suspend fun watched(profileId: Int, pageSize: Int = 500): List<WatchedItem> {
        val all = mutableListOf<WatchedItem>()
        var page = 1
        while (true) {
            val items: List<WatchedItem> = json.decodeFromJsonElement(
                rpc(
                    "sync_pull_watched_items",
                    buildJsonObject {
                        put("p_profile_id", profileId)
                        put("p_page", page)
                        put("p_page_size", pageSize)
                    },
                ),
            )
            all += items
            if (items.size < pageSize || page >= MAX_PAGES) break
            page++
        }
        return all
    }

    /** The id of the newest progress change, used as the starting point for [progressChanges]. */
    suspend fun progressCursor(profileId: Int): Long {
        val result = rpc("sync_get_watch_progress_delta_cursor", buildJsonObject { put("p_profile_id", profileId) })
        return result.jsonPrimitive.longOrNull ?: 0L
    }

    /** Every progress change after [sinceEventId], oldest first. */
    /** Every field of the last progress updates read, exactly as Nuvio sent them (for troubleshooting). */
    @Volatile var lastRawEvents: List<String> = emptyList()
        private set

    suspend fun progressChanges(profileId: Int, sinceEventId: Long, pageSize: Int = 900): List<ProgressEvent> {
        val all = mutableListOf<ProgressEvent>()
        val raw = mutableListOf<String>()
        var cursor = sinceEventId
        var pages = 0
        while (true) {
            val element = rpc(
                "sync_pull_watch_progress_delta",
                buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_since_event_id", cursor)
                    put("p_limit", pageSize)
                },
            )
            (element as? kotlinx.serialization.json.JsonArray)?.forEach { raw += it.toString() }
            val events: List<ProgressEvent> = json.decodeFromJsonElement(element)
            if (events.isEmpty()) break
            all += events
            cursor = maxOf(cursor, events.maxOf { it.eventId })
            if (events.size < pageSize || ++pages >= MAX_PAGES) break
        }
        lastRawEvents = raw
        return all.sortedBy { it.eventId }
    }

    private suspend fun rpc(name: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
        val token = validAccessToken()
        val first = postRpc(name, params, token)
        if (first.status != 401) return first.unwrap()
        // The token may have been revoked early: refresh once and retry.
        val refreshed = refreshSession(force = true)
        return postRpc(name, params, refreshed.accessToken).unwrap()
    }

    private suspend fun postRpc(name: String, params: JsonObject, token: String): HttpResult =
        execute(
            Request.Builder()
                .url("${config.baseUrl}/rest/v1/rpc/$name")
                .header("apikey", config.publishableKey)
                .header("Authorization", "Bearer $token")
                .post(params.toString().toRequestBody(JSON_TYPE))
                .build(),
        )

    private suspend fun validAccessToken(): String {
        val session = sessions.session ?: throw NuvioException("Not signed in", 401)
        if (session.expiresAt * 1000 - clock() > TOKEN_REFRESH_MARGIN_MS) return session.accessToken
        return refreshSession(force = false).accessToken
    }

    private suspend fun refreshSession(force: Boolean): Session = refreshLock.withLock {
        val current = sessions.session ?: throw NuvioException("Not signed in", 401)
        // Another caller may already have refreshed while this one waited for the lock.
        if (!force && current.expiresAt * 1000 - clock() > TOKEN_REFRESH_MARGIN_MS) return current
        val refreshed = try {
            tokenRequest("refresh_token", buildJsonObject { put("refresh_token", current.refreshToken) })
        } catch (e: NuvioException) {
            if (e.status in 400..499) sessions.session = null
            throw e
        }
        sessions.session = refreshed.copy(email = refreshed.email.ifBlank { current.email })
        refreshed
    }

    private suspend fun tokenRequest(grantType: String, body: JsonObject): Session {
        val result = execute(
            Request.Builder()
                .url("${config.baseUrl}/auth/v1/token?grant_type=$grantType")
                .header("apikey", config.publishableKey)
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build(),
        ).unwrap().jsonObject
        val access = result["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw NuvioException("Sign-in response had no access token")
        val expiresAt = result["expires_at"]?.jsonPrimitive?.longOrNull
            ?: (clock() / 1000 + (result["expires_in"]?.jsonPrimitive?.long ?: 3600L))
        val user = result["user"] as? JsonObject
        return Session(
            accessToken = access,
            refreshToken = result["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            expiresAt = expiresAt,
            userId = user?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty(),
            email = user?.get("email")?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private class HttpResult(val status: Int, val body: String)

    private fun HttpResult.unwrap(): JsonElement {
        if (status in 200..299) return if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body)
        throw NuvioException(errorMessage(status, body), status)
    }

    private suspend fun execute(request: Request): HttpResult = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response: Response ->
                HttpResult(response.code, response.body?.string().orEmpty())
            }
        } catch (e: java.io.IOException) {
            throw NuvioException("Couldn't reach the Nuvio server (${e.message ?: e.javaClass.simpleName})")
        }
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
        private val JSON_TYPE = "application/json".toMediaType()
        private const val TOKEN_REFRESH_MARGIN_MS = 60_000L
        private const val MAX_PAGES = 50

        /** Supabase errors come in a few shapes; pick whichever message is present. */
        fun errorMessage(status: Int, body: String): String {
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            val text = listOf("error_description", "msg", "message", "error")
                .firstNotNullOfOrNull { key -> obj?.get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } }
            return when {
                text != null && status == 400 && text.contains("invalid", true) -> "Wrong email or password"
                text != null -> text
                status == 401 -> "Not authorised. Check the server key or sign in again."
                status == 429 -> "Nuvio is rate limiting requests. Try again in a minute."
                else -> "Nuvio server error ($status)"
            }
        }
    }
}
