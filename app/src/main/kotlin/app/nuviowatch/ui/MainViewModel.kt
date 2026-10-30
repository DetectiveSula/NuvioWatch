package app.nuviowatch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nuviowatch.core.Profile
import app.nuviowatch.core.ProgressEntry
import app.nuviowatch.core.Stats
import app.nuviowatch.core.StatsCalculator
import app.nuviowatch.core.TitleInfo
import app.nuviowatch.core.Viewing
import app.nuviowatch.data.MonitorSettings
import app.nuviowatch.data.Snapshot
import app.nuviowatch.graph
import app.nuviowatch.monitor.Monitor
import app.nuviowatch.monitor.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class ServerStatus { DETECTING, READY, NOT_FOUND }

enum class StatsRange(val label: String, val heading: String, val days: Int?) {
    MONTH("30 days", "Watched in 30 days", 30),
    YEAR("Year", "Watched this year", 365),
    ALL("All", "Watched all time", null),
}

/** What one profile is up to, for the "Now" screen. */
data class ProfileNow(
    val profile: Profile,
    val latest: ProgressEntry?,
    val totalToday: Long,
)

data class UiState(
    val signedIn: Boolean = false,
    val email: String = "",
    val server: ServerStatus = ServerStatus.DETECTING,
    val serverUrl: String = "",
    val nuvioInstalled: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val snapshot: Snapshot = Snapshot(),
    val titles: Map<String, TitleInfo> = emptyMap(),
    val viewings: List<Viewing> = emptyList(),
    val now: List<ProfileNow> = emptyList(),
    val settings: MonitorSettings = MonitorSettings(),
    val lastPollAt: Long = 0,
    val lastPollError: String? = null,
    val clock: Long = System.currentTimeMillis(),
) {
    fun stats(profileIndex: Int?, range: StatsRange = StatsRange.MONTH): Stats = StatsCalculator.compute(
        viewings.filter { profileIndex == null || it.profileIndex == profileIndex },
        titles,
        clock,
        sinceMs = range.days?.let { clock - it * 86_400_000L },
    )

    fun profile(index: Int) = snapshot.profiles.firstOrNull { it.index == index }

    fun profileName(index: Int) = snapshot.profiles.firstOrNull { it.index == index }?.name ?: "Profile $index"
}

private data class Local(
    val server: ServerStatus = ServerStatus.DETECTING,
    val loading: Boolean = false,
    val error: String? = null,
    val live: Map<Int, ProgressEntry> = emptyMap(),
    val lastPollAt: Long = 0,
    val lastPollError: String? = null,
    val clock: Long = System.currentTimeMillis(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val prefs = graph.prefs
    private val local = MutableStateFlow(Local())

    val state: StateFlow<UiState> = combine(
        prefs.signedIn,
        prefs.settings,
        graph.repository.snapshot,
        graph.titles.titles,
        local,
    ) { signedIn, settings, snapshot, titles, local ->
        val viewings = snapshot.profiles.flatMap { p ->
            StatsCalculator.viewings(p.index, snapshot.progress[p.index].orEmpty(), snapshot.watched[p.index].orEmpty(), titles)
        }.sortedByDescending { it.atMs }
        UiState(
            signedIn = signedIn,
            email = prefs.session?.email.orEmpty(),
            server = local.server,
            serverUrl = prefs.serverConfig?.baseUrl.orEmpty(),
            nuvioInstalled = graph.isNuvioInstalled,
            loading = local.loading,
            error = local.error,
            snapshot = snapshot,
            titles = titles,
            viewings = viewings,
            now = snapshot.profiles.map { p -> profileNow(p, snapshot, local.live[p.index], viewings, local.clock) },
            settings = settings,
            lastPollAt = local.lastPollAt,
            lastPollError = local.lastPollError,
            clock = local.clock,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        detectServer()
        if (prefs.signedIn.value) refresh()
        // Pick up what the background monitor has seen, and keep "x minutes ago" labels current.
        viewModelScope.launch {
            while (true) {
                local.update {
                    it.copy(
                        live = prefs.liveEntries,
                        lastPollAt = prefs.lastPollAt,
                        lastPollError = prefs.lastPollError,
                        clock = System.currentTimeMillis(),
                    )
                }
                delay(15_000)
            }
        }
    }

    fun detectServer() = viewModelScope.launch {
        local.update { it.copy(server = ServerStatus.DETECTING, error = null) }
        val config = graph.autoDetectServer()
        local.update { it.copy(server = if (config != null) ServerStatus.READY else ServerStatus.NOT_FOUND) }
    }

    fun useCustomServer(url: String, key: String) = viewModelScope.launch {
        local.update { it.copy(server = ServerStatus.DETECTING, error = null) }
        val config = graph.useCustomServer(url, key)
        local.update {
            if (config != null) it.copy(server = ServerStatus.READY)
            else it.copy(server = ServerStatus.NOT_FOUND, error = "That server didn't accept the key. Check both and try again.")
        }
    }

    fun signIn(email: String, password: String) = viewModelScope.launch {
        val client = graph.client() ?: return@launch
        local.update { it.copy(loading = true, error = null) }
        runCatching { client.signIn(email, password) }
            .onSuccess { refresh() }
            .onFailure { e -> local.update { it.copy(loading = false, error = e.message) } }
    }

    fun refresh() = viewModelScope.launch {
        local.update { it.copy(loading = true, error = null) }
        runCatching { graph.repository.refresh() }
            .onSuccess { local.update { it.copy(loading = false) } }
            .onFailure { e -> local.update { it.copy(loading = false, error = e.message ?: "Couldn't load your Nuvio data") } }
    }

    fun setMonitoring(enabled: Boolean) {
        prefs.updateSettings { it.copy(enabled = enabled) }
        val context = getApplication<Application>()
        if (enabled) MonitorService.start(context) else MonitorService.stop(context)
    }

    fun setInterval(seconds: Int) = prefs.updateSettings { it.copy(intervalSeconds = seconds) }

    fun setNotifyFinished(on: Boolean) = prefs.updateSettings { it.copy(notifyFinished = on) }

    fun setProfileAlerts(profileIndex: Int, on: Boolean) = prefs.updateSettings {
        it.copy(mutedProfiles = if (on) it.mutedProfiles - profileIndex else it.mutedProfiles + profileIndex)
    }

    fun checkNow() = viewModelScope.launch {
        Monitor.poll(getApplication<Application>(), force = true)
        local.update { it.copy(live = prefs.liveEntries, lastPollAt = prefs.lastPollAt, lastPollError = prefs.lastPollError) }
    }

    fun signOut() {
        MonitorService.stop(getApplication<Application>())
        Monitor.reset()
        graph.signOut()
        local.update { Local(server = it.server) }
    }

    private fun profileNow(p: Profile, snapshot: Snapshot, live: ProgressEntry?, viewings: List<Viewing>, now: Long): ProfileNow {
        val fromSnapshot = snapshot.progress[p.index].orEmpty().maxByOrNull { it.lastWatchedMs }
        val latest = listOfNotNull(fromSnapshot, live).maxByOrNull { it.lastWatchedMs }
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val today = viewings.filter { it.profileIndex == p.index && it.atMs >= dayStart }.sumOf { it.watchedMs }
        return ProfileNow(p, latest, today)
    }
}
