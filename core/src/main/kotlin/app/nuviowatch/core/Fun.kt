package app.nuviowatch.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** An achievement a profile can unlock. */
enum class Badge(val emoji: String, val title: String, val rule: String) {
    NIGHT_OWL("🦉", "Night owl", "Watched after 11pm on 5 different days"),
    EARLY_BIRD("🐦", "Early bird", "Watched before 9am on 3 different days"),
    BINGE_KING("👑", "Binge king", "4+ episodes of one show in a day"),
    MOVIE_BUFF("🎬", "Movie buff", "Finished 10 films"),
    FINISHER("✅", "Finisher", "Finished 25 episodes"),
    EXPLORER("🧭", "Explorer", "Watched 5 different genres"),
    MARATHON("🏃", "Marathoner", "5 hours in a single day"),
    ON_FIRE("🔥", "On fire", "Watched 7 days in a row"),
    DEVOTED("💎", "Devoted", "Watched 30 days in a row"),
}

data class BadgeProgress(val badge: Badge, val earned: Boolean, val progress: Float)

/** A "type of viewer", for profile pages. */
enum class Personality(val title: String, val blurb: String) {
    BINGER("The Binger", "Once a show starts, there's no stopping."),
    NIGHT_OWL("The Night Owl", "Does their best watching after dark."),
    MOVIE_BUFF("The Movie Buff", "Prefers a good film to a long series."),
    FINISHER("The Finisher", "Never leaves a story half told."),
    SAMPLER("The Sampler", "Tries a bit of everything."),
    WEEKENDER("The Weekend Warrior", "Saves it all up for the weekend."),
    CASUAL("The Casual Viewer", "A little here, a little there."),
}

data class LeaderboardEntry(
    val profileIndex: Int,
    val points: Int,
    val watchedMs: Long,
    val finished: Int,
    val streak: Int,
)

data class AbandonedTitle(
    val profileIndex: Int,
    val contentId: String,
    val fallbackTitle: String,
    val season: Int?,
    val episode: Int?,
    val fraction: Float,
    val lastAtMs: Long,
)

data class Pick(
    val contentId: String,
    val fallbackTitle: String,
    /** Who in the family watched it, best fans first. */
    val fans: List<Int>,
    val reason: String,
    val score: Double,
)

/** Wrapped-style story for a month or a year. */
data class Wrapped(
    val label: String,
    val totalMs: Long,
    val titles: Int,
    val episodes: Int,
    val films: Int,
    val topWatcher: Pair<Int, Long>?,
    val topTitle: Pair<String, Long>?,
    val topGenre: String?,
    val busiestDay: Pair<LocalDate, Long>?,
    val longestBinge: Triple<Int, String, Int>?,
    val perProfile: List<Pair<Int, Long>>,
)

/** Games and personality features, all worked out from viewing history. */
object Fun {
    private fun date(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun hour(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).hour

    fun streaks(viewings: List<Viewing>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> {
        val stats = StatsCalculator.compute(viewings, emptyMap(), nowMs, zone)
        return stats.currentStreak to stats.longestStreak
    }

    // ------------------------------------------------------------ Badges

    fun badges(
        viewings: List<Viewing>,
        titles: Map<String, TitleInfo>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<BadgeProgress> {
        val dated = viewings.filter { it.atMs > 0 }
        val lateDays = dated.filter { hour(it.atMs, zone) >= 23 || hour(it.atMs, zone) < 4 }.map { date(it.atMs - 4 * 3_600_000, zone) }.toSet().size
        val earlyDays = dated.filter { hour(it.atMs, zone) in 5..8 }.map { date(it.atMs, zone) }.toSet().size
        val bestBinge = dated.filter { it.series }.groupBy { it.contentId to date(it.atMs, zone) }.maxOfOrNull { it.value.size } ?: 0
        val films = dated.count { !it.series && it.completed }
        val episodes = dated.count { it.series && it.completed }
        val genres = dated.flatMap { titles[it.contentId]?.genres.orEmpty() }.toSet().size
        val bestDayMs = dated.groupBy { date(it.atMs, zone) }.maxOfOrNull { (_, vs) -> vs.sumOf { it.watchedMs } } ?: 0
        val longest = streaks(dated, nowMs, zone).second

        fun p(value: Number, target: Number) = (value.toFloat() / target.toFloat()).coerceIn(0f, 1f)
        return Badge.entries.map { badge ->
            val progress = when (badge) {
                Badge.NIGHT_OWL -> p(lateDays, 5)
                Badge.EARLY_BIRD -> p(earlyDays, 3)
                Badge.BINGE_KING -> p(bestBinge, 4)
                Badge.MOVIE_BUFF -> p(films, 10)
                Badge.FINISHER -> p(episodes, 25)
                Badge.EXPLORER -> p(genres, 5)
                Badge.MARATHON -> p(bestDayMs, 5 * 3_600_000L)
                Badge.ON_FIRE -> p(longest, 7)
                Badge.DEVOTED -> p(longest, 30)
            }
            BadgeProgress(badge, progress >= 1f, progress)
        }
    }

    // ------------------------------------------------------------ Leaderboard

    /** Monday 00:00 of the week containing [nowMs]. */
    fun weekStart(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        date(nowMs, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone)

    /**
     * Points for the week: 1 per 10 minutes watched, 3 per finished episode,
     * 10 per finished film, and 5 per day of the current streak.
     */
    fun leaderboard(
        viewings: List<Viewing>,
        profiles: List<Int>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        weeksAgo: Long = 0,
    ): List<LeaderboardEntry> {
        val start = weekStart(nowMs, zone).minusWeeks(weeksAgo)
        val from = start.toInstant().toEpochMilli()
        val until = start.plusWeeks(1).toInstant().toEpochMilli()
        val end = minOf(nowMs, until - 1)
        return profiles.map { index ->
            val mine = viewings.filter { it.profileIndex == index }
            val week = mine.filter { it.atMs in from until until }
            val watched = week.sumOf { it.watchedMs }
            val finishedEpisodes = week.count { it.series && it.completed }
            val finishedFilms = week.count { !it.series && it.completed }
            val streak = streaks(mine.filter { it.atMs <= end }, end, zone).first
            LeaderboardEntry(
                profileIndex = index,
                points = (watched / 600_000).toInt() + finishedEpisodes * 3 + finishedFilms * 10 + streak * 5,
                watchedMs = watched,
                finished = finishedEpisodes + finishedFilms,
                streak = streak,
            )
        }.sortedWith(compareByDescending<LeaderboardEntry> { it.points }.thenByDescending { it.watchedMs })
    }

    /** Last week's winner, who wears the crown this week (null if nobody watched). */
    fun champion(viewings: List<Viewing>, profiles: List<Int>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int? =
        leaderboard(viewings, profiles, nowMs, zone, weeksAgo = 1).firstOrNull()?.takeIf { it.points > 0 }?.profileIndex

    // ------------------------------------------------------------ Personality

    fun personality(viewings: List<Viewing>, zone: ZoneId = ZoneId.systemDefault()): Personality {
        val dated = viewings.filter { it.atMs > 0 && it.watchedMs > 0 }
        if (dated.size < 5) return Personality.CASUAL
        val n = dated.size.toDouble()
        val late = dated.count { hour(it.atMs, zone) >= 22 || hour(it.atMs, zone) < 4 } / n
        val weekend = dated.count { date(it.atMs, zone).dayOfWeek.value >= 6 } / n
        val bingeDays = dated.filter { it.series }.groupBy { it.contentId to date(it.atMs, zone) }.count { it.value.size >= 3 }
        val films = dated.count { !it.series } / n
        val titles = dated.map { it.contentId }.toSet().size
        val completion = dated.count { it.completed } / n
        return when {
            bingeDays >= 3 -> Personality.BINGER
            late >= 0.4 -> Personality.NIGHT_OWL
            films >= 0.6 -> Personality.MOVIE_BUFF
            titles >= 8 && completion < 0.4 -> Personality.SAMPLER
            weekend >= 0.6 -> Personality.WEEKENDER
            completion >= 0.75 -> Personality.FINISHER
            else -> Personality.CASUAL
        }
    }

    // ------------------------------------------------------------ Abandoned

    /** Titles left unfinished and untouched for [idleDays] or more, newest first. */
    fun abandoned(viewings: List<Viewing>, nowMs: Long, idleDays: Long = 21): List<AbandonedTitle> =
        viewings.groupBy { it.profileIndex to it.contentId }.mapNotNull { (key, vs) ->
            val last = vs.maxBy { it.atMs }
            val idle = nowMs - last.atMs >= idleDays * 86_400_000L
            if (!idle || last.completed || last.atMs <= 0) return@mapNotNull null
            AbandonedTitle(key.first, key.second, last.fallbackTitle, last.season, last.episode, last.fraction, last.atMs)
        }.sortedByDescending { it.lastAtMs }

    // ------------------------------------------------------------ Family picks

    /** What others in the family enjoyed that [profileIndex] hasn't watched, best match first. */
    fun picks(
        profileIndex: Int,
        viewings: List<Viewing>,
        titles: Map<String, TitleInfo>,
        names: Map<Int, String>,
        zone: ZoneId = ZoneId.systemDefault(),
        limit: Int = 10,
    ): List<Pick> {
        val mine = viewings.filter { it.profileIndex == profileIndex }
        val seen = mine.map { it.contentId }.toSet()
        val myGenres = mine.flatMap { titles[it.contentId]?.genres.orEmpty() }.groupingBy { it }.eachCount()
        val totalGenre = myGenres.values.sum().coerceAtLeast(1).toDouble()

        return viewings.filter { it.profileIndex != profileIndex && it.contentId !in seen }
            .groupBy { it.contentId }
            .mapNotNull { (id, vs) ->
                // A fan watched a fair bit: finished a film or watched 3+ episodes.
                val fans = vs.groupBy { it.profileIndex }.filter { (_, own) ->
                    own.any { !it.series && it.completed } || own.count { it.series && it.completed } >= 3
                }
                if (fans.isEmpty()) return@mapNotNull null
                val genreMatch = titles[id]?.genres.orEmpty().sumOf { (myGenres[it] ?: 0) / totalGenre }
                val best = fans.maxBy { (_, own) -> own.sumOf { it.watchedMs } }
                val days = best.value.map { date(it.atMs, zone) }.let { ds -> ds.max().toEpochDay() - ds.min().toEpochDay() + 1 }
                val name = names[best.key] ?: "Someone"
                val episodes = best.value.count { it.series && it.completed }
                val reason = when {
                    vs.first().series && episodes >= 6 && days <= 7 -> "$name watched $episodes episodes in $days day${if (days == 1L) "" else "s"}"
                    vs.first().series -> "$name has watched $episodes episodes"
                    fans.size > 1 -> "${fans.size} of the family finished it"
                    else -> "$name finished it"
                }
                Pick(id, vs.first().fallbackTitle, fans.keys.sortedByDescending { k -> fans[k]!!.sumOf { it.watchedMs } }, reason, fans.size * 2.0 + genreMatch * 3 + episodes / 10.0)
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    // ------------------------------------------------------------ Wrapped

    fun wrapped(
        viewings: List<Viewing>,
        titles: Map<String, TitleInfo>,
        from: LocalDate,
        untilExclusive: LocalDate,
        label: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Wrapped? {
        val inRange = viewings.filter { it.atMs > 0 && it.watchedMs > 0 && date(it.atMs, zone).let { d -> !d.isBefore(from) && d.isBefore(untilExclusive) } }
        if (inRange.isEmpty()) return null
        val perProfile = inRange.groupBy { it.profileIndex }.map { (k, vs) -> k to vs.sumOf { it.watchedMs } }.sortedByDescending { it.second }
        val perTitle = inRange.groupBy { it.contentId }.map { (k, vs) -> k to vs.sumOf { it.watchedMs } }.sortedByDescending { it.second }
        val genre = inRange.flatMap { v -> titles[v.contentId]?.genres.orEmpty().map { it to v.watchedMs } }
            .groupBy({ it.first }, { it.second }).maxByOrNull { it.value.sum() }?.key
        val busiest = inRange.groupBy { date(it.atMs, zone) }.map { (d, vs) -> d to vs.sumOf { it.watchedMs } }.maxByOrNull { it.second }
        val binge = inRange.filter { it.series }.groupBy { Triple(it.profileIndex, it.contentId, date(it.atMs, zone)) }
            .maxByOrNull { it.value.size }?.takeIf { it.value.size >= 2 }?.let { Triple(it.key.first, it.key.second, it.value.size) }
        return Wrapped(
            label = label,
            totalMs = inRange.sumOf { it.watchedMs },
            titles = perTitle.size,
            episodes = inRange.count { it.series && it.completed },
            films = inRange.count { !it.series && it.completed },
            topWatcher = perProfile.firstOrNull(),
            topTitle = perTitle.firstOrNull(),
            topGenre = genre,
            busiestDay = busiest,
            longestBinge = binge,
            perProfile = perProfile,
        )
    }
}
