package app.nuviowatch.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.nuviowatch.graph
import app.nuviowatch.live.LiveHub
import app.nuviowatch.live.LiveReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps checking Nuvio while monitoring is switched on.
 *
 * A coroutine loop does the checks while the phone is awake; an alarm wakes the
 * phone for the next check when it is asleep.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var live: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        val prefs = graph.prefs
        if (!prefs.settings.value.enabled || !prefs.signedIn.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (live?.isActive != true) live = scope.launch { listenForLiveReports() }
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    check()
                    delay(prefs.settings.value.intervalSeconds * 1000L)
                }
            }
        } else if (intent?.action == ACTION_POLL) {
            scope.launch { check() }
        }
        return START_STICKY
    }

    /** Keeps a streaming connection to the account's live channel, reconnecting as needed. */
    private suspend fun listenForLiveReports() {
        var backoff = 5_000L
        while (currentCoroutineContext().isActive) {
            val prefs = graph.prefs
            val relay = LiveHub.relay(this)
            if (relay == null) {
                prefs.setStatus("stream", "Waiting: account id missing, sign out and in again")
                delay(30_000)
                continue
            }
            try {
                prefs.setStatus("stream", "Connecting…")
                relay.listen(
                    since = prefs.liveSince ?: "15m",
                    onOpen = { prefs.setStatus("stream", "Connected, listening") },
                ) { id, event ->
                    prefs.setStatus("received", "${event.deviceName}: ${event.state.name.lowercase()} · ${event.title}")
                    LiveReceiver.onEvent(this, id, event)
                }
                prefs.setStatus("stream", "Disconnected, reconnecting")
                backoff = 5_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                prefs.setStatus("stream", "Error: ${e.message ?: e.javaClass.simpleName} (retrying)")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(120_000L)
            }
        }
    }

    private suspend fun check() {
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NuvioWatch:poll")
        wakeLock.acquire(60_000L)
        try {
            Monitor.poll(this)
            Notifier.updateOngoing(this)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            scheduleWakeUp(this)
        }
    }

    private fun enterForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ONGOING_ID, Notifier.ongoing(this), type)
    }

    override fun onDestroy() {
        cancelWakeUp(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_POLL = "app.nuviowatch.POLL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            cancelWakeUp(context)
            context.stopService(Intent(context, MonitorService::class.java))
        }

        private fun wakeUpIntent(context: Context) = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PollReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun scheduleWakeUp(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val at = System.currentTimeMillis() + context.graph.prefs.settings.value.intervalSeconds * 1000L + 5_000L
            val pending = wakeUpIntent(context)
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                // Without the "Alarms & reminders" permission Android may delay this a little.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }

        fun cancelWakeUp(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(wakeUpIntent(context))
        }
    }
}

/** Fired by the wake-up alarm: asks the running service to check now. */
class PollReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!context.graph.prefs.settings.value.enabled) return
        val poll = Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_POLL)
        try {
            context.startService(poll)
        } catch (e: IllegalStateException) {
            // The service isn't running and the app is in the background: start it in the foreground.
            runCatching { ContextCompat.startForegroundService(context, poll) }
        }
    }
}

/** Restarts monitoring after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.graph.prefs
        if (prefs.settings.value.enabled && prefs.signedIn.value) {
            runCatching { MonitorService.start(context) }
        }
    }
}
