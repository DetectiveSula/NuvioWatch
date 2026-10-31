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
class LiveTracker(private val staleMs: Long = 4 * 60_000L) {
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
        val stale = devices.values.filter { it.state != PlayState.STOPPED && nowMs - it.at > staleMs }
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
