package app.nuviowatch.monitor

import android.content.Context
import app.nuviowatch.data.Prefs
import app.nuviowatch.graph
import java.time.LocalTime

/** No alerts overnight. Summaries of what people watched are kept and sent together in the morning. */
object QuietHours {
    fun isQuiet(prefs: Prefs, now: LocalTime = LocalTime.now()): Boolean = isQuiet(prefs.quietOn, prefs.quietStart, prefs.quietEnd, now.hour)

    fun isQuiet(on: Boolean, start: Int, end: Int, hour: Int): Boolean = when {
        !on || start == end -> false
        start < end -> hour in start until end
        else -> hour >= start || hour < end // over midnight, e.g. 23:00 to 07:00
    }

    fun hold(prefs: Prefs, heading: String, details: String) {
        prefs.heldSummaries = (prefs.heldSummaries + "$heading\t$details").takeLast(30)
    }

    /** Once quiet hours are over: one notification with everything that was watched overnight. */
    fun deliver(context: Context) {
        val prefs = context.graph.prefs
        if (isQuiet(prefs)) return
        val held = prefs.heldSummaries
        if (held.isEmpty()) return
        prefs.heldSummaries = emptyList()
        Notifier.showOvernight(context, held.map { line -> line.substringBefore('\t') to line.substringAfter('\t', "") })
    }
}
