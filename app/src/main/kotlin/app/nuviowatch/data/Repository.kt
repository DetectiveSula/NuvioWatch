package app.nuviowatch.data

import android.content.Context
import app.nuviowatch.core.Cinemeta
import app.nuviowatch.core.NuvioClient
import app.nuviowatch.core.Profile
import app.nuviowatch.core.ProgressEntry
import app.nuviowatch.core.TitleInfo
import app.nuviowatch.core.WatchedItem
import app.nuviowatch.core.isSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** Everything read from the Nuvio account at the last refresh. */
@Serializable
data class Snapshot(
    val profiles: List<Profile> = emptyList(),
    val progress: Map<Int, List<ProgressEntry>> = emptyMap(),
    val watched: Map<Int, List<WatchedItem>> = emptyMap(),
    val fetchedAt: Long = 0,
)

/** Loads the account's profiles and history, and keeps a copy on the phone for quick start-up. */
class Repository(
    context: Context,
    private val clientProvider: () -> NuvioClient?,
    private val titles: TitleCache,
) {
    private val file = File(context.filesDir, "snapshot.json")
    private val lock = Mutex()
    private val _snapshot = MutableStateFlow(readFile())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    suspend fun refresh(): Snapshot = lock.withLock {
        val client = clientProvider() ?: error("Sign in first")
        val profiles = client.profiles()
        val (progress, watched) = coroutineScope {
            val p = profiles.map { async { it.index to client.progress(it.index) } }
            val w = profiles.map { async { it.index to client.watched(it.index) } }
            p.awaitAll().toMap() to w.awaitAll().toMap()
        }
        val snapshot = Snapshot(profiles, progress, watched, System.currentTimeMillis())
        titles.ensure(snapshot.titleRequests())
        _snapshot.value = snapshot
        withContext(Dispatchers.IO) { runCatching { file.writeText(NuvioClient.json.encodeToString(snapshot)) } }
        snapshot
    }

    fun clear() {
        _snapshot.value = Snapshot()
        file.delete()
    }

    private fun readFile(): Snapshot =
        runCatching { NuvioClient.json.decodeFromString<Snapshot>(file.readText()) }.getOrDefault(Snapshot())
}

/** Every title in the snapshot, with whether it is a series. */
fun Snapshot.titleRequests(): Map<String, Boolean> {
    val requests = mutableMapOf<String, Boolean>()
    progress.values.flatten().forEach { requests[it.contentId] = isSeries(it.contentType, it.season, it.videoId) }
    watched.values.flatten().forEach { requests.putIfAbsent(it.contentId, isSeries(it.contentType, it.season)) }
    return requests
}

/** Title details from Cinemeta, saved on the phone so each title is only looked up once. */
class TitleCache(context: Context, private val cinemeta: Cinemeta) {
    @Serializable
    private data class Stored(val found: Map<String, TitleInfo> = emptyMap(), val missing: Set<String> = emptySet())

    private val file = File(context.filesDir, "titles.json")
    private val lock = Mutex()
    private var stored = runCatching { NuvioClient.json.decodeFromString<Stored>(file.readText()) }.getOrDefault(Stored())
    private val _titles = MutableStateFlow(stored.found)
    val titles: StateFlow<Map<String, TitleInfo>> = _titles.asStateFlow()

    operator fun get(id: String): TitleInfo? = _titles.value[id]

    suspend fun ensure(requests: Map<String, Boolean>) {
        lock.withLock {
            val needed = requests.filterKeys { it !in stored.found && it !in stored.missing && Cinemeta.canLookUp(it) }
            if (needed.isEmpty()) return
            val results = cinemeta.lookupAll(needed)
            val found = results.mapNotNull { (id, info) -> info?.let { id to it } }.toMap()
            stored = Stored(stored.found + found, stored.missing + results.filterValues { it == null }.keys)
            _titles.value = stored.found
            withContext(Dispatchers.IO) { runCatching { file.writeText(NuvioClient.json.encodeToString(stored)) } }
        }
    }

    suspend fun ensure(id: String, series: Boolean) {
        ensure(mapOf(id to series))
    }
}
