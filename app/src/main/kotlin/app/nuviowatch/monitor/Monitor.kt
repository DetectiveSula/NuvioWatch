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
    private val tracker = SessionTracker()
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
            for ((index, session) in sessions.toMap()) {
                if (tracker.isDue(session, now)) {
                    val profile = profiles.firstOrNull { it.index == index }
                    val allowed = profile != null && index !in settings.mutedProfiles && prefs.mutedUntil(index) <= now
                    if (allowed) {
                        session.items.distinctBy { it.contentId }.forEach { graph.titles.ensure(it.contentId, it.series) }
                        val lines = SessionTracker.summarize(profile!!.name, session) { graph.titles[it]?.name }
                        val poster = lines.firstOrNull()?.let { graph.titles[it.contentId]?.poster }
                        Notifier.showSummary(context, profile, session, lines, Notifier.loadBitmap(context, poster))
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
                    sessions[index] = session.copy(notifiedAt = now)
                }
                if (tracker.isExpired(session, now)) sessions.remove(index)
            }

            prefs.liveEntries = live
            prefs.sessions = sessions
            prefs.lastPollError = null
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
}
