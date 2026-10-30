package app.nuviowatch.monitor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.nuviowatch.graph
import java.time.LocalDate
import java.time.ZoneId

/** "Mute <name> today" on an alert: no more alerts about that profile until midnight. */
class MuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val profile = intent.getIntExtra(EXTRA_PROFILE, -1).takeIf { it >= 0 } ?: return
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        context.graph.prefs.setMutedUntil(profile, midnight)
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
    }

    companion object {
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_NOTIFICATION = "notification"

        fun intent(context: Context, profileIndex: Int, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            profileIndex,
            Intent(context, MuteReceiver::class.java)
                .putExtra(EXTRA_PROFILE, profileIndex)
                .putExtra(EXTRA_NOTIFICATION, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
