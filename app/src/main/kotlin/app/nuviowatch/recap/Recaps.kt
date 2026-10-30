package app.nuviowatch.recap

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nuviowatch.core.Digest
import app.nuviowatch.core.DigestBuilder
import app.nuviowatch.core.DigestPeriod
import app.nuviowatch.core.StatsCalculator
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

/** Daily and weekly family recaps, sent at the chosen hour (weekly on Sundays). */
object Recaps {
    /** Sets an alarm for the next recap time; call after boot and whenever the settings change. */
    fun schedule(context: Context) {
        val prefs = context.graph.prefs
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context)
        if (!prefs.dailyRecap && !prefs.weeklyRecap) {
            alarms.cancel(pending)
            return
        }
        val zone = ZoneId.systemDefault()
        var next = ZonedDateTime.of(LocalDate.now(zone), LocalTime.of(prefs.recapHour, 0), zone)
        if (!next.isAfter(ZonedDateTime.now(zone))) next = next.plusDays(1)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending)
    }

    /** Builds today's recap (and the weekly one on Sundays, or when [forceWeekly]) and shows them. */
    suspend fun send(context: Context, forceWeekly: Boolean = false): Boolean {
        val graph = context.graph
        val prefs = graph.prefs
        if (!prefs.signedIn.value) return false
        // Android gives an alarm about ten seconds, so fall back to the last refresh if Nuvio is slow.
        val snapshot = withTimeoutOrNull(7_000) { runCatching { graph.repository.refresh() }.getOrNull() }
            ?: graph.repository.snapshot.value
        val titles = graph.titles.titles.value
        val viewings = snapshot.profiles.flatMap { p ->
            StatsCalculator.viewings(p.index, snapshot.progress[p.index].orEmpty(), snapshot.watched[p.index].orEmpty(), titles)
        }
        val names = snapshot.profiles.associate { it.index to it.name }
        val now = System.currentTimeMillis()
        var sent = false
        fun show(digest: Digest?, id: Int) {
            if (digest != null) {
                Notifier.showDigest(context, digest, id)
                sent = true
            }
        }
        if (prefs.dailyRecap || forceWeekly) {
            show(DigestBuilder.build(DigestPeriod.DAY, viewings, names, { titles[it]?.name }, now), DAILY_ID)
        }
        val sunday = LocalDate.now().dayOfWeek == DayOfWeek.SUNDAY
        if (forceWeekly || (prefs.weeklyRecap && sunday)) {
            show(DigestBuilder.build(DigestPeriod.WEEK, viewings, names, { titles[it]?.name }, now), WEEKLY_ID)
        }
        return sent
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        7,
        Intent(context, RecapReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private const val DAILY_ID = 41
    private const val WEEKLY_ID = 42
}

/** Fired at recap time: sends the recap, then sets the alarm for tomorrow. */
class RecapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Recaps.send(context)
            } finally {
                Recaps.schedule(context)
                result.finish()
            }
        }
    }
}
