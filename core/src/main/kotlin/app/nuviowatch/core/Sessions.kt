package app.nuviowatch.core

import kotlinx.serialization.Serializable

/** One film or episode touched during a viewing session. */
@Serializable
data class SessionItem(
    val progressKey: String,
    val contentId: String,
    val contentType: String = "",
    val videoId: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    /** Where it was before this session (0 if it's new). */
    val startPosition: Long = 0,
    val position: Long = 0,
    val duration: Long = 0,
) {
    val watchedMs: Long get() = (position - startPosition).coerceAtLeast(0)
    val completed: Boolean get() = isComplete(position, duration)
    val series: Boolean get() = isSeries(contentType, season, videoId)
}

/** A profile's run of activity, summarised once it goes quiet. */
@Serializable
data class WatchSession(
    val profileIndex: Int,
    val startedAt: Long,
    val lastActivityAt: Long,
    val items: List<SessionItem> = emptyList(),
    /** When the summary was last sent; later activity updates the same notification. */
    val notifiedAt: Long? = null,
) {
    val notificationId: Int get() = "session|$profileIndex|$startedAt".hashCode()
    val isUpdate: Boolean get() = notifiedAt != null
}

/** The text of a session summary for one title. */
data class SummaryLine(
    val contentId: String,
    val title: String,
    val text: String,
    val watchedMs: Long,
)

/**
 * Groups a profile's progress changes into viewing sessions.
 *
 * Nuvio uploads progress on pause, seek and stop, so a session ends when uploads
 * have stopped for [quietMs]. If more activity follows soon after, the same
 * session continues and its summary is updated instead of sending a new one.
 */
class SessionTracker(
    private val quietMs: Long = 20 * 60_000L,
    private val keepMs: Long = 4 * 60 * 60_000L,
    private val maxEventAgeMs: Long = 3 * 60 * 60_000L,
) {
    /** Adds new progress changes to the profile's session, starting one if needed. */
    fun record(
        current: WatchSession?,
        profileIndex: Int,
        events: List<ProgressEvent>,
        nowMs: Long,
        knownPosition: (String) -> Long?,
    ): WatchSession? {
        // Old changes (e.g. after the phone was off) aren't news any more.
        val fresh = events.filter { it.isUpsert && it.contentId.isNotBlank() && nowMs - normalizeEpochMs(it.lastWatched) <= maxEventAgeMs }
        if (fresh.isEmpty()) return current
        val latestAt = fresh.maxOf { normalizeEpochMs(it.lastWatched) }.coerceAtMost(nowMs)

        var session = current?.takeIf { latestAt - it.lastActivityAt <= keepMs }
            ?: WatchSession(profileIndex, startedAt = fresh.minOf { normalizeEpochMs(it.lastWatched) }, lastActivityAt = latestAt)

        val items = session.items.associateBy { it.progressKey }.toMutableMap()
        for (event in fresh.sortedBy { it.eventId }) {
            val key = event.progressKey.ifBlank { event.videoId.ifBlank { event.contentId } }
            val existing = items[key]
            val start = existing?.startPosition
                ?: knownPosition(key)?.takeIf { it <= event.position } // a rewatch from the start counts in full
                ?: 0L
            items[key] = SessionItem(
                progressKey = key,
                contentId = event.contentId,
                contentType = event.contentType,
                videoId = event.videoId,
                season = event.season,
                episode = event.episode,
                startPosition = start,
                position = event.position,
                duration = event.duration,
            )
        }
        session = session.copy(items = items.values.toList(), lastActivityAt = maxOf(session.lastActivityAt, latestAt))
        return session
    }

    /** True when the session has gone quiet and has news since its last summary. */
    fun isDue(session: WatchSession, nowMs: Long): Boolean =
        session.items.isNotEmpty() &&
            nowMs - session.lastActivityAt >= quietMs &&
            session.lastActivityAt > (session.notifiedAt ?: Long.MIN_VALUE)

    fun isExpired(session: WatchSession, nowMs: Long): Boolean = nowMs - session.lastActivityAt > keepMs

    companion object {
        /** One line per title, biggest first: "Mani watched 3 episodes of X" / "S6 E1–E3 · 2h 10m". */
        fun summarize(profileName: String, session: WatchSession, titleName: (String) -> String?): List<SummaryLine> =
            session.items.groupBy { it.contentId }.map { (contentId, items) ->
                val name = titleName(contentId) ?: contentId
                val watched = items.sumOf { it.watchedMs }
                if (!items.first().series) {
                    val item = items.maxBy { it.position }
                    if (item.completed) {
                        SummaryLine(contentId, "$profileName finished $name", "${formatShort(watched)} watched", watched)
                    } else {
                        SummaryLine(contentId, "$profileName watched $name", "${formatShort(watched)} · stopped at ${percent(item)}%", watched)
                    }
                } else {
                    val episodes = items.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
                    if (episodes.size == 1) {
                        val e = episodes.single()
                        val where = episodeLabel(e.season, e.episode)
                        val end = if (e.completed) "finished" else "stopped at ${percent(e)}%"
                        SummaryLine(contentId, "$profileName watched $name", listOfNotNull(where, formatShort(watched), end).joinToString(" · "), watched)
                    } else {
                        SummaryLine(contentId, "$profileName watched ${episodes.size} episodes of $name", "${episodeRange(episodes)} · ${formatShort(watched)}", watched)
                    }
                }
            }.sortedByDescending { it.watchedMs }

        private fun percent(item: SessionItem) =
            if (item.duration > 0) (item.position * 100 / item.duration).coerceIn(0, 100) else 0

        private fun episodeRange(episodes: List<SessionItem>): String {
            val first = episodes.first()
            val last = episodes.last()
            return if (first.season == last.season) "S${first.season} E${first.episode}–E${last.episode}"
            else "S${first.season} E${first.episode} – S${last.season} E${last.episode}"
        }

        fun formatShort(ms: Long): String {
            val minutes = ms / 60_000
            return if (minutes >= 60) "${minutes / 60}h ${"%02d".format(minutes % 60)}m" else "${minutes.coerceAtLeast(1)}m"
        }
    }
}
