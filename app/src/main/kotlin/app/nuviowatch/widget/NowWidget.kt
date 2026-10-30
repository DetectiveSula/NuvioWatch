package app.nuviowatch.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import app.nuviowatch.R
import app.nuviowatch.core.PlayState
import app.nuviowatch.graph
import app.nuviowatch.live.LiveHub
import app.nuviowatch.ui.MainActivity

/** Home-screen widget: who's watching Nuvio right now, or who watched last. */
class NowWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        private val rows = listOf(
            Row(R.id.row1, R.id.dot1, R.id.title1, R.id.sub1),
            Row(R.id.row2, R.id.dot2, R.id.title2, R.id.sub2),
            Row(R.id.row3, R.id.dot3, R.id.title3, R.id.sub3),
        )

        private data class Row(val row: Int, val dot: Int, val title: Int, val sub: Int)

        private data class Line(val title: String, val sub: String, val live: Boolean?)

        /** Redraws every placed widget; cheap, so it's called whenever live state changes. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(context.packageName, R.layout.widget_now)
            val lines = lines(context)
            rows.forEachIndexed { i, row ->
                val line = lines.getOrNull(i)
                views.setViewVisibility(row.row, if (line == null) View.GONE else View.VISIBLE)
                if (line != null) {
                    views.setTextViewText(row.title, line.title)
                    views.setTextViewText(row.sub, line.sub)
                    views.setViewVisibility(row.dot, if (line.live == null) View.GONE else View.VISIBLE)
                    views.setImageViewResource(row.dot, if (line.live == true) R.drawable.widget_dot_live else R.drawable.widget_dot_paused)
                }
            }
            val nobody = lines.none { it.live != null }
            views.setTextViewText(R.id.empty, if (nobody) "Nobody's watching right now" else "")
            views.setViewVisibility(R.id.empty, if (nobody) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            manager.updateAppWidget(ids, views)
        }

        private fun lines(context: Context): List<Line> {
            val graph = context.graph
            if (!graph.prefs.signedIn.value) return listOf(Line("Open Nuvio Watch to sign in", "", null))
            val now = System.currentTimeMillis()
            val snapshot = graph.repository.snapshot.value
            val titles = graph.titles.titles.value

            val live = graph.prefs.liveDevices.values
                .filter { it.state != PlayState.STOPPED && now - it.at < 5 * 60_000L }
                .sortedByDescending { it.at }
                .map { event ->
                    val who = LiveHub.profileFor(event, snapshot, titles)?.name ?: "Someone"
                    val name = LiveHub.titleFor(event, titles)?.name ?: event.title
                    val playing = event.state == PlayState.PLAYING
                    Line(
                        title = "$who · $name",
                        sub = listOfNotNull(if (playing) "Watching" else "Paused", event.subtitle, event.deviceName).joinToString(" · "),
                        live = playing,
                    )
                }
            if (live.isNotEmpty()) return live

            // Nobody live: show who watched most recently.
            val recent = snapshot.profiles.mapNotNull { p ->
                val entry = (snapshot.progress[p.index].orEmpty() + listOfNotNull(graph.prefs.liveEntries[p.index]))
                    .maxByOrNull { it.lastWatchedMs } ?: return@mapNotNull null
                Triple(p, entry, titles[entry.contentId]?.name ?: entry.contentId)
            }.sortedByDescending { it.second.lastWatchedMs }.take(2)
            return recent.map { (p, entry, name) ->
                val ago = DateUtils.getRelativeTimeSpanString(entry.lastWatchedMs, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                Line("${p.name} · $name", "Last watched $ago", null)
            }
        }
    }
}
