package app.nuviowatch.live

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.PlayState
import app.nuviowatch.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches Nuvio's media session on this device (the same information the lock-screen
 * player shows) and reports play, pause and stop to the other devices on the account.
 *
 * Android only lets an app see other apps' media sessions while it has notification
 * access, which is why this is a notification listener; it never reads notifications.
 */
class NowPlayingListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val watched = mutableMapOf<String, MediaController>()
    private var lastSent: LiveEvent? = null
    private var sessions: MediaSessionManager? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        attach(controllers.orEmpty())
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            // While playing, report every minute so others know it's still going.
            lastSent?.takeIf { it.state == PlayState.PLAYING }?.let { report(force = true) }
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java)
        sessions = manager
        val me = ComponentName(this, NowPlayingListener::class.java)
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, me, main)
            attach(manager.getActiveSessions(me))
        }.onSuccess { graph.prefs.setStatus("listener", "Connected") }
            .onFailure { graph.prefs.setStatus("listener", "Couldn't read media sessions: ${it.message}") }
        main.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    override fun onListenerDisconnected() {
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        main.removeCallbacks(heartbeat)
        watched.values.forEach { it.unregisterCallback(callback) }
        watched.clear()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = report()
        override fun onMetadataChanged(metadata: MediaMetadata?) = report()
        override fun onSessionDestroyed() = report()
    }

    private fun attach(controllers: List<MediaController>) {
        val nuvio = controllers.filter { it.packageName in NUVIO_PACKAGES }.associateBy { it.sessionToken.toString() }
        (watched.keys - nuvio.keys).forEach { watched.remove(it)?.unregisterCallback(callback) }
        (nuvio.keys - watched.keys).forEach { key ->
            nuvio.getValue(key).registerCallback(callback, main)
            watched[key] = nuvio.getValue(key)
        }
        val others = controllers.map { it.packageName }.filter { it !in NUVIO_PACKAGES }.distinct()
        graph.prefs.setStatus(
            "player",
            if (nuvio.isEmpty()) "No Nuvio player open" + (if (others.isNotEmpty()) " (other players: ${others.joinToString()})" else "")
            else "Nuvio player found",
        )
        report()
    }

    private fun report(force: Boolean = false) {
        val prefs = graph.prefs
        if (!prefs.reportPlayback || !prefs.signedIn.value) {
            prefs.setStatus("send", if (!prefs.signedIn.value) "Not sending: not signed in" else "Not sending: reporting is off")
            return
        }
        val now = System.currentTimeMillis()
        val active = watched.values.firstOrNull { it.playbackState?.state in LIVE_STATES }
            ?: watched.values.firstOrNull { it.metadata != null }
        val title = active?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val state = when (active?.playbackState?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> PlayState.PLAYING
            PlaybackState.STATE_PAUSED -> PlayState.PAUSED
            else -> PlayState.STOPPED
        }
        if (title.isNullOrBlank() && lastSent == null) return
        val event = LiveEvent(
            deviceId = prefs.deviceId,
            deviceName = prefs.deviceName,
            profileIndex = prefs.deviceProfile,
            title = title?.takeIf { state != PlayState.STOPPED } ?: lastSent?.title.orEmpty(),
            subtitle = active?.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: active?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: lastSent?.subtitle,
            state = state,
            positionMs = active?.playbackState?.position?.coerceAtLeast(0) ?: lastSent?.positionMs ?: 0,
            durationMs = active?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: lastSent?.durationMs ?: 0,
            at = now,
        )
        val previous = lastSent
        val changed = previous == null || previous.state != event.state || previous.title != event.title || previous.subtitle != event.subtitle
        if (!changed && !force) return
        if (event.title.isBlank()) return
        lastSent = event
        scope.launch {
            val relay = LiveHub.relay(this@NowPlayingListener)
            if (relay == null) {
                prefs.setStatus("send", "Not sending: account id missing, sign out and in again")
                return@launch
            }
            runCatching { relay.publish(event) }
                .onSuccess { prefs.setStatus("send", "Sent \"${event.state.name.lowercase()}\" · ${event.title}") }
                .onFailure { prefs.setStatus("send", "Send failed: ${it.message}") }
        }
    }

    companion object {
        /** Nuvio's phone app and its TV / Fire TV app. */
        val NUVIO_PACKAGES = setOf("com.nuvio.app", "com.nuvio.tv", "com.nuvio.tv.debug", "com.nuviodebug.com")
        private val LIVE_STATES = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_PAUSED)
        private const val HEARTBEAT_MS = 60_000L

        fun hasAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return ComponentName(context, NowPlayingListener::class.java).flattenToString() in enabled
        }

        /** For TVs and Fire Sticks, which often hide the notification access screen. */
        fun adbCommand(context: Context) =
            "adb shell cmd notification allow_listener ${ComponentName(context, NowPlayingListener::class.java).flattenToString()}"
    }
}
