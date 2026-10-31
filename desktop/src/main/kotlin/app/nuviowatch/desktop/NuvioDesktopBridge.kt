package app.nuviowatch.desktop

import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.NuvioClient
import app.nuviowatch.core.PlayState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.concurrent.thread

/**
 * Sees what Nuvio Desktop is playing. Nuvio Desktop shares it through Discord Rich
 * Presence, by talking to a local socket ($TMPDIR/discord-ipc-0); this app answers on that
 * socket like Discord would. Discord itself isn't needed.
 */
object NuvioDesktopBridge {
    private const val OP_HANDSHAKE = 0
    private const val OP_FRAME = 1
    private const val OP_CLOSE = 2
    private const val OP_PING = 3
    private const val OP_PONG = 4

    @Volatile private var last: LiveEvent? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread(isDaemon = true, name = "nuvio-desktop") { listen() }
        thread(isDaemon = true, name = "nuvio-desktop-heartbeat") {
            while (true) {
                Thread.sleep(60_000)
                heartbeat()
            }
        }
    }

    private fun listen() {
        val dir = (System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")).trimEnd('/')
        var server: ServerSocketChannel? = null
        var chosen = ""
        for (i in 0..9) {
            val path = "$dir/discord-ipc-$i"
            val file = File(path)
            if (file.exists()) {
                // Taken by Discord (or a stale socket from a crash): only reuse it if nobody answers.
                if (canConnect(path)) continue
                file.delete()
            }
            val s = runCatching {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(path)) }
            }.getOrNull() ?: continue
            server = s
            chosen = path
            break
        }
        val srv = server ?: run {
            Model.setStatus("nuvio", "Couldn't open the connection Nuvio Desktop uses")
            return
        }
        Model.setStatus("nuvio", if (chosen.endsWith("-0")) "Waiting for Nuvio Desktop" else "Discord is running, so Nuvio Desktop may talk to Discord instead")
        while (true) {
            val client = runCatching { srv.accept() }.getOrNull() ?: continue
            thread(isDaemon = true) { serve(client) }
        }
    }

    private fun canConnect(path: String): Boolean = runCatching {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(path)) }
    }.isSuccess

    private fun serve(channel: SocketChannel) {
        Model.setStatus("nuvio", "Nuvio Desktop connected")
        try {
            channel.use {
                while (true) {
                    val (op, payload) = readFrame(it) ?: break
                    val json = runCatching { NuvioClient.json.parseToJsonElement(payload).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                    when (op) {
                        OP_HANDSHAKE -> writeFrame(it, OP_FRAME, buildJsonObject {
                            put("cmd", "DISPATCH")
                            put("evt", "READY")
                            put("data", buildJsonObject {
                                put("v", 1)
                                put("user", buildJsonObject { put("id", "0"); put("username", "Nuvio Watch"); put("discriminator", "0") })
                            })
                        })
                        OP_FRAME -> {
                            val cmd = json["cmd"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            writeFrame(it, OP_FRAME, JsonObject(mapOf(
                                "cmd" to JsonPrimitive(cmd),
                                "nonce" to (json["nonce"] ?: JsonPrimitive("")),
                                "evt" to JsonNull,
                                "data" to JsonObject(emptyMap()),
                            )))
                            if (cmd == "SET_ACTIVITY") {
                                val activity = (json["args"] as? JsonObject)?.get("activity") as? JsonObject
                                activityChanged(activity)
                            }
                        }
                        OP_PING -> writeFrame(it, OP_PONG, json)
                        OP_CLOSE -> return
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            Model.setStatus("nuvio", "Nuvio Desktop disconnected")
            activityChanged(null)
        }
    }

    private fun readExactly(ch: SocketChannel, n: Int): ByteBuffer? {
        val buf = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.hasRemaining()) if (ch.read(buf) < 0) return null
        buf.flip()
        return buf
    }

    private fun readFrame(ch: SocketChannel): Pair<Int, String>? {
        val header = readExactly(ch, 8) ?: return null
        val op = header.int
        val length = header.int
        if (length < 0 || length > 1_000_000) return null
        val body = readExactly(ch, length) ?: return null
        val bytes = ByteArray(length).also { body.get(it) }
        return op to String(bytes, Charsets.UTF_8)
    }

    private fun writeFrame(ch: SocketChannel, op: Int, obj: JsonElement) {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(op).putInt(bytes.size).put(bytes).flip()
        synchronized(ch) { while (buf.hasRemaining()) ch.write(buf) }
    }

    /**
     * Play / pause / stop from a Rich Presence status. Nuvio Desktop has used two formats:
     * older: details "Watching: <title>" / "Paused: <title>", state "<episode>";
     * newer: details "<title>", state "<episode>" or "<episode> • Paused" (or just "Paused").
     * Outside the player it sends "Browsing …" or "Viewing …".
     */
    fun parse(details: String, stateLine: String?): Triple<PlayState, String, String?> {
        val pausedSuffix = Regex("""\s*[•·|-]\s*Paused\s*$""", RegexOption.IGNORE_CASE)
        fun clean(line: String?): Pair<String?, Boolean> {
            val text = line?.trim().orEmpty()
            if (text.isEmpty()) return null to false
            if (text.equals("Paused", ignoreCase = true)) return null to true
            if (text.equals("Watching", ignoreCase = true)) return null to false
            if (pausedSuffix.containsMatchIn(text)) return text.replace(pausedSuffix, "").ifEmpty { null } to true
            return text to false
        }
        if (details.isEmpty() || details.startsWith("Browsing") || details.startsWith("Viewing ")) return Triple(PlayState.STOPPED, "", null)
        if (details.startsWith("Watching: ")) return Triple(PlayState.PLAYING, details.removePrefix("Watching: "), clean(stateLine).first)
        if (details.startsWith("Paused: ")) return Triple(PlayState.PAUSED, details.removePrefix("Paused: "), clean(stateLine).first)
        val (text, paused) = clean(stateLine)
        return Triple(if (paused) PlayState.PAUSED else PlayState.PLAYING, details, text)
    }

    @Synchronized
    private fun activityChanged(activity: JsonObject?) {
        val details = activity?.get("details")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val start = (activity?.get("timestamps") as? JsonObject)?.get("start")?.jsonPrimitive?.longOrNull
        val (state, title, subtitle) = parse(details, activity?.get("state")?.jsonPrimitive?.contentOrNull)
        val now = System.currentTimeMillis()
        if (state == PlayState.STOPPED) {
            val previous = last ?: return
            if (previous.state == PlayState.STOPPED) return
            send(previous.copy(state = PlayState.STOPPED, at = now))
            return
        }
        var position = if (last?.title == title) last?.positionMs ?: 0 else 0
        if (state == PlayState.PLAYING && start != null) position = maxOf(0, now - start * 1000)
        val s = Model.store.saved.value
        val event = LiveEvent(
            deviceId = s.deviceId,
            deviceName = s.deviceName,
            profileIndex = s.profileIndex,
            title = title,
            subtitle = subtitle,
            state = state,
            positionMs = position,
            durationMs = 0,
            at = now,
        )
        val previous = last
        if (previous != null && previous.state == event.state && previous.title == event.title && previous.subtitle == event.subtitle) {
            last = event // just a seek: remember the new position without sending
            return
        }
        Model.setStatus("player", if (state == PlayState.PLAYING) "Playing: $title" else "Paused: $title")
        send(event)
    }

    @Synchronized
    private fun heartbeat() {
        val previous = last ?: return
        if (previous.state != PlayState.PLAYING) return
        val now = System.currentTimeMillis()
        send(previous.copy(positionMs = previous.positionMs + (now - previous.at), at = now))
    }

    private fun send(event: LiveEvent) {
        last = event
        Model.publish(event)
    }
}
