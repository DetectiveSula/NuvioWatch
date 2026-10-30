package app.nuviowatch.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.nuviowatch.R
import app.nuviowatch.core.AlertKind
import app.nuviowatch.core.TitleInfo
import app.nuviowatch.core.WatchAlert
import app.nuviowatch.core.episodeLabel
import app.nuviowatch.graph
import app.nuviowatch.ui.MainActivity
import java.text.DateFormat
import java.util.Date

object Notifier {
    const val ONGOING_ID = 1
    private const val ALERTS = "watch_alerts"
    private const val ONGOING = "monitor"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ALERTS, "Watching alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When someone starts watching on Nuvio"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(ONGOING, "Monitoring status", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Nuvio Watch is checking for activity"
                setShowBadge(false)
            },
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showAlert(context: Context, alert: WatchAlert, title: TitleInfo?, poster: Bitmap?) {
        if (!canNotify(context)) return
        val entry = alert.entry
        val name = title?.name ?: entry.contentId
        val episode = episodeLabel(entry.season, entry.episode)
        val heading = when (alert.kind) {
            AlertKind.WATCHING -> "${alert.profile.name} started watching"
            AlertKind.FINISHED -> "${alert.profile.name} finished"
        }
        val id = alert.cooldownKey.hashCode()
        val builder = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(listOfNotNull(name, episode).joinToString(" · "))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFFFF5C7A.toInt())
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .addAction(0, "Mute ${alert.profile.name} today", MuteReceiver.intent(context, alert.profile.index, id))
        if (poster != null) builder.setLargeIcon(poster)
        if (alert.kind == AlertKind.WATCHING && entry.duration > 0) {
            builder.setProgress(100, (entry.fraction * 100).toInt(), false)
        }
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    fun ongoing(context: Context): Notification {
        val prefs = context.graph.prefs
        val error = prefs.lastPollError
        val text = when {
            error != null -> "Problem: $error"
            prefs.lastPollAt > 0 -> "Last checked " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(prefs.lastPollAt))
            else -> "Starting…"
        }
        return NotificationCompat.Builder(context, ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Watching for Nuvio activity")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .build()
    }

    fun updateOngoing(context: Context) {
        if (!canNotify(context)) return
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(ONGOING_ID, ongoing(context))
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
