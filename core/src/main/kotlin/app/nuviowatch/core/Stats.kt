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
    /** Whole weeks (Monday first) ending today, for the activity heatmap. */
    val days: List<DayTotal>,
    /** Minutes watched per hour of the day, 0–23. */
    val hours: List<Long>,
    val lastActivityMs: Long?,
    /** Time watched by each profile index. */
    val perProfile: Map<Int, Long>,
    /** Most consecutive days with something watched. */
    val longestStreak: Int,
    /** Consecutive days up to today (or yesterday) with something watched. */
    val currentStreak: Int,
    /** First day of the current streak. */
    val streakSince: LocalDate? = null,
    /** The missed day the current streak's freeze covered, if one was used. */
    val freezeOn: LocalDate? = null,
) {
    companion object {
        val EMPTY = Stats(0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList(), List(24) { 0L }, null, emptyMap(), 0, 0)
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

    /**
     * Totals cover viewings since [sinceMs] (everything when null); the heatmap and
     * streaks always use every viewing so they stay meaningful for short ranges.
     */
    fun compute(
        viewings: List<Viewing>,
        titles: Map<String, TitleInfo>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        weeks: Int = 5,
        sinceMs: Long? = null,
        /** Days known to have had some watching that the viewings may not show (from live playback and sync). */
        extraActiveDays: Set<LocalDate> = emptySet(),
        /** Missed days already covered by a streak freeze (recorded when they happened). */
        freezeDays: Set<LocalDate> = emptySet(),
    ): Stats {
        if (viewings.isEmpty() && extraActiveDays.isEmpty()) return Stats.EMPTY
        fun dateOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = dateOf(nowMs)
        val firstDay = today.minusDays(today.dayOfWeek.value - 1L).minusWeeks(weeks - 1L)

        val perDay = mutableMapOf<LocalDate, Long>()
        for (v in viewings) if (v.atMs > 0) perDay.merge(dateOf(v.atMs), v.watchedMs / 60_000, Long::plus)
        // Any watching at all counts towards a streak, even under a minute.
        val activeSet = viewings.filter { it.atMs > 0 && it.watchedMs > 0 }.map { dateOf(it.atMs) }.toSet() + extraActiveDays
        val activeDays = activeSet.filter { !it.isAfter(today) }.sorted()
        val longest = Streaks.longest(activeDays, freezeDays)
        val currentRun = Streaks.current(activeSet, today, freezeDays)
        val current = currentRun.days

        val inRange = if (sinceMs == null) viewings else viewings.filter { it.atMs >= sinceMs }
        val perHour = LongArray(24)
        for (v in inRange) if (v.atMs > 0) perHour[Instant.ofEpochMilli(v.atMs).atZone(zone).hour] += v.watchedMs / 60_000

        val byTitle = inRange.groupBy { it.contentId }
        val topTitles = byTitle.map { (id, vs) ->
            TitleTotal(id, vs.first().series, vs.sumOf { it.watchedMs }, vs.size, vs.maxOf { it.atMs })
        }.sortedByDescending { it.watchedMs }

        val genreCounts = mutableMapOf<String, Int>()
        for (id in byTitle.keys) titles[id]?.genres?.forEach { genreCounts.merge(it, 1, Int::plus) }

        return Stats(
            totalWatchedMs = inRange.sumOf { it.watchedMs },
            movies = inRange.count { !it.series && it.completed },
            episodes = inRange.count { it.series && it.completed },
            shows = byTitle.values.count { it.first().series },
            inProgress = inRange.count { !it.completed && it.fraction > 0f },
            topTitles = topTitles.take(10),
            topGenres = genreCounts.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value },
            days = generateSequence(firstDay) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
                .map { DayTotal(it, perDay[it] ?: 0) }.toList(),
            hours = perHour.toList(),
            lastActivityMs = viewings.maxOfOrNull { it.atMs }?.takeIf { it > 0 },
            perProfile = inRange.groupBy { it.profileIndex }.mapValues { (_, vs) -> vs.sumOf { it.watchedMs } },
            longestStreak = maxOf(longest, current),
            currentStreak = current,
            streakSince = currentRun.since,
            freezeOn = currentRun.freezeOn,
        )
    }
}

/** A streak: days watched in a row, the day it began, and the missed day a freeze covered. */
data class StreakRun(val days: Int, val since: LocalDate?, val freezeOn: LocalDate?)

/**
 * Streaks with a weekly freeze: one missed day is forgiven if no other day was
 * forgiven within a week of it. Forgiven days keep a streak alive but don't add to it.
 *
 * Only freezes recorded when they happened ([freezes]) bridge older gaps, plus
 * yesterday, which can still be covered now. So turning freezes on never joins up
 * streaks that had already ended.
 */
object Streaks {
    const val FREEZE_EVERY_DAYS = 7L

    private fun ready(day: LocalDate, freezes: Set<LocalDate>) =
        freezes.none { it != day && kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(it, day)) < FREEZE_EVERY_DAYS }

    fun current(days: Set<LocalDate>, today: LocalDate, freezes: Set<LocalDate> = emptySet()): StreakRun {
        val yesterday = today.minusDays(1)
        var cursor = if (today in days) today else yesterday
        var count = 0
        var since: LocalDate? = null
        var freezeOn: LocalDate? = null
        while (true) {
            if (cursor in days) {
                count++
                since = cursor
                cursor = cursor.minusDays(1)
                continue
            }
            val before = cursor.minusDays(1)
            val covered = cursor in freezes || (cursor == yesterday && ready(cursor, freezes))
            if (before in days && covered) {
                if (freezeOn == null) freezeOn = cursor
                cursor = before
                continue
            }
            break
        }
        return StreakRun(count, since, freezeOn.takeIf { count > 0 })
    }

    fun longest(sortedDays: List<LocalDate>, freezes: Set<LocalDate> = emptySet()): Int {
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in sortedDays) {
            val p = previous
            run = when {
                p != null && p.plusDays(1) == day -> run + 1
                p != null && p.plusDays(2) == day && p.plusDays(1) in freezes -> run + 1
                else -> 1
            }
            longest = maxOf(longest, run)
            previous = day
        }
        return longest
    }
}
