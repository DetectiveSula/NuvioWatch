package app.nuviowatch.monitor

import android.content.Context
import app.nuviowatch.core.NuvioClient
import app.nuviowatch.core.NuvioException
import app.nuviowatch.core.Profile
import app.nuviowatch.core.ProgressEvent
import app.nuviowatch.core.SessionTracker
import app.nuviowatch.core.normalizeEpochMs
import app.nuviowatch.graph
import app.nuviowatch.live.LiveReceiver
import app.nuviowatch.widget.NowWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One check of the account: reads each profile's new progress changes, groups them
 * into viewing sessions, and sends a summary once a session has gone quiet.
 */
object Monitor {
    private val mutex = Mutex()
    // Nuvio only reports watching when someone pauses, skips or stops, so a summary goes out as soon
    // as it does. If they carry on (the next episode), the same notification is quietly updated.
    private val tracker = SessionTracker(quietMs = 0)
    private var profiles: List<Profile> = emptyList()
    private var profilesFetchedAt = 0L

    suspend fun poll(context: Context, force: Boolean = false) = mutex.withLock {
        val graph = context.graph
        val prefs = graph.prefs
        val now = System.currentTimeMillis()
        // The loop and the wake-up alarm can both fire close together; one check is enough.
        if (!force && now - prefs.lastPollAt < MIN_GAP_MS) return@withLock
        val client = graph.client() ?: return@withLock
        if (!prefs.signedIn.value) return@withLock

        try {
            if (profiles.isEmpty() || now - profilesFetchedAt > PROFILE_REFRESH_MS) {
                profiles = client.profiles()
                profilesFetchedAt = now
            }
            val live = prefs.liveEntries.toMutableMap()
            val sessions = prefs.sessions.toMutableMap()

            for (profile in profiles) {
                val cursor = prefs.cursor(profile.index)
                if (cursor == null) {
                    // First check for this profile: start from now instead of reporting old history.
                    val start = try {
                        client.progressCursor(profile.index)
                    } catch (e: NuvioException) {
                        if (e.isAuthError) throw e
                        changesFromFullPull(context, client, profile.index) // records where things are
                        0L
                    }
                    prefs.setCursor(profile.index, start)
                    continue
                }
                val events = try {
                    client.progressChanges(profile.index, cursor).also { if (it.isNotEmpty()) prefs.setCursor(profile.index, it.maxOf { e -> e.eventId }) }
                } catch (e: NuvioException) {
                    if (e.isAuthError) throw e
                    changesFromFullPull(context, client, profile.index)
                }
                events.filter { it.isUpsert }.maxOfOrNull { normalizeEpochMs(it.lastWatched) }?.let {
                    if (it > prefs.lastSeenProgress(profile.index)) prefs.setLastSeenProgress(profile.index, it)
                }
                if (events.isEmpty()) continue

                val upserts = events.filter { it.isUpsert }
                upserts.map { normalizeEpochMs(it.lastWatched) }.filter { it > 0 }
                    .map { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
                    .distinct().forEach { prefs.markActive(profile.index, it) }
                upserts.maxByOrNull { normalizeEpochMs(it.lastWatched) }?.toEntry()?.let { latest ->
                    if (latest.lastWatchedMs >= (live[profile.index]?.lastWatchedMs ?: 0)) live[profile.index] = latest
                }

                val known = knownPositions(context, profile.index)
                tracker.record(sessions[profile.index], profile.index, events, now) { key -> known[key] }
                    ?.let { sessions[profile.index] = it }
                upserts.forEach { known[it.progressKey.ifBlank { it.videoId.ifBlank { it.contentId } }] = it.position }
                prefs.setKnownPositions(profile.index, known)
            }

            LiveReceiver.expire(context, now)
            val settings = prefs.settings.value
            for ((index, current) in sessions.toMap()) {
                var session = current
                val newActivity = tracker.isDue(session, now)
                val endReached = session.summaryDueAt?.let { now >= it } == true
                if (newActivity || endReached) {
                    // Still playing on a device with Nuvio Watch: it sends its own alerts and summary.
                    val liveNow = prefs.liveDevices.values.any { d ->
                        d.state != app.nuviowatch.core.PlayState.STOPPED && now - d.at < 15 * 60_000L &&
                            app.nuviowatch.live.LiveHub.profileFor(d, graph.repository.snapshot.value, graph.titles.titles.value)?.index == index
                    }
                    if (liveNow) {
                        sessions[index] = session.copy(notifiedAt = now, summaryDueAt = null)
                        if (tracker.isExpired(session, now)) sessions.remove(index)
                        continue
                    }
                    val profile = profiles.firstOrNull { it.index == index }
                    // Already summarised the moment they stopped, from live playback.
                    val covered = prefs.liveCoveredAt(index) >= session.lastActivityAt - LIVE_COVER_MS
                    val allowed = !covered && profile != null && index !in settings.mutedProfiles && prefs.mutedUntil(index) <= now
                    val latest = session.latest
                    session.items.distinctBy { it.contentId }.forEach { graph.titles.ensure(it.contentId, it.series) }

                    // Nuvio sends the same update for a skip, a pause and a stop. Unless they've finished,
                    // say they're watching, and send the summary when the episode would have ended.
                    if (newActivity && latest != null && !latest.completed && !covered) {
                        val popUp = session.watchingAt == null || (session.summarizedAt ?: Long.MIN_VALUE) >= (session.watchingAt ?: Long.MIN_VALUE)
                        if (allowed && !QuietHours.isQuiet(prefs)) {
                            val info = graph.titles[latest.contentId]
                            val details = listOfNotNull(
                                app.nuviowatch.core.episodeLabel(latest.season, latest.episode),
                                if (latest.duration > 0) "${(latest.position * 100 / latest.duration).coerceIn(0, 100)}%" else null,
                            ).joinToString(" · ")
                            Notifier.showSyncWatching(
                                context, session.notificationId, profile!!, info?.name ?: latest.contentId, details, popUp,
                                if (popUp) Notifier.loadBitmap(context, info?.poster) else null,
                            )
                            prefs.setStatus("alert", "Shown: ${profile.name} is watching (from Nuvio sync)")
                        }
                        val remaining = if (latest.duration > 0) (latest.duration - latest.position).coerceAtLeast(0) else 30 * 60_000L
                        session = session.copy(
                            notifiedAt = now,
                            watchingAt = if (popUp) now else session.watchingAt,
                            summaryDueAt = session.lastActivityAt + remaining + END_MARGIN_MS,
                        )
                    } else {
                        if (covered) prefs.setStatus("summary", "Sync summary skipped: already sent when they stopped")
                        if (allowed) {
                            val lines = SessionTracker.summarize(profile!!.name, session) { graph.titles[it]?.name }
                            val poster = lines.firstOrNull()?.let { graph.titles[it.contentId]?.poster }
                            if (QuietHours.isQuiet(prefs)) {
                                lines.forEach { QuietHours.hold(prefs, it.title, it.text) }
                            } else {
                                Notifier.showSummary(context, profile, session, lines, Notifier.loadBitmap(context, poster), silent = false)
                                prefs.setStatus("summary", "Shown from Nuvio sync: ${lines.firstOrNull()?.title.orEmpty()}")
                            }
                        }
                        // Ask the person who watched (on their own phone) how it was.
                        if (index == prefs.deviceProfile) {
                            session.items.filter { it.completed }.distinctBy { it.contentId }.forEach { item ->
                                val key = "rate|$index|${item.contentId}"
                                if (!prefs.isMarked(key)) {
                                    graph.titles.ensure(item.contentId, item.series)
                                    Notifier.showRatePrompt(context, index, item.contentId, graph.titles[item.contentId]?.name ?: item.contentId)
                                    prefs.mark(key)
                                }
                            }
                        }
                        session = session.copy(notifiedAt = now, summaryDueAt = null, summarizedAt = now)
                    }
                    sessions[index] = session
                }
                if (tracker.isExpired(session, now)) sessions.remove(index)
            }

            prefs.liveEntries = live
            prefs.sessions = sessions
            prefs.lastPollError = null
            // Quiet hours over: send what was watched overnight.
            QuietHours.deliver(context)
            // New versions of Nuvio Watch, every 3 hours.
            if (now - prefs.lastUpdateCheck > 3 * 3_600_000L) {
                prefs.lastUpdateCheck = now
                runCatching { app.nuviowatch.recap.Nudges.announceUpdate(context) }
            }
            // Streak milestones soon after they happen (at most every 10 minutes).
            if (now - prefs.lastStreakCheck > 10 * 60_000L) {
                prefs.lastStreakCheck = now
                runCatching { app.nuviowatch.recap.Nudges.announceStreaks(context, graph.repository.snapshot.value) }
            }
            NowWidget.update(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefs.lastPollError = e.message ?: e.javaClass.simpleName
        } finally {
            prefs.lastPollAt = now
        }
    }

    /**
     * If Nuvio's change feed isn't available, read the whole progress list and
     * treat anything newer than the last check as a change.
     */
    private suspend fun changesFromFullPull(context: Context, client: NuvioClient, profileIndex: Int): List<ProgressEvent> {
        val since = context.graph.prefs.lastSeenProgress(profileIndex)
        val entries = client.progress(profileIndex)
        if (since == 0L) {
            // First time: just note where things are.
            entries.maxOfOrNull { it.lastWatchedMs }?.let { context.graph.prefs.setLastSeenProgress(profileIndex, it) }
            return emptyList()
        }
        return entries.filter { it.lastWatchedMs > since }.map {
            ProgressEvent(0, "upsert", it.progressKey, it.contentId, it.contentType, it.videoId, it.season, it.episode, it.position, it.duration, it.lastWatched)
        }
    }

    /** Saved positions, seeded from the last full refresh the first time. */
    private fun knownPositions(context: Context, profileIndex: Int): MutableMap<String, Long> {
        val saved = context.graph.prefs.knownPositions(profileIndex)
        if (saved.isNotEmpty()) return saved.toMutableMap()
        return context.graph.repository.snapshot.value.progress[profileIndex].orEmpty()
            .associate { it.progressKey.ifBlank { it.videoId.ifBlank { it.contentId } } to it.position }
            .toMutableMap()
    }

    /** Clears in-memory state after signing out. */
    fun reset() {
        profiles = emptyList()
        profilesFetchedAt = 0
    }

    private const val MIN_GAP_MS = 15_000L
    private const val PROFILE_REFRESH_MS = 60 * 60_000L
    private const val LIVE_COVER_MS = 15 * 60_000L
    /** A few minutes after the episode would have ended (credits, a slow start). */
    private const val END_MARGIN_MS = 3 * 60_000L
}
