package app.nuviowatch.core

enum class AlertKind { WATCHING, FINISHED }

data class WatchAlert(
    val profile: Profile,
    val entry: ProgressEntry,
    val kind: AlertKind,
) {
    /** Used to avoid repeating the same alert. */
    val cooldownKey: String
        get() = when (kind) {
            // One "watching" alert per show per session, so a binge doesn't buzz every episode.
            AlertKind.WATCHING -> "w|${profile.index}|${entry.contentId}"
            AlertKind.FINISHED -> "f|${profile.index}|${entry.progressKey.ifBlank { entry.videoId }}"
        }
}

enum class LiveState { ACTIVE_NOW, RECENT, IDLE }

/**
 * Decides which progress changes deserve a notification.
 *
 * Nuvio uploads progress when the viewer pauses, seeks, finishes or closes the player,
 * so a fresh change means someone is (or just was) watching.
 */
class AlertPolicy(
    private val notifyFinished: Boolean = false,
    private val freshnessMs: Long = 15 * 60_000L,
    private val watchingCooldownMs: Long = 90 * 60_000L,
    private val finishedCooldownMs: Long = 12 * 60 * 60_000L,
) {
    fun evaluate(
        profile: Profile,
        events: List<ProgressEvent>,
        nowMs: Long,
        lastAlertAt: (String) -> Long?,
    ): List<WatchAlert> {
        val latest = events
            .filter { it.isUpsert && it.contentId.isNotBlank() }
            .groupBy { it.contentId }
            .map { (_, sameTitle) -> sameTitle.maxBy { it.eventId }.toEntry() }

        return latest.mapNotNull { entry ->
            if (nowMs - entry.lastWatchedMs > freshnessMs) return@mapNotNull null
            val kind = if (entry.isCompleted) AlertKind.FINISHED else AlertKind.WATCHING
            if (kind == AlertKind.FINISHED && !notifyFinished) return@mapNotNull null
            val alert = WatchAlert(profile, entry, kind)
            val cooldown = if (kind == AlertKind.WATCHING) watchingCooldownMs else finishedCooldownMs
            val last = lastAlertAt(alert.cooldownKey)
            if (last != null && nowMs - last < cooldown) null else alert
        }
    }

    companion object {
        fun liveState(entry: ProgressEntry?, nowMs: Long): LiveState {
            if (entry == null) return LiveState.IDLE
            val age = nowMs - entry.lastWatchedMs
            return when {
                age < 20 * 60_000L && !entry.isCompleted -> LiveState.ACTIVE_NOW
                age < 6 * 60 * 60_000L -> LiveState.RECENT
                else -> LiveState.IDLE
            }
        }
    }
}
