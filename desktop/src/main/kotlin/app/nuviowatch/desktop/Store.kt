package app.nuviowatch.desktop

import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.LiveRun
import app.nuviowatch.core.NuvioClient
import app.nuviowatch.core.ProgressEntry
import app.nuviowatch.core.Profile
import app.nuviowatch.core.Reaction
import app.nuviowatch.core.ServerConfig
import app.nuviowatch.core.Session
import app.nuviowatch.core.SessionStore
import app.nuviowatch.core.TitleInfo
import app.nuviowatch.core.WatchedItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

/** Everything the Mac app remembers between launches. */
@Serializable
data class Saved(
    val session: Session? = null,
    val server: ServerConfig? = null,
    val deviceId: String = "mac-" + UUID.randomUUID().toString(),
    val deviceName: String = "Mac",
    /** Who usually watches on this Mac. */
    val profileIndex: Int? = null,
    val alertsOn: Boolean = true,
    val alertOwnDevice: Boolean = false,
    val reportPlayback: Boolean = true,
    val startAtLogin: Boolean = true,
    val mutedProfiles: Set<Int> = emptySet(),
    val liveSince: String? = null,
    val liveDevices: Map<String, LiveEvent> = emptyMap(),
    val liveRuns: Map<String, LiveRun> = emptyMap(),
    val reactions: List<Reaction> = emptyList(),
    /** Days each profile watched something (yyyy-mm-dd), for streaks. */
    val activeDays: Map<Int, List<String>> = emptyMap(),
    val freezeDays: Map<Int, List<String>> = emptyMap(),
    val announced: Set<String> = emptySet(),
)

/** Profiles and history pulled from Nuvio, kept so the app opens instantly. */
@Serializable
data class Snapshot(
    val profiles: List<Profile> = emptyList(),
    val progress: Map<Int, List<ProgressEntry>> = emptyMap(),
    val watched: Map<Int, List<WatchedItem>> = emptyMap(),
    val fetchedAt: Long = 0,
)

/** Saves to ~/Library/Application Support/Nuvio Watch. */
class Store : SessionStore {
    val dir: File = File(System.getProperty("user.home"), "Library/Application Support/Nuvio Watch").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")
    private val snapshotFile = File(dir, "snapshot.json")
    private val titlesFile = File(dir, "titles.json")

    private val _saved = MutableStateFlow(read<Saved>(stateFile) ?: Saved())
    val saved: StateFlow<Saved> = _saved

    fun update(change: (Saved) -> Saved) {
        synchronized(this) {
            _saved.update(change)
            write(stateFile, NuvioClient.json.encodeToString(_saved.value))
        }
    }

    override var session: Session?
        get() = _saved.value.session
        set(value) = update { it.copy(session = value) }

    fun loadSnapshot(): Snapshot = read<Snapshot>(snapshotFile) ?: Snapshot()
    fun saveSnapshot(s: Snapshot) = write(snapshotFile, NuvioClient.json.encodeToString(s))
    fun loadTitles(): Map<String, TitleInfo> = read<Map<String, TitleInfo>>(titlesFile) ?: emptyMap()
    fun saveTitles(t: Map<String, TitleInfo>) = write(titlesFile, NuvioClient.json.encodeToString(t))

    private inline fun <reified T> read(file: File): T? =
        runCatching { NuvioClient.json.decodeFromString<T>(file.readText()) }.getOrNull()

    private fun write(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }
}
