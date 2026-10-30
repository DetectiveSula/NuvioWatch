package app.nuviowatch.live

import android.content.Context
import app.nuviowatch.core.LiveChange
import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.LiveTracker
import app.nuviowatch.core.PlayState
import app.nuviowatch.graph
import app.nuviowatch.monitor.Notifier
import app.nuviowatch.widget.NowWidget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Turns live reports from other devices into "started" / "stopped" alerts. */
object LiveReceiver {
    private val mutex = Mutex()
    private val tracker = LiveTracker()
    private var restored = false

    suspend fun onEvent(context: Context, id: String, event: LiveEvent) = mutex.withLock {
        val prefs = context.graph.prefs
        restore(context)
        val change = tracker.apply(event)
        prefs.liveDevices = tracker.states
        prefs.liveSince = id
        // Replayed after a reconnect, or from this phone itself: not news.
        val fresh = System.currentTimeMillis() - event.at < FRESH_MS
        NowWidget.update(context)
        val ownDevice = event.deviceId == prefs.deviceId
        if (change != null && fresh) {
            if (ownDevice && !prefs.alertOwnDevice) {
                prefs.setStatus("alert", "Skipped: playback on this device (turn on \"Also alert about this device\")")
            } else {
                alert(context, event, change)
            }
        }
    }

    /** Devices that went silent while playing (e.g. switched off) count as stopped. */
    suspend fun expire(context: Context, nowMs: Long) = mutex.withLock {
        restore(context)
        if (tracker.expire(nowMs).isNotEmpty()) {
            context.graph.prefs.liveDevices = tracker.states
            NowWidget.update(context)
        }
    }

    private fun restore(context: Context) {
        if (restored) return
        tracker.restore(context.graph.prefs.liveDevices)
        restored = true
    }

    private suspend fun alert(context: Context, event: LiveEvent, change: LiveChange) {
        val graph = context.graph
        val prefs = graph.prefs
        val settings = prefs.settings.value
        if (!settings.enabled) {
            prefs.setStatus("alert", "Skipped: Watching alerts is off")
            return
        }
        val titles = graph.titles.titles.value
        val profile = LiveHub.profileFor(event, graph.repository.snapshot.value, titles)
        if (profile != null && (profile.index in settings.mutedProfiles || prefs.mutedUntil(profile.index) > System.currentTimeMillis())) {
            prefs.setStatus("alert", "Skipped: ${profile.name} is muted")
            return
        }
        if (!Notifier.canNotify(context)) {
            prefs.setStatus("alert", "Skipped: notifications are blocked for Nuvio Watch")
            return
        }
        val who = profile?.name ?: "Someone on ${event.deviceName}"
        val title = LiveHub.titleFor(event, titles)
        Notifier.showLive(
            context = context,
            event = event,
            heading = if (change == LiveChange.STARTED) "$who started watching" else "$who stopped watching",
            profileIndex = profile?.index,
            poster = if (change == LiveChange.STARTED) Notifier.loadBitmap(context, title?.poster) else null,
            silent = change == LiveChange.STOPPED || event.state != PlayState.PLAYING,
            profileName = profile?.name,
        )
        prefs.setStatus("alert", "Shown: $who ${if (change == LiveChange.STARTED) "started" else "stopped"} watching")
    }

    fun reset() {
        restored = false
        tracker.restore(emptyMap())
    }

    private const val FRESH_MS = 3 * 60_000L
}
