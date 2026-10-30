package app.nuviowatch.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One film or episode a profile has watched (fully or partly), merged from progress and history. */
data class Viewing(
    val profileIndex: Int,
    val contentId: String,
    val series: Boolean,
    val season: Int?,
    val episode: Int?,
    val fallbackTitle: String,
    /** Estimated time spent watching it. */
    val watchedMs: Long,
    val atMs: Long,
    val completed: Boolean,
    val fraction: Float,
)

data class TitleTotal(
    val contentId: String,
    val series: Boolean,
    val watchedMs: Long,
    val plays: Int,
    val lastAtMs: Long,
)

data class DayTotal(val date: LocalDate, val minutes: Long)

data class Stats(
    val totalWatchedMs: Long,
    val movies: Int,
    val episodes: Int,
    val shows: Int,
    val inProgress: Int,
    val topTitles: List<TitleTotal>,
    val topGenres: List<Pair<String, Int>>,
    val days: List<DayTotal>,
    /** Minutes watched per hour of the day, 0–23. */
    val hours: List<Long>,
    val lastActivityMs: Long?,
) {
    companion object {
        val EMPTY = Stats(0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList(), List(24) { 0L }, null)
    }
}

object StatsCalculator {
    private const val DEFAULT_MOVIE_MINUTES = 110
    private const val DEFAULT_EPISODE_MINUTES = 45

    fun viewings(
        profileIndex: Int,
        progress: List<ProgressEntry>,
        watched: List<WatchedItem>,
        titles: Map<String, TitleInfo>,
    ): List<Viewing> {
        fun key(id: String, season: Int?, episode: Int?) = "$id|${season ?: ""}|${episode ?: ""}"

        val progressByKey = progress.associateBy { key(it.contentId, it.season, it.episode) }
        val watchedByKey = watched
            // A whole series marked as watched has no episode, so there is nothing to time.
            .filterNot { isSeries(it.contentType, it.season) && it.episode == null }
            .groupBy { key(it.contentId, it.season, it.episode) }
            .mapValues { (_, items) -> items.maxBy { it.watchedAtMs } }

        return (progressByKey.keys + watchedByKey.keys).map { k ->
            val p = progressByKey[k]
            val w = watchedByKey[k]
            val contentId = p?.contentId ?: w!!.contentId
            val series = isSeries(p?.contentType ?: w!!.contentType, p?.season ?: w?.season, p?.videoId.orEmpty())
            val completed = w != null || p?.isCompleted == true
            val runtimeMs = (titles[contentId]?.runtimeMinutes
                ?: if (series) DEFAULT_EPISODE_MINUTES else DEFAULT_MOVIE_MINUTES) * 60_000L
            val fullLengthMs = p?.duration?.takeIf { it > 0 } ?: runtimeMs
            Viewing(
                profileIndex = profileIndex,
                contentId = contentId,
                series = series,
                season = p?.season ?: w?.season,
                episode = p?.episode ?: w?.episode,
                fallbackTitle = w?.title?.takeIf { it.isNotBlank() } ?: contentId,
                watchedMs = if (completed) maxOf(fullLengthMs, p?.position ?: 0) else (p?.position ?: 0),
                atMs = maxOf(p?.lastWatchedMs ?: 0, w?.watchedAtMs ?: 0),
                completed = completed,
                fraction = if (completed) 1f else p?.fraction ?: 0f,
            )
        }.sortedByDescending { it.atMs }
    }

    fun compute(
        viewings: List<Viewing>,
        titles: Map<String, TitleInfo>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        days: Int = 30,
    ): Stats {
        if (viewings.isEmpty()) return Stats.EMPTY
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val firstDay = today.minusDays(days - 1L)
        val perDay = mutableMapOf<LocalDate, Long>()
        val perHour = LongArray(24)
        for (v in viewings) {
            if (v.atMs <= 0) continue
            val at = Instant.ofEpochMilli(v.atMs).atZone(zone)
            val minutes = v.watchedMs / 60_000
            if (!at.toLocalDate().isBefore(firstDay)) perDay.merge(at.toLocalDate(), minutes, Long::plus)
            perHour[at.hour] += minutes
        }

        val byTitle = viewings.groupBy { it.contentId }
        val topTitles = byTitle.map { (id, vs) ->
            TitleTotal(id, vs.first().series, vs.sumOf { it.watchedMs }, vs.size, vs.maxOf { it.atMs })
        }.sortedByDescending { it.watchedMs }

        val genreCounts = mutableMapOf<String, Int>()
        for (id in byTitle.keys) titles[id]?.genres?.forEach { genreCounts.merge(it, 1, Int::plus) }

        return Stats(
            totalWatchedMs = viewings.sumOf { it.watchedMs },
            movies = viewings.count { !it.series && it.completed },
            episodes = viewings.count { it.series && it.completed },
            shows = byTitle.values.count { it.first().series },
            inProgress = viewings.count { !it.completed && it.fraction > 0f },
            topTitles = topTitles.take(10),
            topGenres = genreCounts.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value },
            days = (0 until days).map { firstDay.plusDays(it.toLong()) }.map { DayTotal(it, perDay[it] ?: 0) },
            hours = perHour.toList(),
            lastActivityMs = viewings.maxOf { it.atMs }.takeIf { it > 0 },
        )
    }
}
