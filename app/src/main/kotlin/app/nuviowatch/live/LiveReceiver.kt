package app.nuviowatch.live

import android.content.Context
import app.nuviowatch.core.Fun
import app.nuviowatch.core.LiveChange
import app.nuviowatch.core.Profile
import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.LiveRun
import app.nuviowatch.core.LiveRuns
import app.nuviowatch.core.LiveTracker
import app.nuviowatch.core.PlayState
import app.nuviowatch.graph
import app.nuviowatch.monitor.Notifier
import app.nuviowatch.recap.Nudges
import app.nuviowatch.widget.NowWidget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Turns live reports from other devices into "started watching" alerts and a summary the moment they stop. */
object LiveReceiver {
    private val mutex = Mutex()
    private val tracker = LiveTracker()
    private val runs = LiveRuns()
    private var restored = false

    suspend fun onEvent(context: Context, id: String, event: LiveEvent) = mutex.withLock {
        val prefs = context.graph.prefs
        restore(context)
        val change = tracker.apply(event)
        val finished = runs.apply(event)
        // Only devices set to a person (Settings > This device), so a guess never gives someone a streak.
        val who = event.profileIndex
        if (event.state == PlayState.PLAYING && who != null && !event.deviceId.startsWith("test-")) {
            prefs.markActive(who, java.time.Instant.ofEpochMilli(event.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
        }
        prefs.liveDevices = tracker.states
        prefs.liveRuns = runs.states
        prefs.liveSince = id
        // Replayed after a reconnect, or from this phone itself: not news.
        val fresh = System.currentTimeMillis() - event.at < FRESH_MS
        NowWidget.update(context)
        val ownDevice = event.deviceId == prefs.deviceId
        if (change == LiveChange.STARTED && fresh) {
            if (ownDevice && !prefs.alertOwnDevice) {
                prefs.setStatus("alert", "Skipped: playback on this device (turn on \"Also alert about this device\")")
            } else {
                alert(context, event, change)
            }
        }
        if (fresh) {
            when {
                finished != null -> summarize(context, finished)
                // Stopped, but this phone hadn't been timing it (e.g. it was updated or restarted
                // mid-way): still say what they watched and where they got to.
                change == LiveChange.STOPPED -> summarize(
                    context,
                    LiveRun(
                        event.deviceId, event.deviceName, event.profileIndex, event.title, startedAt = event.at, lastAt = event.at,
                        lastState = PlayState.STOPPED, items = listOf(app.nuviowatch.core.LiveRunItem(event.subtitle?.takeIf { it.isNotBlank() }, 0, event.positionMs, event.durationMs)),
                    ),
                    untimed = true,
                )
                event.state == PlayState.STOPPED -> prefs.setStatus("summary", "Stop received from ${event.deviceName}, but nothing had been playing there")
            }
        } else if (event.state == PlayState.STOPPED) {
            prefs.setStatus("summary", "Stop from ${event.deviceName} arrived late, not sent")
        }
    }

    /** "Mum watched 2 episodes of X", replacing their "started watching" alert. */
    private suspend fun summarize(context: Context, run: LiveRun, untimed: Boolean = false) {
        val graph = context.graph
        val prefs = graph.prefs
        val id = Notifier.liveId(run.deviceId)
        if (!untimed && run.playedMs < LiveRuns.MIN_SUMMARY_MS) {
            // Only a peek: take back the "started watching" alert.
            Notifier.cancel(context, id)
            prefs.setStatus("summary", "Not sent: ${run.title} played for under a minute")
            return
        }
        val titles = graph.titles.titles.value
        val asEvent = LiveEvent(run.deviceId, run.deviceName, run.profileIndex, run.title, state = PlayState.STOPPED, at = run.lastAt)
        val profile = LiveHub.profileFor(asEvent, graph.repository.snapshot.value, titles)
        // The Nuvio-sync summary for this viewing would only repeat it.
        profile?.let { prefs.setLiveCoveredAt(it.index, System.currentTimeMillis()) }
        val settings = prefs.settings.value
        val skip = when {
            run.deviceId == prefs.deviceId && !prefs.alertOwnDevice -> "playback on this device"
            !settings.enabled -> "Watching alerts is off"
            profile != null && (profile.index in settings.mutedProfiles || prefs.mutedUntil(profile.index) > System.currentTimeMillis()) -> "${profile.name} is muted"
            !Notifier.canNotify(context) -> "notifications are blocked for Nuvio Watch"
            else -> null
        }
        if (skip != null) {
            prefs.setStatus("summary", "Skipped summary: $skip")
            return
        }
        val who = profile?.name ?: "Someone on ${run.deviceName}"
        val (heading, text) = LiveRuns.summarize(who, run)
        val poster = Notifier.loadBitmap(context, LiveHub.titleFor(asEvent, titles)?.poster)
        Notifier.showLiveSummary(context, id, heading, "$text · on ${run.deviceName}", profile?.index, profile?.name, poster, run.lastAt)
        prefs.setStatus("summary", "Shown: $heading")
    }

    /** Devices that went silent while playing (e.g. switched off) count as stopped. */
    suspend fun expire(context: Context, nowMs: Long) = mutex.withLock {
        restore(context)
        val stale = tracker.expire(nowMs)
        val finished = runs.expire(nowMs)
        if (stale.isNotEmpty() || finished.isNotEmpty()) {
            context.graph.prefs.liveDevices = tracker.states
            context.graph.prefs.liveRuns = runs.states
            NowWidget.update(context)
        }
        finished.forEach { summarize(context, it) }
    }

    private fun restore(context: Context) {
        if (restored) return
        tracker.restore(context.graph.prefs.liveDevices)
        runs.restore(context.graph.prefs.liveRuns)
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
        val ahead = if (change == LiveChange.STARTED) episodesAheadOfMe(context, event, profile?.index) else 0
        Notifier.showLive(
            context = context,
            event = event,
            heading = if (change == LiveChange.STARTED) "$who started watching" else "$who stopped watching",
            profileIndex = profile?.index,
            poster = if (change == LiveChange.STARTED) Notifier.loadBitmap(context, title?.poster) else null,
            silent = change == LiveChange.STOPPED || event.state != PlayState.PLAYING,
            profileName = profile?.name,
            subtitleOverride = if (ahead > 0) "⚠️ $ahead episode${if (ahead == 1) "" else "s"} ahead of you, episode hidden" else null,
        )
        if (change == LiveChange.STARTED && profile != null) {
            sameShow(context, event, profile)
            suggestionStarted(context, event, profile)
        }
        prefs.setStatus("alert", "Shown: $who ${if (change == LiveChange.STARTED) "started" else "stopped"} watching")
    }

    /** How far the watcher is ahead of this phone's owner in the same series (0 if not). */
    fun episodesAheadOfMe(context: Context, event: LiveEvent, watcher: Int?): Int {
        val graph = context.graph
        val me = graph.prefs.deviceProfile ?: return 0
        if (watcher == null || watcher == me) return 0
        val theirs = Fun.episodeOf(event.subtitle) ?: return 0
        val contentId = LiveHub.titleIdFor(event, graph.titles.titles.value) ?: return 0
        val mine = Fun.latestEpisode(Nudges.viewings(graph.repository.snapshot.value, context), me, contentId)
        return Fun.episodesAhead(theirs, mine, graph.episodes.episodes.value[contentId]).coerceAtLeast(0)
    }

    /** "Mani and Ishti are both watching …" — right now on two devices, or recently in Nuvio. Once a day per pair. */
    private fun sameShow(context: Context, event: LiveEvent, watcher: Profile) {
        val graph = context.graph
        val prefs = graph.prefs
        val snapshot = graph.repository.snapshot.value
        val titles = graph.titles.titles.value
        val contentId = LiveHub.titleIdFor(event, titles)
        val name = LiveHub.titleFor(event, titles)?.name ?: event.title
        val now = System.currentTimeMillis()

        val liveOther = prefs.liveDevices.values.firstOrNull { other ->
            other.deviceId != event.deviceId && other.state != PlayState.STOPPED && now - other.at < 5 * 60_000L &&
                LiveHub.titleIdFor(other, titles) == contentId && contentId != null
        }?.let { other -> LiveHub.profileFor(other, snapshot, titles)?.takeIf { it.index != watcher.index }?.let { it to other } }

        val recentOther = if (liveOther == null && contentId != null) {
            snapshot.profiles.filter { it.index != watcher.index }.mapNotNull { p ->
                val last = snapshot.progress[p.index].orEmpty().filter { it.contentId == contentId }.maxByOrNull { it.lastWatchedMs }
                last?.takeIf { now - it.lastWatchedMs < 7 * 86_400_000L }?.let { p to it }
            }.maxByOrNull { it.second.lastWatchedMs }
        } else {
            null
        }
        val other = liveOther?.first ?: recentOther?.first ?: return

        val key = "same|${minOf(watcher.index, other.index)}|${maxOf(watcher.index, other.index)}|$name"
        val today = java.time.LocalDate.now().toString()
        if (prefs.lastSent(key) == today) return
        prefs.setLastSent(key, today)

        val me = prefs.deviceProfile
        val (ask, askName) = when (me) {
            watcher.index -> other.index to other.name
            other.index -> watcher.index to watcher.name
            else -> null to null
        }
        if (liveOther != null) {
            Notifier.showSameShow(
                context,
                "${watcher.name} and ${other.name} are both watching $name",
                "Right now, on ${event.deviceName} and ${liveOther.second.deviceName}.",
                ask,
                askName,
                name,
            )
        } else {
            val entry = recentOther!!.second
            val where = app.nuviowatch.core.episodeLabel(entry.season, entry.episode)?.let { " (on $it)" }.orEmpty()
            Notifier.showSameShow(context, "${other.name} is watching $name too", "${other.name} watched it recently$where. Watch the next one together?", ask, askName, name)
        }
    }

    /** "Ishti started X", for whoever suggested it to her. */
    private fun suggestionStarted(context: Context, event: LiveEvent, watcher: Profile) {
        val prefs = context.graph.prefs
        val me = prefs.deviceProfile ?: return
        val contentId = LiveHub.titleIdFor(event, context.graph.titles.titles.value) ?: return
        val suggestion = prefs.suggestions.lastOrNull { it.toProfile == watcher.index && it.contentId == contentId && it.fromProfile == me } ?: return
        val key = "suggest-started|${suggestion.id}"
        if (prefs.isMarked(key)) return
        prefs.mark(key)
        Notifier.showSuggestionStarted(context, watcher.name, suggestion.title)
    }

    fun reset() {
        restored = false
        tracker.restore(emptyMap())
        runs.restore(emptyMap())
    }

    private const val FRESH_MS = 3 * 60_000L
}
