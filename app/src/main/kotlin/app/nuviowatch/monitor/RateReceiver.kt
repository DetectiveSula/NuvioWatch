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

/** A quick rating straight from the "How was it?" notification. */
class RateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val profile = intent.getIntExtra("profile", -1).takeIf { it >= 0 } ?: return
        val contentId = intent.getStringExtra("content") ?: return
        val stars = intent.getIntExtra("stars", 0).takeIf { it in 1..5 } ?: return
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra("notification", 0))
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Inbox.rate(context, profile, contentId, stars)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        fun intent(context: Context, profile: Int, contentId: String, stars: Int, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId * 10 + stars,
            Intent(context, RateReceiver::class.java)
                .putExtra("profile", profile)
                .putExtra("content", contentId)
                .putExtra("stars", stars)
                .putExtra("notification", notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
