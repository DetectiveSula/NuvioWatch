package app.nuviowatch.desktop

import app.nuviowatch.core.Cinemeta
import app.nuviowatch.core.EpisodeInfo
import app.nuviowatch.core.FamilyDb
import app.nuviowatch.core.Fun
import app.nuviowatch.core.LiveChange
import app.nuviowatch.core.LiveChannel
import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.LiveRelay
import app.nuviowatch.core.LiveRun
import app.nuviowatch.core.LiveRuns
import app.nuviowatch.core.LiveTracker
import app.nuviowatch.core.NuvioClient
import app.nuviowatch.core.NuvioException
import app.nuviowatch.core.OFFICIAL_SERVER_URL
import app.nuviowatch.core.PlayState
import app.nuviowatch.core.Profile
import app.nuviowatch.core.Reaction
import app.nuviowatch.core.ServerCandidates
import app.nuviowatch.core.ServerConfig
import app.nuviowatch.core.ServerSetup
import app.nuviowatch.core.Social
import app.nuviowatch.core.StatsCalculator
import app.nuviowatch.core.TitleInfo
import app.nuviowatch.core.Viewing
import app.nuviowatch.core.isSeries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A Mac notification to show. */
data class Alert(val title: String, val text: String)

/** A reaction to float over Nuvio Desktop. */
data class Bubble(val id: String, val emoji: String, val from: String)

/** The Mac app's state and background work: Nuvio sync, the family's live channel, alerts and summaries. */
object Model {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    val store = Store()
    private val cinemeta = Cinemeta(http)

    val snapshot = MutableStateFlow(store.loadSnapshot())
    val titles = MutableStateFlow(store.loadTitles())
    val episodes = MutableStateFlow<Map<String, List<EpisodeInfo>>>(emptyMap())
    val viewings = MutableStateFlow<List<Viewing>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val clock = MutableStateFlow(System.currentTimeMillis())
    val statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val bubbles = MutableStateFlow<List<Bubble>>(emptyList())

    private val _alerts = MutableSharedFlow<Alert>(extraBufferCapacity = 20)
    val alerts: SharedFlow<Alert> = _alerts

    private val tracker = LiveTracker()
    private val runs = LiveRuns()
    private val liveLock = Mutex()
    private val seen = LinkedHashSet<String>()
    private var started = false

    val signedIn: Boolean get() = store.saved.value.session != null

    fun setStatus(part: String, text: String) = statuses.update { it + (part to "$text · ${java.time.LocalTime.now().withNano(0)}") }

    /** Shows a Mac notification. */
    fun notify(title: String, text: String) = _alerts.tryEmit(Alert(title, text))

    // ------------------------------------------------------------ Sign in and Nuvio data

    private var cachedClient: Pair<ServerConfig, NuvioClient>? = null

    private suspend fun server(): ServerConfig? {
        store.saved.value.server?.let { return it }
        val urls = BuildInfo.SERVER_URLS.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(OFFICIAL_SERVER_URL) }
        val found = ServerSetup(http).firstWorking(ServerCandidates(listOfNotNull(BuildInfo.SERVER_KEY.takeIf { it.isNotBlank() }), urls), urls)
        if (found != null) store.update { it.copy(server = found) }
        return found
    }

    private suspend fun client(): NuvioClient? {
        val config = server() ?: return null
        cachedClient?.let { (c, client) -> if (c == config) return client }
        return NuvioClient(config, store, http).also { cachedClient = config to it }
    }

    suspend fun signIn(email: String, password: String): String? {
        val client = client() ?: return "Couldn't reach Nuvio's server. Check your internet connection."
        return try {
            client.signIn(email.trim(), password)
            start()
            null
        } catch (e: NuvioException) {
            if (e.isAuthError || e.status == 400) "Wrong email or password." else (e.message ?: "Sign-in failed")
        } catch (e: Exception) {
            e.message ?: "Sign-in failed"
        }
    }

    fun signOut() {
        store.update { Saved(deviceId = it.deviceId, deviceName = it.deviceName, server = it.server) }
        snapshot.value = Snapshot()
        viewings.value = emptyList()
        store.saveSnapshot(Snapshot())
    }

    fun refresh() = scope.launch {
        val client = client() ?: return@launch
        if (!signedIn) return@launch
        loading.value = true
        try {
            val profiles = client.profiles()
            val (progress, watched) = coroutineScope {
                val p = profiles.map { async { it.index to client.progress(it.index) } }
                val w = profiles.map { async { it.index to client.watched(it.index) } }
                p.awaitAll().toMap() to w.awaitAll().toMap()
            }
            val snap = Snapshot(profiles, progress, watched, System.currentTimeMillis())
            snapshot.value = snap
            store.saveSnapshot(snap)
            ensureTitles(snap)
            rebuildViewings()
            loadEpisodes()
            error.value = null
            setStatus("sync", "Up to date")
        } catch (e: NuvioException) {
            if (e.isAuthError) signOut()
            error.value = e.message
            setStatus("sync", "Failed: ${e.message}")
        } catch (e: Exception) {
            error.value = e.message
            setStatus("sync", "Failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            loading.value = false
        }
    }

    private suspend fun ensureTitles(snap: Snapshot) {
        val requests = mutableMapOf<String, Boolean>()
        snap.progress.values.flatten().forEach { requests[it.contentId] = isSeries(it.contentType, it.season, it.videoId) }
        snap.watched.values.flatten().forEach { requests.putIfAbsent(it.contentId, isSeries(it.contentType, it.season)) }
        val missing = requests.filterKeys { it !in titles.value && Cinemeta.canLookUp(it) }
        if (missing.isEmpty()) return
        val found = cinemeta.lookupAll(missing).mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        titles.update { it + found }
        store.saveTitles(titles.value)
    }

    private suspend fun loadEpisodes() {
        val since = System.currentTimeMillis() - 90 * 86_400_000L
        val ids = viewings.value.filter { it.series && it.atMs > since }.map { it.contentId }.distinct().filter { it !in episodes.value }
        for (id in ids.take(30)) {
            runCatching { cinemeta.episodes(id) }.getOrNull()?.let { list -> episodes.update { it + (id to list) } }
        }
    }

    fun rebuildViewings() {
        val snap = snapshot.value
        viewings.value = snap.profiles.flatMap { p ->
            StatsCalculator.viewings(p.index, snap.progress[p.index].orEmpty(), snap.watched[p.index].orEmpty(), titles.value)
        }
    }

    fun profileName(index: Int) = snapshot.value.profiles.firstOrNull { it.index == index }?.name ?: "Profile $index"

    fun myName(): String = store.saved.value.profileIndex?.let { profileName(it) } ?: store.saved.value.deviceName

    // ------------------------------------------------------------ Streak days

    fun activeDays(): Map<Int, Set<LocalDate>> = store.saved.value.activeDays.mapValues { (_, d) -> d.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet() }
    fun freezeDays(): Map<Int, Set<LocalDate>> = store.saved.value.freezeDays.mapValues { (_, d) -> d.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet() }

    private fun markActive(profile: Int, at: Long) {
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (store.saved.value.activeDays[profile]?.contains(day) == true) return
        store.update { s -> s.copy(activeDays = s.activeDays + (profile to (s.activeDays[profile].orEmpty() + day).takeLast(400))) }
    }

    // ------------------------------------------------------------ Live

    private fun relay(): LiveRelay? = store.saved.value.session?.userId?.takeIf { it.isNotBlank() }?.let { LiveRelay(LiveChannel(it), http) }

    fun db(): FamilyDb? = store.saved.value.session?.userId?.takeIf { it.isNotBlank() }?.let { FamilyDb(BuildInfo.FAMILY_DB_URL, BuildInfo.FAMILY_DB_KEY, it, http) }

    /** Starts the background work once signed in. Safe to call more than once. */
    fun start() {
        if (!signedIn || started) return
        started = true
        tracker.restore(store.saved.value.liveDevices)
        runs.restore(store.saved.value.liveRuns)
        rebuildViewings()
        refresh()
        scope.launch { listenLoop() }
        scope.launch {
            while (isActive) {
                delay(30_000)
                clock.value = System.currentTimeMillis()
                expire()
            }
        }
        scope.launch {
            while (isActive) {
                delay(10 * 60_000L)
                refresh()
            }
        }
        scope.launch {
            while (isActive) {
                delay(5_000)
                clock.value = System.currentTimeMillis()
            }
        }
    }

    private suspend fun listenLoop() {
        var backoff = 2_000L
        while (scope.isActive && signedIn) {
            val relay = relay() ?: return
            runCatching {
                relay.listen(store.saved.value.liveSince ?: "10m", onOpen = { setStatus("stream", "Connected"); backoff = 2_000L }) { id, text ->
                    onMessage(id, text)
                }
            }.onFailure { setStatus("stream", "Reconnecting: ${it.message ?: it.javaClass.simpleName}") }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun onMessage(id: String, text: String) {
        synchronized(seen) {
            if (!seen.add(id)) return
            if (seen.size > 500) seen.remove(seen.first())
        }
        store.update { it.copy(liveSince = id) }
        when (val message = Social.parse(text)) {
            is LiveEvent -> onLive(message)
            is Reaction -> onReaction(message)
            else -> Unit
        }
    }

    val liveDevices = MutableStateFlow(store.saved.value.liveDevices)

    private suspend fun onLive(event: LiveEvent) = liveLock.withLock {
        setStatus("received", "${event.deviceName}: ${event.state.name.lowercase()} · ${event.title}")
        // Same show still playing (the next episode in a binge): no new alert.
        val continuing = runs.states[event.deviceId]?.title == event.title && event.state != PlayState.STOPPED
        val change = tracker.apply(event)
        val finished = runs.apply(event)
        if (event.state == PlayState.PLAYING && event.profileIndex != null && !event.deviceId.startsWith("test-")) markActive(event.profileIndex!!, event.at)
        store.update { it.copy(liveDevices = tracker.states, liveRuns = runs.states) }
        liveDevices.value = tracker.states
        val fresh = System.currentTimeMillis() - event.at < 3 * 60_000L
        if (!fresh) return@withLock
        if (change == LiveChange.STARTED && !continuing) started(event)
        if (finished != null) summarize(finished)
    }

    private suspend fun expire() = liveLock.withLock {
        val now = System.currentTimeMillis()
        val stale = tracker.expire(now)
        val finished = runs.expire(now)
        if (stale.isNotEmpty() || finished.isNotEmpty()) {
            store.update { it.copy(liveDevices = tracker.states, liveRuns = runs.states) }
            liveDevices.value = tracker.states
        }
        finished.forEach { summarize(it) }
    }

    private fun allowed(deviceId: String, profile: Profile?): Boolean {
        val s = store.saved.value
        if (!s.alertsOn) return false
        if (deviceId == s.deviceId && !s.alertOwnDevice) return false
        if (profile != null && profile.index in s.mutedProfiles) return false
        return true
    }

    private fun started(event: LiveEvent) {
        val profile = profileFor(event)
        if (!allowed(event.deviceId, profile)) return
        val who = profile?.name ?: "Someone on ${event.deviceName}"
        val title = titleFor(event)?.name ?: event.title
        _alerts.tryEmit(Alert("$who started watching", listOfNotNull(title, event.subtitle, "on ${event.deviceName}").joinToString(" · ")))
        setStatus("alert", "Shown: $who started watching")
    }

    private fun summarize(run: LiveRun) {
        if (run.playedMs < LiveRuns.MIN_SUMMARY_MS) return
        val probe = LiveEvent(run.deviceId, run.deviceName, run.profileIndex, run.title, state = PlayState.STOPPED, at = run.lastAt)
        val profile = profileFor(probe)
        if (!allowed(run.deviceId, profile)) return
        val who = profile?.name ?: "Someone on ${run.deviceName}"
        val (heading, text) = LiveRuns.summarize(who, run)
        _alerts.tryEmit(Alert(heading, "$text · on ${run.deviceName}"))
        setStatus("summary", "Shown: $heading")
    }

    // ------------------------------------------------------------ Reactions

    private fun onReaction(r: Reaction) {
        val s = store.saved.value
        if (s.reactions.any { it.id == r.id }) return
        store.update { it.copy(reactions = (it.reactions + r).takeLast(40)) }
        val forMe = r.fromDeviceId != s.deviceId && (r.toDeviceId == s.deviceId || (r.toProfile != null && r.toProfile == s.profileIndex))
        if (!forMe || System.currentTimeMillis() - r.at > 10 * 60_000L) return
        if (r.toDeviceId == s.deviceId) {
            showBubble(r.emoji, r.fromName)
        } else {
            _alerts.tryEmit(Alert("${r.fromName} reacted ${r.emoji}", "to ${r.title}"))
        }
    }

    fun showBubble(emoji: String, from: String) {
        val b = Bubble(UUID.randomUUID().toString(), emoji, from)
        bubbles.update { it + b }
        scope.launch {
            delay(3_800)
            bubbles.update { list -> list.filterNot { it.id == b.id } }
        }
    }

    fun react(to: LiveEvent, emoji: String) = scope.launch {
        val s = store.saved.value
        val r = Reaction(
            id = UUID.randomUUID().toString(),
            fromDeviceId = s.deviceId,
            fromName = myName(),
            toProfile = profileFor(to)?.index,
            toDeviceId = to.deviceId,
            emoji = emoji,
            title = titleFor(to)?.name ?: to.title,
            at = System.currentTimeMillis(),
        )
        store.update { it.copy(reactions = (it.reactions + r).takeLast(40)) }
        val text = Social.encode(r)
        runCatching { relay()?.publishText(text) }
        runCatching { db()?.add(r.id, "reaction", text, r.at) }
    }

    /** Sends this Mac's own playback (Nuvio Desktop) to the family. */
    fun publish(event: LiveEvent) = scope.launch {
        if (!store.saved.value.reportPlayback) {
            setStatus("send", "Not sending: reporting is off")
            return@launch
        }
        val relay = relay() ?: return@launch
        val text = Social.encode(event)
        runCatching { relay.publishText(text) }
            .onSuccess { setStatus("send", "Sent \"${event.state.name.lowercase()}\" · ${event.title}") }
            .onFailure { setStatus("send", "Send failed: ${it.message}") }
        runCatching { db()?.add("${event.deviceId}-${event.at}", "playback", text, event.at) }
    }

    // ------------------------------------------------------------ Matching live titles to profiles

    private fun normalize(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun titleIds(title: String): Set<String> {
        val wanted = normalize(title)
        if (wanted.isEmpty()) return emptySet()
        return titles.value.filterValues { normalize(it.name) == wanted || wanted.startsWith(normalize(it.name) + " ") }.keys
    }

    fun titleFor(e: LiveEvent): TitleInfo? = titleIds(e.title).firstOrNull()?.let { titles.value[it] }

    fun titleIdFor(e: LiveEvent): String? = titleIds(e.title).firstOrNull()

    fun profileFor(e: LiveEvent): Profile? {
        val snap = snapshot.value
        e.profileIndex?.let { i -> snap.profiles.firstOrNull { it.index == i }?.let { return it } }
        val ids = titleIds(e.title)
        if (ids.isEmpty()) return null
        return snap.profiles.mapNotNull { p ->
            val latest = snap.progress[p.index].orEmpty().filter { it.contentId in ids }.maxOfOrNull { it.lastWatchedMs }
                ?: snap.watched[p.index].orEmpty().filter { it.contentId in ids }.maxOfOrNull { it.watchedAtMs }
            latest?.let { p to it }
        }.maxByOrNull { it.second }?.first
    }

    /** Devices playing or paused right now, newest first. */
    fun liveNow(now: Long = clock.value): List<LiveEvent> =
        liveDevices.value.values.filter { it.state != PlayState.STOPPED && now - it.at < 5 * 60_000L }.sortedByDescending { it.at }

    fun streak(index: Int): Int = Fun.streaks(
        viewings.value.filter { it.profileIndex == index }, System.currentTimeMillis(),
        extraDays = activeDays()[index].orEmpty(), freezes = freezeDays()[index].orEmpty(),
    ).first
}
