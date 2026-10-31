package app.nuviowatch.recap

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nuviowatch.core.Fun
import app.nuviowatch.core.StatsCalculator
import app.nuviowatch.core.Viewing
import app.nuviowatch.data.Snapshot
import app.nuviowatch.graph
import app.nuviowatch.monitor.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.IsoFields

/**
 * Little announcements: new badges, Monday's weekly winner, and an evening
 * reminder when your own streak is about to end.
 */
object Nudges {
    private val times = listOf(LocalTime.of(9, 5), LocalTime.of(20, 0))

    fun schedule(context: Context) {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val next = (0L..1L).flatMap { d -> times.map { ZonedDateTime.of(LocalDate.now(zone).plusDays(d), it, zone) } }
            .first { it.isAfter(now) }
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pendingIntent(context))
    }

    fun viewings(snapshot: Snapshot, context: Context): List<Viewing> {
        val titles = context.graph.titles.titles.value
        return snapshot.profiles.flatMap { p ->
            StatsCalculator.viewings(p.index, snapshot.progress[p.index].orEmpty(), snapshot.watched[p.index].orEmpty(), titles)
        }
    }

    /** New badges for anyone. The first run only records what's already earned, so installing doesn't spam. */
    fun announceBadges(context: Context, snapshot: Snapshot) {
        val prefs = context.graph.prefs
        val titles = context.graph.titles.titles.value
        val all = viewings(snapshot, context)
        if (all.isEmpty()) return
        val now = System.currentTimeMillis()
        val announced = prefs.announcedBadges
        val updated = (announced ?: emptySet()).toMutableSet()
        for (p in snapshot.profiles) {
            val badges = Fun.badges(all.filter { it.profileIndex == p.index }, titles, now)
            for (badge in Fun.newBadges(p.index, badges, updated)) {
                if (announced != null) Notifier.showBadge(context, p.name, badge)
                updated += Fun.badgeKey(p.index, badge)
            }
        }
        prefs.announcedBadges = updated
    }

    /** Completed monthly challenges, for anyone. The first check records quietly, like badges. */
    fun announceChallenges(context: Context, snapshot: Snapshot) {
        val graph = context.graph
        val prefs = graph.prefs
        val all = viewings(snapshot, context)
        if (all.isEmpty()) return
        val month = java.time.YearMonth.now()
        val seeded = prefs.isMarked("challenges-seeded|$month")
        for (p in snapshot.profiles) {
            val mine = all.filter { it.profileIndex == p.index }
            for (c in Fun.challengesFor(month)) {
                val progress = Fun.challengeProgress(c, mine, graph.titles.titles.value, graph.episodes.episodes.value, month)
                val key = "challenge|$month|${p.index}|${c.name}"
                if (progress.done && !prefs.isMarked(key)) {
                    if (seeded) Notifier.showAnnouncement(context, "${c.emoji} ${p.name} completed ${c.title}", "${c.goalText}. +20 leaderboard points!", key.hashCode())
                    prefs.mark(key)
                }
            }
        }
        prefs.mark("challenges-seeded|$month")
    }

    /** Morning: episodes out today of shows this phone's owner follows (or anyone's, on a shared device). */
    private fun announceNewEpisodes(context: Context, snapshot: Snapshot) {
        val graph = context.graph
        val prefs = graph.prefs
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val me = prefs.deviceProfile
        val alerts = Fun.newEpisodes(viewings(snapshot, context), graph.episodes.episodes.value, start, start + 86_400_000L, System.currentTimeMillis())
            .filter { me == null || me in it.followers }
        for (a in alerts) {
            val key = "ep|${a.contentId}|${a.episode.season}|${a.episode.episode}"
            if (prefs.isMarked(key)) continue
            val show = graph.titles[a.contentId]?.name ?: continue
            val label = listOfNotNull(app.nuviowatch.core.episodeLabel(a.episode.season, a.episode.episode), a.episode.title).joinToString(" · ")
            Notifier.showNewEpisode(context, show, label)
            prefs.mark(key)
        }
    }

    /** A notification the first time a new version is seen; tapping it opens the app's Update button. */
    private suspend fun announceUpdate(context: Context) {
        val info = app.nuviowatch.data.AppUpdater.check(context) ?: return
        val prefs = context.graph.prefs
        if (prefs.lastSent("update") == info.versionName) return
        Notifier.showAnnouncement(context, "Nuvio Watch ${info.versionName} is ready", "Open the app and tap Update.", 53)
        prefs.setLastSent("update", info.versionName)
    }

    /** Monday morning: last week's champion. */
    private fun announceWinner(context: Context, snapshot: Snapshot) {
        val prefs = context.graph.prefs
        val today = LocalDate.now()
        if (today.dayOfWeek != DayOfWeek.MONDAY) return
        val week = "${today.get(IsoFields.WEEK_BASED_YEAR)}-W${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
        if (prefs.lastSent("winner") == week) return
        val board = Fun.leaderboard(viewings(snapshot, context), snapshot.profiles.map { it.index }, System.currentTimeMillis(), weeksAgo = 1)
        val winner = board.firstOrNull()?.takeIf { it.points > 0 } ?: return
        val name = snapshot.profiles.firstOrNull { it.index == winner.profileIndex }?.name ?: return
        val runnerUp = board.getOrNull(1)?.takeIf { it.points > 0 }?.let { r ->
            snapshot.profiles.firstOrNull { it.index == r.profileIndex }?.name?.let { "$it came second with ${r.points}" }
        }
        Notifier.showAnnouncement(context, "👑 $name won the week with ${winner.points} pts", runnerUp ?: "A new week starts now. Good luck, everyone!", 51)
        prefs.setLastSent("winner", week)
    }

    /** Evening: your own streak ends at midnight unless you watch something. */
    private fun remindStreak(context: Context, snapshot: Snapshot) {
        val prefs = context.graph.prefs
        val me = prefs.deviceProfile ?: return
        val today = LocalDate.now().toString()
        if (prefs.lastSent("streak") == today || LocalTime.now().hour < 18) return
        val name = snapshot.profiles.firstOrNull { it.index == me }?.name ?: return
        val streak = Fun.streakAtRisk(
            viewings(snapshot, context).filter { it.profileIndex == me }, System.currentTimeMillis(),
            extraDays = context.graph.prefs.activeDays[me].orEmpty(),
        ) ?: return
        Notifier.showAnnouncement(context, "🔥 $name, your $streak-day streak ends at midnight", "Watch something tonight to keep it going.", 52)
        prefs.setLastSent("streak", today)
    }

    suspend fun run(context: Context) {
        val graph = context.graph
        if (!graph.prefs.signedIn.value) return
        val snapshot = withTimeoutOrNull(7_000) { runCatching { graph.repository.refresh() }.getOrNull() }
            ?: graph.repository.snapshot.value
        announceBadges(context, snapshot)
        announceChallenges(context, snapshot)
        announceNewEpisodes(context, snapshot)
        announceUpdate(context)
        announceWinner(context, snapshot)
        remindStreak(context, snapshot)
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        8,
        Intent(context, NudgeReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Nudges.run(context)
            } finally {
                Nudges.schedule(context)
                result.finish()
            }
        }
    }
}
