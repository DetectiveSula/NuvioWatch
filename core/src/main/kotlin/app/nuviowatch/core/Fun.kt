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

/** The next thing to watch in a series someone is following. */
data class UpNext(
    val profileIndex: Int,
    val contentId: String,
    val next: EpisodeInfo,
    /** True if they stopped part-way through [next]. */
    val resume: Boolean,
    val availableNow: Boolean,
    val lastAtMs: Long,
)

/** Something to watch together, and why. */
data class RouletteOption(val contentId: String, val fallbackTitle: String, val series: Boolean, val reason: String)

/** A new episode of a series someone follows. */
data class EpisodeAlert(val contentId: String, val episode: EpisodeInfo, val followers: List<Int>)

/** A monthly challenge; three are picked each month. */
enum class Challenge(val emoji: String, val title: String, val goalText: String, val goal: Int) {
    GENRE_FILMS("🎬", "Genre hopper", "Finish 3 films from different genres", 3),
    COMEBACK("🔁", "Comeback", "Finish something you left for 3+ weeks", 1),
    REGULAR("📅", "Regular", "Watch on 10 different days", 10),
    SOMETHING_NEW("🧭", "Something new", "Watch a genre you've never watched before", 1),
    SEASON("🏁", "Season finisher", "Finish a whole season", 1),
    VARIETY("📺", "Variety", "Watch 5 different titles", 5),
}

data class ChallengeProgress(val challenge: Challenge, val count: Int, val completedAt: Long?) {
    val done: Boolean get() = completedAt != null
    val fraction: Float get() = (count.toFloat() / challenge.goal).coerceAtMost(1f)
}

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
    /** The first thing watched in the period (content id, when). */
    val firstTitle: Pair<String, Long>? = null,
    /** Longest run of days anyone watched in the period (profile, days). */
    val longestStreak: Pair<Int, Int>? = null,
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
        bonus: Map<Int, Int> = emptyMap(),
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
                points = (watched / 600_000).toInt() + finishedEpisodes * 3 + finishedFilms * 10 + streak * 5 + (bonus[index] ?: 0),
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

    /**
     * Titles left unfinished and untouched for [idleDays] or more, newest first.
     * Films and episodes stopped before [minFraction] were only sampled, so they don't count.
     */
    fun abandoned(viewings: List<Viewing>, nowMs: Long, idleDays: Long = 21, minFraction: Float = 0.10f): List<AbandonedTitle> =
        viewings.groupBy { it.profileIndex to it.contentId }.mapNotNull { (key, vs) ->
            val last = vs.maxBy { it.atMs }
            val idle = nowMs - last.atMs >= idleDays * 86_400_000L
            if (!idle || last.completed || last.atMs <= 0) return@mapNotNull null
            // A series counts as started once any episode was finished; a film needs real progress.
            val started = last.fraction >= minFraction || vs.any { it.series && it.completed }
            if (!started) return@mapNotNull null
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

    // ------------------------------------------------------------ Up next

    /**
     * For each series someone watched in the last [activeDays], the episode to watch
     * next: the one they stopped in, or the one after their latest. Ready ones come
     * first (most recently watched), then upcoming ones by air date.
     */
    fun upNext(
        viewings: List<Viewing>,
        episodes: Map<String, List<EpisodeInfo>>,
        nowMs: Long,
        activeDays: Long = 60,
    ): List<UpNext> {
        val since = nowMs - activeDays * 86_400_000L
        return viewings.filter { it.series && it.season != null && it.episode != null }
            .groupBy { it.profileIndex to it.contentId }
            .mapNotNull { (key, vs) ->
                val lastAt = vs.maxOf { it.atMs }
                if (lastAt < since) return@mapNotNull null
                val list = episodes[key.second] ?: return@mapNotNull null
                val latest = vs.maxWith(compareBy({ it.season!! }, { it.episode!! }))
                val (next, resume) = if (!latest.completed && latest.fraction > 0f) {
                    (list.firstOrNull { it.season == latest.season && it.episode == latest.episode } ?: return@mapNotNull null) to true
                } else {
                    (list.firstOrNull { it.season > latest.season!! || (it.season == latest.season && it.episode > latest.episode!!) } ?: return@mapNotNull null) to false
                }
                val available = next.releasedMs == null || next.releasedMs <= nowMs
                UpNext(key.first, key.second, next, resume, available, lastAt)
            }
            .sortedWith(
                compareByDescending<UpNext> { it.availableNow }
                    .thenByDescending { if (it.availableNow) it.lastAtMs else 0 }
                    .thenBy { it.next.releasedMs ?: Long.MAX_VALUE },
            )
    }

    // ------------------------------------------------------------ Roulette

    /**
     * Ideas for [viewers] to watch together: things they're part-way through,
     * then things one of them loved (finished or rated 4+) that others haven't seen.
     */
    fun roulette(viewers: Set<Int>, viewings: List<Viewing>, ratings: List<Rating> = emptyList(), limit: Int = 12, names: Map<Int, String> = emptyMap()): List<RouletteOption> {
        fun name(i: Int) = names[i] ?: "Someone"
        val byTitle = viewings.filter { it.profileIndex in viewers }.groupBy { it.contentId }
        val options = mutableListOf<Pair<RouletteOption, Double>>()
        for ((id, vs) in byTitle) {
            val who = vs.map { it.profileIndex }.toSet()
            val latest = vs.maxBy { it.atMs }
            val unfinished = !latest.completed || latest.series
            val loved = vs.filter { v -> v.completed && (!v.series || vs.count { it.profileIndex == v.profileIndex && it.completed } >= 3) }.map { it.profileIndex }.toSet() +
                ratings.filter { it.contentId == id && it.stars >= 4 && it.profileIndex in viewers }.map { it.profileIndex }
            val unseen = viewers - who
            when {
                who.size >= 2 && unfinished -> options += RouletteOption(id, latest.fallbackTitle, latest.series, "${who.joinToString(" and ") { name(it) }} are both on it") to (3.0 + who.size)
                loved.isNotEmpty() && unseen.isNotEmpty() -> options += RouletteOption(
                    id, latest.fallbackTitle, latest.series,
                    "${name(loved.first())} loved it; ${unseen.joinToString(" and ") { name(it) }} ${if (unseen.size == 1) "hasn't" else "haven't"} seen it",
                ) to (2.0 + loved.size)
                unfinished && latest.fraction in 0.05f..0.95f -> options += RouletteOption(id, latest.fallbackTitle, latest.series, "${name(latest.profileIndex)} left it at ${(latest.fraction * 100).toInt()}%") to 1.0
                unfinished && latest.series -> options += RouletteOption(id, latest.fallbackTitle, true, "${name(latest.profileIndex)} is following it") to 0.8
            }
        }
        return options.sortedByDescending { it.second }.map { it.first }.distinctBy { it.contentId }.take(limit)
    }

    // ------------------------------------------------------------ New episodes

    /** Episodes released in [fromMs, toMs) of series anyone watched in the last [activeDays]. */
    fun newEpisodes(viewings: List<Viewing>, episodes: Map<String, List<EpisodeInfo>>, fromMs: Long, toMs: Long, nowMs: Long, activeDays: Long = 90): List<EpisodeAlert> {
        val since = nowMs - activeDays * 86_400_000L
        val followers = viewings.filter { it.series && it.atMs >= since }.groupBy { it.contentId }.mapValues { (_, vs) -> vs.map { it.profileIndex }.distinct() }
        return followers.flatMap { (id, who) ->
            episodes[id].orEmpty().filter { e -> e.releasedMs != null && e.releasedMs >= fromMs && e.releasedMs < toMs }.map { EpisodeAlert(id, it, who) }
        }.sortedBy { it.episode.releasedMs }
    }

    // ------------------------------------------------------------ Monthly challenges

    /** Three challenges for the month, rotating through the list. */
    fun challengesFor(month: java.time.YearMonth): List<Challenge> {
        val all = Challenge.entries
        val start = (month.year * 12 + month.monthValue) % all.size
        return (0 until 3).map { all[(start + it * 2) % all.size] }.distinct()
    }

    fun challengeProgress(
        challenge: Challenge,
        mine: List<Viewing>,
        titles: Map<String, TitleInfo>,
        episodes: Map<String, List<EpisodeInfo>>,
        month: java.time.YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ChallengeProgress {
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val until = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val inMonth = mine.filter { it.atMs in from until until && it.watchedMs > 0 }.sortedBy { it.atMs }
        fun reach(goal: Int, steps: List<Long>) = ChallengeProgress(challenge, steps.size, steps.getOrNull(goal - 1))
        return when (challenge) {
            Challenge.GENRE_FILMS -> {
                val genres = mutableSetOf<String>()
                val steps = inMonth.filter { !it.series && it.completed }.mapNotNull { v ->
                    titles[v.contentId]?.genres?.firstOrNull { it !in genres }?.let { genres += it; v.atMs }
                }
                reach(3, steps)
            }
            Challenge.COMEBACK -> {
                val steps = inMonth.filter { it.completed }.mapNotNull { v ->
                    val before = mine.filter { it.contentId == v.contentId && it.atMs < v.atMs }.maxOfOrNull { it.atMs }
                    v.atMs.takeIf { before != null && v.atMs - before >= 21 * 86_400_000L }
                }
                reach(1, steps)
            }
            Challenge.REGULAR -> reach(10, inMonth.distinctBy { date(it.atMs, zone) }.map { it.atMs })
            Challenge.SOMETHING_NEW -> {
                val known = mine.filter { it.atMs < from }.flatMap { titles[it.contentId]?.genres.orEmpty() }.toSet()
                reach(1, inMonth.filter { v -> titles[v.contentId]?.genres.orEmpty().any { it !in known } }.map { it.atMs })
            }
            Challenge.SEASON -> {
                val steps = inMonth.filter { it.series && it.completed && it.season != null }.groupBy { it.contentId to it.season!! }.mapNotNull { (key, vs) ->
                    val season = episodes[key.first].orEmpty().filter { it.season == key.second && (it.releasedMs ?: 0) <= until }
                    val done = mine.filter { it.contentId == key.first && it.season == key.second && it.completed }.mapNotNull { it.episode }.toSet()
                    vs.maxOf { it.atMs }.takeIf { season.isNotEmpty() && season.all { it.episode in done } }
                }.sorted()
                reach(1, steps)
            }
            Challenge.VARIETY -> reach(5, inMonth.distinctBy { it.contentId }.map { it.atMs })
        }
    }

    /** 20 leaderboard points for each challenge completed during the given week. */
    fun challengeBonus(
        viewings: List<Viewing>,
        profiles: List<Int>,
        titles: Map<String, TitleInfo>,
        episodes: Map<String, List<EpisodeInfo>>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        weeksAgo: Long = 0,
    ): Map<Int, Int> {
        val start = weekStart(nowMs, zone).minusWeeks(weeksAgo)
        val from = start.toInstant().toEpochMilli()
        val until = start.plusWeeks(1).toInstant().toEpochMilli()
        val months = setOf(java.time.YearMonth.from(start), java.time.YearMonth.from(start.plusDays(6)))
        return profiles.associateWith { index ->
            val mine = viewings.filter { it.profileIndex == index }
            months.sumOf { month ->
                challengesFor(month).count { c -> challengeProgress(c, mine, titles, episodes, month, zone).completedAt?.let { it in from until until } == true }
            } * 20
        }.filterValues { it > 0 }
    }

    // ------------------------------------------------------------ Announcements

    /** Badges earned now that weren't in [alreadyAnnounced] (keys like "3|NIGHT_OWL"). */
    fun newBadges(profileIndex: Int, current: List<BadgeProgress>, alreadyAnnounced: Set<String>): List<Badge> =
        current.filter { it.earned && badgeKey(profileIndex, it.badge) !in alreadyAnnounced }.map { it.badge }

    fun badgeKey(profileIndex: Int, badge: Badge) = "$profileIndex|${badge.name}"

    /**
     * The streak someone would lose at midnight: they watched up to yesterday
     * (a streak of 2+ days) but nothing yet today. Null if nothing's at risk.
     */
    fun streakAtRisk(viewings: List<Viewing>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val today = date(nowMs, zone)
        if (viewings.any { it.atMs > 0 && it.watchedMs > 0 && date(it.atMs, zone) == today }) return null
        val streak = streaks(viewings, nowMs, zone).first
        return streak.takeIf { it >= 2 }
    }

    // ------------------------------------------------------------ Shared shows and spoilers

    private val episodePattern = Regex("""S(\d+)\D{0,3}E(\d+)""", RegexOption.IGNORE_CASE)

    /** Season and episode from a player subtitle: "S6, E1: …", "S6:E1 – …", "S6 · E1". */
    fun episodeOf(subtitle: String?): Pair<Int, Int>? =
        subtitle?.let { episodePattern.find(it) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /** The furthest episode [profileIndex] has reached in a series (watched or started). */
    fun latestEpisode(viewings: List<Viewing>, profileIndex: Int, contentId: String): Pair<Int, Int>? =
        viewings.filter { it.profileIndex == profileIndex && it.contentId == contentId && it.season != null && it.episode != null }
            .maxWithOrNull(compareBy({ it.season!! }, { it.episode!! }))
            ?.let { it.season!! to it.episode!! }

    /**
     * How many episodes [theirs] is ahead of [mine] (0 or less: not ahead). Uses the
     * real episode list when known, otherwise counts within a season and treats each
     * later season as at least one episode ahead.
     */
    fun episodesAhead(theirs: Pair<Int, Int>, mine: Pair<Int, Int>?, episodes: List<EpisodeInfo>?): Int {
        val my = mine ?: (1 to 0)
        if (!episodes.isNullOrEmpty()) {
            fun index(p: Pair<Int, Int>) = episodes.indexOfFirst { it.season == p.first && it.episode == p.second }
            val t = index(theirs)
            val m = if (mine == null) -1 else index(my)
            if (t >= 0 && (m >= 0 || mine == null)) return t - m
        }
        return when {
            theirs.first == my.first -> theirs.second - my.second
            theirs.first > my.first -> maxOf(1, theirs.second)
            else -> -1
        }
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
            firstTitle = inRange.minByOrNull { it.atMs }?.let { it.contentId to it.atMs },
            longestStreak = inRange.groupBy { it.profileIndex }.map { (p, vs) ->
                val days = vs.map { date(it.atMs, zone) }.toSortedSet()
                var best = 0
                var run = 0
                var prev: LocalDate? = null
                for (d in days) {
                    run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
                    best = maxOf(best, run)
                    prev = d
                }
                p to best
            }.maxByOrNull { it.second }?.takeIf { it.second >= 2 },
        )
    }
}
