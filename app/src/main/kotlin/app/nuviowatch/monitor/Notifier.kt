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
import androidx.core.graphics.drawable.toBitmap
import app.nuviowatch.R
import app.nuviowatch.core.Digest
import app.nuviowatch.core.Badge
import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.Ping
import app.nuviowatch.core.Prediction
import app.nuviowatch.core.Reaction
import app.nuviowatch.core.Profile
import app.nuviowatch.core.SummaryLine
import app.nuviowatch.core.WatchSession
import app.nuviowatch.graph
import app.nuviowatch.ui.MainActivity
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.text.DateFormat
import java.util.Date

object Notifier {
    const val ONGOING_ID = 1
    private const val ALERTS = "watch_alerts"
    private const val ONGOING = "monitor"
    private const val RECAPS = "recaps"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ALERTS, "Watching alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When someone starts watching on Nuvio"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(RECAPS, "Daily and weekly recaps", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "What the family watched today and this week"
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

    /**
     * One notification per viewing session. Later activity in the same session
     * replaces it silently, so a long sitting ends up as a single, correct summary.
     */
    fun showSummary(context: Context, profile: Profile, session: WatchSession, lines: List<SummaryLine>, poster: Bitmap?) {
        if (!canNotify(context) || lines.isEmpty()) return
        val main = lines.first()
        val builder = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(main.title)
            .setContentText(main.text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFFFF5C7A.toInt())
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setSilent(session.isUpdate)
            .setWhen(session.lastActivityAt)
            .setShowWhen(true)
            .addAction(0, "Mute ${profile.name} today", MuteReceiver.intent(context, profile.index, session.notificationId))
        if (poster != null) builder.setLargeIcon(poster)
        if (lines.size > 1) {
            val inbox = NotificationCompat.InboxStyle().setBigContentTitle("${profile.name} watched ${lines.size} titles")
            lines.forEach { inbox.addLine("${it.title.removePrefix(profile.name + " ")} · ${it.text}") }
            builder.setStyle(inbox)
        }
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(session.notificationId, builder.build())
    }

    /** "Ishti started watching" from another device, updated in place when they stop. */
    /**
     * Live alerts about each person use their own channel, so each can have a
     * different sound and vibration (set in Android's notification settings).
     */
    fun personChannel(context: Context, profileIndex: Int, name: String): String {
        val id = "person_$profileIndex"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(
                NotificationChannel(id, "$name starts watching", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Live alerts when $name presses play"
                    enableVibration(true)
                    // A different rhythm per person, so you can tell who it is without looking.
                    vibrationPattern = longArrayOf(0) + LongArray((profileIndex % 4) + 1) { 120L }.flatMap { listOf(it, 90L) }
                }
            )
        }
        return id
    }

    fun showLive(
        context: Context,
        event: LiveEvent,
        heading: String,
        profileIndex: Int?,
        poster: Bitmap?,
        silent: Boolean,
        profileName: String? = null,
        subtitleOverride: String? = null,
    ) {
        if (!canNotify(context)) return
        val id = "live|${event.deviceId}".hashCode()
        val subtitle = subtitleOverride ?: event.subtitle?.takeIf { it.isNotBlank() }
        val text = listOfNotNull(event.title, subtitle, "on ${event.deviceName}").joinToString(" · ")
        val channel = if (profileIndex != null && profileName != null) personChannel(context, profileIndex, profileName) else ALERTS
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFFFF5C7A.toInt())
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setSilent(silent)
            .setWhen(event.at)
            .setShowWhen(true)
        if (poster != null) builder.setLargeIcon(poster)
        if (profileIndex != null) {
            builder.addAction(0, "Mute today", MuteReceiver.intent(context, profileIndex, id))
        }
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    suspend fun loadBitmap(context: Context, url: String?): Bitmap? {
        if (url == null) return null
        val result = runCatching {
            context.imageLoader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).size(256).build())
        }.getOrNull()
        return (result as? SuccessResult)?.drawable?.toBitmap()
    }

    fun showDigest(context: Context, digest: Digest, id: Int) {
        if (!canNotify(context)) return
        val style = NotificationCompat.InboxStyle().setBigContentTitle(digest.title)
        digest.lines.forEach { style.addLine(it) }
        val notification = NotificationCompat.Builder(context, RECAPS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(digest.title)
            .setContentText(digest.summary)
            .setStyle(style)
            .setColor(0xFF8C9BFF.toInt())
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun showBadge(context: Context, name: String, badge: Badge) =
        showAnnouncement(context, "${badge.emoji} $name unlocked ${badge.title}", badge.rule, "badge|$name|${badge.name}".hashCode())

    fun showAnnouncement(context: Context, title: String, text: String, id: Int) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, RECAPS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFFFFD166.toInt())
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /** "Mani: Come watch with me!" / "Mani wants to watch … together". */
    fun showPing(context: Context, p: Ping) {
        if (!canNotify(context)) return
        val title = if (p.kind == "join") "🍿 ${p.fromName}: Come watch with me!" else "🍿 ${p.fromName} wants to watch together"
        val text = listOfNotNull(p.title, p.deviceName?.let { "on $it" }).joinToString(" · ")
        val notification = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(p.id.hashCode(), notification)
    }

    /** "Mani and Ishti are both watching …", with a button that asks the other person to watch together. */
    fun showSameShow(context: Context, heading: String, text: String, askProfile: Int?, askName: String?, title: String) {
        if (!canNotify(context)) return
        val builder = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
        val id = "same|$heading".hashCode()
        if (askProfile != null && askName != null) {
            builder.addAction(0, "Watch together?", TogetherReceiver.intent(context, askProfile, title, id))
        }
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    fun showReaction(context: Context, r: Reaction) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${r.fromName} reacted ${r.emoji}")
            .setContentText("to ${r.title}")
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(60_000)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(r.id.hashCode(), notification)
    }

    fun showPrediction(context: Context, p: Prediction) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🔮 ${p.byName} made a prediction")
            .setContentText("Open the Family tab to vote")
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(p.id.hashCode(), notification)
    }

    fun showPredictionResult(context: Context, question: String, cameTrue: Boolean, winners: List<String>) {
        if (!canNotify(context)) return
        val text = (if (cameTrue) "It happened! " else "It didn't happen. ") +
            if (winners.isEmpty()) "Nobody called it." else "Called it: ${winners.joinToString()}"
        val notification = NotificationCompat.Builder(context, RECAPS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (cameTrue) "✅ $question" else "❌ $question")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(question.hashCode(), notification)
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
