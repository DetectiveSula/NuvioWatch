package app.nuviowatch.monitor

import android.content.Context
import app.nuviowatch.core.AlertPolicy
import app.nuviowatch.core.Profile
import app.nuviowatch.core.isSeries
import app.nuviowatch.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One check of the account: reads each profile's new progress changes and
 * sends a notification for anyone who has started watching something.
 */
object Monitor {
    private val mutex = Mutex()
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
            val settings = prefs.settings.value
            val policy = AlertPolicy(notifyFinished = settings.notifyFinished)
            val live = prefs.liveEntries.toMutableMap()

            for (profile in profiles) {
                val cursor = prefs.cursor(profile.index)
                if (cursor == null) {
                    // First check for this profile: start from now instead of alerting on old history.
                    prefs.setCursor(profile.index, client.progressCursor(profile.index))
                    continue
                }
                val events = client.progressChanges(profile.index, cursor)
                if (events.isEmpty()) continue
                prefs.setCursor(profile.index, events.maxOf { it.eventId })

                events.filter { it.isUpsert }.maxByOrNull { it.eventId }?.toEntry()?.let { latest ->
                    if (latest.lastWatchedMs >= (live[profile.index]?.lastWatchedMs ?: 0)) live[profile.index] = latest
                }

                if (profile.index in settings.mutedProfiles) continue
                for (alert in policy.evaluate(profile, events, now, prefs::lastAlertAt)) {
                    val entry = alert.entry
                    graph.titles.ensure(entry.contentId, isSeries(entry.contentType, entry.season, entry.videoId))
                    Notifier.showAlert(context, alert, graph.titles[entry.contentId])
                    prefs.setLastAlertAt(alert.cooldownKey, now)
                }
            }
            prefs.liveEntries = live
            prefs.lastPollError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefs.lastPollError = e.message ?: e.javaClass.simpleName
        } finally {
            prefs.lastPollAt = now
        }
    }

    /** Clears in-memory state after signing out. */
    fun reset() {
        profiles = emptyList()
        profilesFetchedAt = 0
    }

    private const val MIN_GAP_MS = 15_000L
    private const val PROFILE_REFRESH_MS = 60 * 60_000L
}
