package app.nuviowatch.monitor

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import app.nuviowatch.core.Profile
import app.nuviowatch.core.SessionTracker
import app.nuviowatch.graph
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
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
                    prefs.setCursor(profile.index, client.progressCursor(profile.index))
                    continue
                }
                val events = client.progressChanges(profile.index, cursor)
                if (events.isEmpty()) continue
                prefs.setCursor(profile.index, events.maxOf { it.eventId })

                val upserts = events.filter { it.isUpsert }
                upserts.maxByOrNull { it.eventId }?.toEntry()?.let { latest ->
                    if (latest.lastWatchedMs >= (live[profile.index]?.lastWatchedMs ?: 0)) live[profile.index] = latest
                }

                val known = knownPositions(context, profile.index)
                tracker.record(sessions[profile.index], profile.index, events, now) { key -> known[key] }
                    ?.let { sessions[profile.index] = it }
                upserts.forEach { known[it.progressKey.ifBlank { it.videoId.ifBlank { it.contentId } }] = it.position }
                prefs.setKnownPositions(profile.index, known)
            }

            val settings = prefs.settings.value
            for ((index, session) in sessions.toMap()) {
                if (tracker.isDue(session, now)) {
                    val profile = profiles.firstOrNull { it.index == index }
                    val allowed = profile != null && index !in settings.mutedProfiles && prefs.mutedUntil(index) <= now
                    if (allowed) {
                        session.items.distinctBy { it.contentId }.forEach { graph.titles.ensure(it.contentId, it.series) }
                        val lines = SessionTracker.summarize(profile!!.name, session) { graph.titles[it]?.name }
                        val poster = lines.firstOrNull()?.let { graph.titles[it.contentId]?.poster }
                        Notifier.showSummary(context, profile, session, lines, loadBitmap(context, poster))
                    }
                    sessions[index] = session.copy(notifiedAt = now)
                }
                if (tracker.isExpired(session, now)) sessions.remove(index)
            }

            prefs.liveEntries = live
            prefs.sessions = sessions
            prefs.lastPollError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefs.lastPollError = e.message ?: e.javaClass.simpleName
        } finally {
            prefs.lastPollAt = now
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

    private suspend fun loadBitmap(context: Context, url: String?): Bitmap? {
        if (url == null) return null
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).size(256).build())
        return (result as? SuccessResult)?.drawable?.toBitmap()
    }

    /** Clears in-memory state after signing out. */
    fun reset() {
        profiles = emptyList()
        profilesFetchedAt = 0
    }

    private const val MIN_GAP_MS = 15_000L
    private const val PROFILE_REFRESH_MS = 60 * 60_000L
}
