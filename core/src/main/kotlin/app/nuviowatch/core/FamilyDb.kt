package app.nuviowatch.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

/**
 * The family's shared database (a Supabase project the family owns).
 *
 * Every device on the same Nuvio account derives the same family key from the account
 * id. Requests carry it in an `x-family-key` header, and the database's row-level
 * security only shows and accepts rows whose `family` is the hash of that key. Payloads
 * are encrypted with the live channel's key, so stored rows are unreadable on their own.
 */
class FamilyDb(
    private val url: String,
    private val publishableKey: String,
    userId: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val channel = LiveChannel(userId)
    val familyKey: String = sha256Hex("nuvio-watch-db:$userId")
    val family: String = sha256Hex(familyKey)

    @Serializable
    data class Row(val id: String, val family: String, val kind: String, val payload: String, val at: Long)

    data class Message(val id: String, val kind: String, val text: String, val at: Long)

    private fun request(path: String) = Request.Builder()
        .url("${url.trimEnd('/')}/rest/v1/$path")
        .header("apikey", publishableKey)
        .header("x-family-key", familyKey)

    /** Stores one message; storing the same id twice is ignored. */
    suspend fun add(id: String, kind: String, text: String, at: Long) = withContext(Dispatchers.IO) {
        val row = Row(id, family, kind, channel.encryptText(text), at)
        http.newCall(
            request("messages")
                .header("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(NuvioClient.json.encodeToString(row).toRequestBody(JSON))
                .build(),
        ).execute().use { if (!it.isSuccessful) throw java.io.IOException("Database returned ${it.code}: ${it.body?.string()?.take(200)}") }
    }

    /** Messages stored after [sinceAt] (epoch ms), oldest first, decrypted. */
    suspend fun since(sinceAt: Long, kinds: Set<String>? = null, limit: Int = 500): List<Message> = withContext(Dispatchers.IO) {
        val kindFilter = kinds?.joinToString(",", prefix = "&kind=in.(", postfix = ")").orEmpty()
        http.newCall(
            request("messages?select=id,family,kind,payload,at&at=gt.$sinceAt$kindFilter&order=at.asc&limit=$limit").get().build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Database returned ${response.code}")
            NuvioClient.json.decodeFromString<List<Row>>(response.body?.string().orEmpty())
                .mapNotNull { row -> channel.decryptText(row.payload)?.let { Message(row.id, row.kind, it, row.at) } }
        }
    }

    /** Saves the family dashboard summary that the web page shows. */
    suspend fun saveDashboard(json: String) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("family", family)
            put("payload", channel.encryptText(json))
        }
        http.newCall(
            request("dashboards")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(body.toString().toRequestBody(JSON))
                .build(),
        ).execute().use { if (!it.isSuccessful) throw java.io.IOException("Database returned ${it.code}") }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Exact viewing time from live reports: time spent in "playing" between reports. */
object LiveSessions {
    /**
     * Milliseconds each device spent playing each title within [fromMs, toMs).
     * A playing stretch ends at the device's next report, or after [maxGapMs]
     * if the device went silent (switched off).
     */
    fun playingTime(events: List<LiveEvent>, fromMs: Long, toMs: Long, maxGapMs: Long = 5 * 60_000L): Map<Pair<String, String>, Long> {
        val totals = mutableMapOf<Pair<String, String>, Long>()
        for ((_, deviceEvents) in events.groupBy { it.deviceId }) {
            val sorted = deviceEvents.sortedBy { it.at }
            sorted.forEachIndexed { i, e ->
                if (e.state != PlayState.PLAYING) return@forEachIndexed
                val end = minOf(sorted.getOrNull(i + 1)?.at ?: (e.at + maxGapMs), e.at + maxGapMs, toMs)
                val start = maxOf(e.at, fromMs)
                if (end > start) totals.merge(e.deviceId to e.title, end - start, Long::plus)
            }
        }
        return totals
    }
}
