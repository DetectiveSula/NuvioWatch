package app.nuviowatch

import android.app.Application
import app.nuviowatch.data.AppGraph
import app.nuviowatch.monitor.MonitorService
import app.nuviowatch.monitor.Notifier
import app.nuviowatch.recap.Recaps

class NuvioWatchApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifier.createChannels(this)
        runCatching { Recaps.schedule(this) }
        if (graph.prefs.settings.value.enabled && graph.prefs.signedIn.value) {
            runCatching { MonitorService.start(this) }
        }
    }
}

val android.content.Context.graph: AppGraph
    get() = (applicationContext as NuvioWatchApp).graph
