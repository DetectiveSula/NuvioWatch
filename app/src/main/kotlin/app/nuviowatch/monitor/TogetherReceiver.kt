package app.nuviowatch.monitor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.nuviowatch.live.Inbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** "Watch together?" on a same-show alert: pings the other person. */
class TogetherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val profile = intent.getIntExtra(EXTRA_PROFILE, -1).takeIf { it >= 0 } ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Inbox.ping(context, profile, "together", title)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_NOTIFICATION = "notification"

        fun intent(context: Context, profile: Int, title: String, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, TogetherReceiver::class.java)
                .putExtra(EXTRA_PROFILE, profile)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_NOTIFICATION, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
