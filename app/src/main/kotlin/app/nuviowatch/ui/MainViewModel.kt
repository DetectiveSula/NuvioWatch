package app.nuviowatch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nuviowatch.core.EpisodeInfo
import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.Outcome
import app.nuviowatch.core.Prediction
import app.nuviowatch.core.PredictionKind
import app.nuviowatch.core.Reaction
import app.nuviowatch.core.Social
import app.nuviowatch.core.Vote
import app.nuviowatch.core.PlayState
import app.nuviowatch.core.Profile
import app.nuviowatch.core.ProgressEntry
import app.nuviowatch.core.Stats
import app.nuviowatch.core.StatsCalculator
import app.nuviowatch.core.TitleInfo
import app.nuviowatch.core.Viewing
import app.nuviowatch.data.MonitorSettings
import app.nuviowatch.data.Snapshot
import app.nuviowatch.graph
import app.nuviowatch.live.Inbox
import app.nuviowatch.live.LiveHub
import app.nuviowatch.monitor.Notifier
import app.nuviowatch.live.LiveReceiver
import app.nuviowatch.live.NowPlayingListener
import app.nuviowatch.recap.Recaps
import app.nuviowatch.widget.NowWidget
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

/** A device where Nuvio is playing or paused right now. */
data class LiveNow(val event: LiveEvent, val profile: Profile?, val info: TitleInfo?)

data class SocialState(
    val reactions: List<Reaction> = emptyList(),
    val predictions: List<Prediction> = emptyList(),
    val votes: List<Vote> = emptyList(),
    val myDeviceId: String = "",
    val message: String? = null,
)

data class RecapSettings(val daily: Boolean = true, val weekly: Boolean = true, val hour: Int = 21, val message: String? = null)

data class DeviceSettings(
    val name: String = "",
    val profileIndex: Int? = null,
    val reportPlayback: Boolean = true,
    val alertOwnDevice: Boolean = false,
    val hasAccess: Boolean = false,
    val adbCommand: String = "",
)

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
    /** Devices currently playing or paused in Nuvio, newest first. */
    val liveNow: List<LiveNow> = emptyList(),
    val device: DeviceSettings = DeviceSettings(),
    /** Troubleshooting lines: label to "text · time". */
    val statuses: List<Pair<String, String>> = emptyList(),
    val testMessage: String? = null,
    val recaps: RecapSettings = RecapSettings(),
    val episodes: Map<String, List<EpisodeInfo>> = emptyMap(),
    val social: SocialState = SocialState(),
    /** Every device that has reported, newest first, including idle ones. */
    val devices: List<LiveEvent> = emptyList(),
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
    val social: SocialState = SocialState(),
    val episodes: Map<String, List<EpisodeInfo>> = emptyMap(),
    val recaps: RecapSettings = RecapSettings(),
    val statuses: List<Pair<String, String>> = emptyList(),
    val testMessage: String? = null,
    val liveDevices: Map<String, LiveEvent> = emptyMap(),
    val device: DeviceSettings = DeviceSettings(),
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
            liveNow = local.liveDevices.values
                .filter { it.state != PlayState.STOPPED && local.clock - it.at < LIVE_FRESH_MS }
                .sortedByDescending { it.at }
                .map { LiveNow(it, LiveHub.profileFor(it, snapshot, titles), LiveHub.titleFor(it, titles)) },
            device = local.device,
            statuses = local.statuses,
            testMessage = local.testMessage,
            recaps = local.recaps,
            episodes = local.episodes,
            social = local.social,
            devices = local.liveDevices.values.sortedByDescending { it.at },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        // Announce predictions as they settle.
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                announceSettledPredictions()
            }
        }
        viewModelScope.launch { graph.episodes.episodes.collect { e -> local.update { it.copy(episodes = e) } } }
        detectServer()
        if (prefs.signedIn.value) refresh()
        // Pick up what the background monitor has seen, and keep "x minutes ago" labels current.
        viewModelScope.launch {
            while (true) {
                local.update {
                    it.copy(
                        liveDevices = prefs.liveDevices,
                        device = deviceSettings(),
                        statuses = statuses(),
                        social = it.social.copy(reactions = prefs.reactions, predictions = prefs.predictions, votes = prefs.votes, myDeviceId = prefs.deviceId),
                        recaps = it.recaps.copy(daily = prefs.dailyRecap, weekly = prefs.weeklyRecap, hour = prefs.recapHour),
                        live = prefs.liveEntries,
                        lastPollAt = prefs.lastPollAt,
                        lastPollError = prefs.lastPollError,
                        clock = System.currentTimeMillis(),
                    )
                }
                delay(5_000)
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
            .onSuccess { snapshot ->
                local.update { it.copy(loading = false) }
                NowWidget.update(getApplication())
                // Episode lists for shows watched in the last two months, for "Up next".
                val since = System.currentTimeMillis() - 60 * 86_400_000L
                val series = snapshot.progress.values.flatten().filter { it.season != null && it.lastWatchedMs > since }.map { it.contentId } +
                    snapshot.watched.values.flatten().filter { it.season != null && it.watchedAtMs > since }.map { it.contentId }
                launch { runCatching { graph.episodes.ensure(series.toSet()) } }
            }
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

    fun setDeviceName(name: String) {
        prefs.deviceName = name
        local.update { it.copy(device = deviceSettings()) }
    }

    fun setDeviceProfile(index: Int?) {
        prefs.deviceProfile = index
        local.update { it.copy(device = deviceSettings()) }
    }

    fun setRecaps(daily: Boolean? = null, weekly: Boolean? = null, hour: Int? = null) {
        daily?.let { prefs.dailyRecap = it }
        weekly?.let { prefs.weeklyRecap = it }
        hour?.let { prefs.recapHour = it }
        Recaps.schedule(getApplication())
        local.update { it.copy(recaps = it.recaps.copy(daily = prefs.dailyRecap, weekly = prefs.weeklyRecap, hour = prefs.recapHour)) }
    }

    /** Sends today's and this week's recap right away, to preview them. */
    fun sendRecapNow() = viewModelScope.launch {
        local.update { it.copy(recaps = it.recaps.copy(message = "Preparing…")) }
        val sent = Recaps.send(getApplication(), forceWeekly = true)
        local.update { it.copy(recaps = it.recaps.copy(message = if (sent) "Sent. Check your notifications." else "Nothing watched yet to recap.")) }
    }

    fun react(live: LiveNow, emoji: String) = viewModelScope.launch {
        val ok = Inbox.react(getApplication(), live.event, live.profile?.index, emoji)
        val who = live.profile?.name ?: live.event.deviceName
        local.update { it.copy(social = it.social.copy(reactions = prefs.reactions, message = if (ok) "Sent $emoji to $who" else "Couldn't send, check your connection")) }
    }

    fun predict(profileIndex: Int, kind: PredictionKind, contentId: String?, season: Int?, hours: Int?, deadlineMs: Long) = viewModelScope.launch {
        val prediction = Prediction(
            id = java.util.UUID.randomUUID().toString(),
            byName = prefs.myName(state.value.snapshot.profiles),
            profileIndex = profileIndex,
            kind = kind,
            contentId = contentId,
            season = season,
            hours = hours,
            deadlineMs = deadlineMs,
            createdAt = System.currentTimeMillis(),
        )
        Inbox.predict(getApplication(), prediction)
        local.update { it.copy(social = it.social.copy(predictions = prefs.predictions)) }
    }

    fun vote(predictionId: String, yes: Boolean) = viewModelScope.launch {
        Inbox.vote(getApplication(), predictionId, yes)
        local.update { it.copy(social = it.social.copy(votes = prefs.votes)) }
    }

    private fun announceSettledPredictions() {
        val current = state.value
        if (current.viewings.isEmpty()) return
        val announced = prefs.announcedPredictions
        val newlyAnnounced = mutableSetOf<String>()
        for (p in prefs.predictions) {
            if (p.id in announced) continue
            val outcome = Social.resolve(p, current.viewings, current.episodes, System.currentTimeMillis())
            if (outcome == Outcome.PENDING) continue
            val tally = Social.tally(p.id, prefs.votes)
            val title = p.contentId?.let { current.titles[it]?.name }
            val question = Social.question(p, current.profileName(p.profileIndex), title, deadlineLabel(p.deadlineMs))
            Notifier.showPredictionResult(getApplication(), question, outcome == Outcome.YES, if (outcome == Outcome.YES) tally.yes else tally.no)
            newlyAnnounced += p.id
        }
        if (newlyAnnounced.isNotEmpty()) prefs.announcedPredictions = announced + newlyAnnounced
    }

    fun setAlertOwnDevice(on: Boolean) {
        prefs.alertOwnDevice = on
        local.update { it.copy(device = deviceSettings()) }
    }

    fun setReportPlayback(on: Boolean) {
        prefs.reportPlayback = on
        local.update { it.copy(device = deviceSettings()) }
    }

    private fun statuses(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        fun line(part: String, fallback: String): String =
            prefs.status(part)?.let { (text, at) -> "$text · ${timeAgo(at, now)}" } ?: fallback
        val access = NowPlayingListener.hasAccess(getApplication<Application>())
        return listOf(
            "Live connection" to if (prefs.settings.value.enabled) line("stream", "Not started yet") else "Off (turn on Watching alerts)",
            "Last live report received" to line("received", "None yet"),
            "Last live alert" to line("alert", "None yet"),
            "Notification access here" to if (access) "Allowed" else "Not allowed",
            "Media reader here" to if (access) line("listener", "Not connected yet") else "Needs notification access",
            "Nuvio player here" to line("player", "Not seen yet"),
            "Last report sent from here" to line("send", "None yet"),
        )
    }

    /** Sends a pretend "started" and "stopped" so phones can check they hear each other. */
    fun sendTest() = viewModelScope.launch {
        val relay = LiveHub.relay(getApplication())
        if (relay == null) {
            local.update { it.copy(testMessage = "Can't send: account id missing. Sign out and in again.") }
            return@launch
        }
        val base = LiveEvent(
            deviceId = "test-" + prefs.deviceId,
            deviceName = prefs.deviceName,
            title = "Test alert",
            subtitle = "If you see this, phones can hear each other",
            state = PlayState.PLAYING,
            at = System.currentTimeMillis(),
        )
        runCatching {
            relay.publish(base)
            delay(8_000)
            relay.publish(base.copy(state = PlayState.STOPPED, at = System.currentTimeMillis()))
        }.onSuccess { local.update { it.copy(testMessage = "Test sent. Other phones with alerts on should buzz now.") } }
            .onFailure { e -> local.update { it.copy(testMessage = "Test failed: ${e.message}") } }
    }

    private fun deviceSettings(): DeviceSettings {
        val app = getApplication<Application>()
        return DeviceSettings(
            name = prefs.deviceName,
            profileIndex = prefs.deviceProfile,
            reportPlayback = prefs.reportPlayback,
            alertOwnDevice = prefs.alertOwnDevice,
            hasAccess = NowPlayingListener.hasAccess(app),
            adbCommand = NowPlayingListener.adbCommand(app),
        )
    }

    fun checkNow() = viewModelScope.launch {
        Monitor.poll(getApplication<Application>(), force = true)
        local.update { it.copy(live = prefs.liveEntries, lastPollAt = prefs.lastPollAt, lastPollError = prefs.lastPollError) }
    }

    fun signOut() {
        MonitorService.stop(getApplication<Application>())
        Monitor.reset()
        graph.signOut()
        LiveReceiver.reset()
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

/** A live report older than this is ignored (the device probably went off). */
private const val LIVE_FRESH_MS = 5 * 60_000L

/** "today 23:59", "Sunday" or "8 Nov", for prediction questions. */
fun deadlineLabel(ms: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when {
        date == today -> "tonight"
        date.isBefore(today.plusDays(7)) -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
    }
}
