package app.nuviowatch.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class PlayState { PLAYING, PAUSED, STOPPED }

/** What Nuvio is doing on one device, read from its Android media session. */
@Serializable
data class LiveEvent(
    val deviceId: String,
    val deviceName: String,
    /** Set when the device is assigned to a profile; otherwise the receiver works it out. */
    val profileIndex: Int? = null,
    val title: String,
    val subtitle: String? = null,
    val state: PlayState,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val at: Long,
)

/**
 * The private channel all devices on one Nuvio account share.
 *
 * The channel name and encryption key both come from the account's user id, so
 * devices signed in to the same account find each other without pairing, and
 * the relay only ever sees encrypted messages.
 */
class LiveChannel(userId: String) {
    val topic: String = "nw-" + sha256("nuvio-watch-topic:$userId").toHex().take(32)
    private val key = SecretKeySpec(sha256("nuvio-watch-key:$userId"), "AES")
    private val random = SecureRandom()

    fun encryptText(text: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(text.toByteArray()))
    }

    /** Null for anything that isn't a message from this account. */
    fun decryptText(message: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(message.trim())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        }
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    fun encrypt(event: LiveEvent): String = encryptText(NuvioClient.json.encodeToString(event))

    fun decrypt(message: String): LiveEvent? =
        decryptText(message)?.let { runCatching { NuvioClient.json.decodeFromString<LiveEvent>(it) }.getOrNull() }

    private companion object {
        fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}

/** Sends and receives live events through ntfy, a free public push relay. */
class LiveRelay(
    private val channel: LiveChannel,
    http: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://ntfy.sh",
) {
    private val sendHttp = http
    private val streamHttp = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    suspend fun publish(event: LiveEvent) = publishText(NuvioClient.json.encodeToString(event))

    /** Sends any message (playback, reaction, prediction, vote) as encrypted text. */
    suspend fun publishText(text: String) = withContext(Dispatchers.IO) {
        sendHttp.newCall(
            Request.Builder()
                .url("$baseUrl/${channel.topic}")
                .post(channel.encryptText(text).toRequestBody("text/plain".toMediaType()))
                .build(),
        ).execute().use { if (!it.isSuccessful) throw java.io.IOException("Relay returned ${it.code}") }
    }

    /**
     * Streams events until the connection drops. [since] is the last message id
     * seen (or a duration like "10m"); [onMessage] gets each decrypted message and its id so the
     * caller can resume from it. Returns the id of the last message read.
     */
    suspend fun listen(
        since: String,
        onOpen: () -> Unit = {},
        onMessage: suspend (id: String, text: String) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        var lastId: String? = null
        streamHttp.newCall(Request.Builder().url("$baseUrl/${channel.topic}/json?since=$since").build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Relay returned ${response.code}")
            val source = response.body?.source() ?: return@use
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                val obj = runCatching { NuvioClient.json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val kind = obj["event"]?.jsonPrimitive?.contentOrNull
                if (kind == "open") onOpen()
                if (kind != "message") continue
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                lastId = id
                val text = obj["message"]?.jsonPrimitive?.contentOrNull?.let(channel::decryptText) ?: continue
                onMessage(id, text)
            }
        }
        lastId
    }
}

enum class LiveChange { STARTED, STOPPED }

/**
 * Keeps the latest state of each device and spots when someone starts or stops.
 * A device that was playing but has gone silent for [staleMs] counts as stopped
 * (e.g. the TV was switched off).
 */
class LiveTracker(private val staleMs: Long = 4 * 60_000L, private val pausedStaleMs: Long = 15 * 60_000L) {
    private val devices = mutableMapOf<String, LiveEvent>()
    /** Title each device has been announced as playing, until it stops. */
    private val announced = mutableMapOf<String, String>()

    val states: Map<String, LiveEvent> get() = devices.toMap()

    fun restore(saved: Map<String, LiveEvent>) {
        devices.clear()
        devices.putAll(saved)
        announced.clear()
        saved.values.filter { it.state == PlayState.PLAYING }.forEach { announced[it.deviceId] = key(it) }
    }

    /**
     * "Started" the first time a title actually plays on a device (even if the
     * player opened paused), "stopped" when an announced title stops.
     */
    fun apply(event: LiveEvent): LiveChange? {
        val previous = devices[event.deviceId]
        if (previous != null && previous.at > event.at) return null // out of order
        devices[event.deviceId] = event
        return when (event.state) {
            PlayState.PLAYING -> if (announced[event.deviceId] != key(event)) {
                announced[event.deviceId] = key(event)
                LiveChange.STARTED
            } else {
                null
            }
            PlayState.PAUSED -> null
            PlayState.STOPPED -> if (announced.remove(event.deviceId) != null) LiveChange.STOPPED else null
        }
    }

    private fun key(e: LiveEvent) = "${e.title}|${e.subtitle.orEmpty()}"

    /** Marks silent devices as stopped and returns the ones that changed. */
    fun expire(nowMs: Long): List<LiveEvent> {
        // Paused players don't send heartbeats, so give them longer (making a cup of tea).
        val stale = devices.values.filter { it.state != PlayState.STOPPED && nowMs - it.at > if (it.state == PlayState.PAUSED) pausedStaleMs else staleMs }
        return stale.map { it.copy(state = PlayState.STOPPED, at = nowMs) }.onEach {
            devices[it.deviceId] = it
            announced.remove(it.deviceId)
        }
    }

    companion object {
        /** Where playback probably is now: playing devices keep moving between reports. */
        fun estimatedPosition(event: LiveEvent, nowMs: Long): Long =
            if (event.state == PlayState.PLAYING) {
                (event.positionMs + (nowMs - event.at)).coerceAtMost(event.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
            } else {
                event.positionMs
            }
    }
}

/** One film or episode within a live viewing run. */
@Serializable
data class LiveRunItem(val subtitle: String? = null, val playedMs: Long = 0, val positionMs: Long = 0, val durationMs: Long = 0) {
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 9 / 10
}

/** Someone watching one title on one device, from when it starts playing until it stops. */
@Serializable
data class LiveRun(
    val deviceId: String,
    val deviceName: String,
    val profileIndex: Int? = null,
    val title: String,
    val startedAt: Long,
    val lastAt: Long,
    val lastState: PlayState,
    val items: List<LiveRunItem> = emptyList(),
) {
    val playedMs: Long get() = items.sumOf { it.playedMs }
}

/**
 * Adds up what each device watched from its live reports, so a summary can be sent
 * the moment someone stops: time spent playing, the episodes they got through and
 * where they stopped. Switching to a different title finishes the previous run.
 */
class LiveRuns(
    private val maxGapMs: Long = 5 * 60_000L,
    private val playingStaleMs: Long = 4 * 60_000L,
    private val pausedStaleMs: Long = 15 * 60_000L,
) {
    private val runs = mutableMapOf<String, LiveRun>()

    val states: Map<String, LiveRun> get() = runs.toMap()

    fun restore(saved: Map<String, LiveRun>) {
        runs.clear()
        runs.putAll(saved)
    }

    /** Adds a report; returns a run that has just finished, if any. */
    fun apply(e: LiveEvent): LiveRun? {
        val previous = runs[e.deviceId]
        if (previous != null && e.at < previous.lastAt) return null // out of order
        var current = previous?.let { credit(it, e.at) }
        var finished: LiveRun? = null
        if (current != null && current.title != e.title && e.state != PlayState.STOPPED) {
            finished = current
            current = null
        }
        if (e.state == PlayState.STOPPED) {
            runs.remove(e.deviceId)
            return finished ?: current?.let { withPosition(it, e) }
        }
        if (current == null) {
            if (e.state != PlayState.PLAYING) {
                runs.remove(e.deviceId)
                return finished // opened paused: nothing has been watched yet
            }
            current = LiveRun(e.deviceId, e.deviceName, e.profileIndex, e.title, startedAt = e.at, lastAt = e.at, lastState = e.state)
        }
        current = withPosition(current, e).copy(
            lastAt = e.at,
            lastState = e.state,
            deviceName = e.deviceName,
            profileIndex = e.profileIndex ?: current.profileIndex,
        )
        runs[e.deviceId] = current
        return finished
    }

    /** Devices that went quiet (switched off, or paused and left) count as finished. */
    fun expire(nowMs: Long): List<LiveRun> {
        val done = runs.values.filter { r ->
            nowMs - r.lastAt > if (r.lastState == PlayState.PLAYING) playingStaleMs else pausedStaleMs
        }
        done.forEach { runs.remove(it.deviceId) }
        return done.map { credit(it, minOf(nowMs, it.lastAt + maxGapMs)) }
    }

    /** Counts the time since the last report as watched, if it was playing. */
    private fun credit(run: LiveRun, at: Long): LiveRun {
        if (run.lastState != PlayState.PLAYING || run.items.isEmpty()) return run
        val gap = (at - run.lastAt).coerceIn(0, maxGapMs)
        val last = run.items.last()
        return run.copy(items = run.items.dropLast(1) + last.copy(playedMs = last.playedMs + gap, positionMs = last.positionMs.let { p ->
            if (last.durationMs > 0) (p + gap).coerceAtMost(last.durationMs) else p + gap
        }))
    }

    private fun withPosition(run: LiveRun, e: LiveEvent): LiveRun {
        val sub = e.subtitle?.takeIf { it.isNotBlank() }
        val existing = run.items.indexOfFirst { it.subtitle == sub }
        val item = (run.items.getOrNull(existing) ?: LiveRunItem(sub)).let {
            if (e.positionMs > 0 || e.durationMs > 0) it.copy(positionMs = e.positionMs, durationMs = e.durationMs.takeIf { d -> d > 0 } ?: it.durationMs) else it
        }
        // The item being played moves to the end, so new time is credited to it.
        val others = run.items.filterIndexed { i, _ -> i != existing }
        return run.copy(items = others + item)
    }

    companion object {
        /** A run shorter than this was just a peek, not worth a summary. */
        const val MIN_SUMMARY_MS = 60_000L

        /** Heading and detail line: "Mum watched 3 episodes of X" / "S2 E1–E3 · 2h 05m". */
        fun summarize(who: String, run: LiveRun): Pair<String, String> {
            // No time when it wasn't timed (the report only said where they stopped).
            val time = run.playedMs.takeIf { it > 0 }?.let { SessionTracker.formatShort(it) }
            val episodes = run.items.filter { it.subtitle != null }
            if (episodes.isEmpty()) {
                val film = run.items.lastOrNull()
                val end = when {
                    film == null -> null
                    film.finished -> "finished"
                    film.durationMs > 0 -> "stopped at ${(film.positionMs * 100 / film.durationMs).coerceIn(0, 100)}%"
                    else -> null
                }
                return (if (film?.finished == true) "$who finished ${run.title}" else "$who watched ${run.title}") to listOfNotNull(time, end).joinToString(" · ")
            }
            fun label(i: LiveRunItem) = Fun.episodeOf(i.subtitle)?.let { (s, ep) -> episodeLabel(s, ep) } ?: i.subtitle
            if (episodes.size == 1) {
                val e = episodes.single()
                val end = when {
                    e.finished -> "finished"
                    e.durationMs > 0 -> "stopped at ${(e.positionMs * 100 / e.durationMs).coerceIn(0, 100)}%"
                    else -> null
                }
                return "$who watched ${run.title}" to listOfNotNull(label(e), time, end).joinToString(" · ")
            }
            val numbered = episodes.mapNotNull { Fun.episodeOf(it.subtitle) }.sortedWith(compareBy({ it.first }, { it.second }))
            val range = if (numbered.size == episodes.size) {
                val (a, b) = numbered.first() to numbered.last()
                if (a.first == b.first) "S${a.first} E${a.second}–E${b.second}" else "S${a.first} E${a.second} – S${b.first} E${b.second}"
            } else {
                null
            }
            return "$who watched ${episodes.size} episodes of ${run.title}" to listOfNotNull(range, time).joinToString(" · ")
        }
    }
}
