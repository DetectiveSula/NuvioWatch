package app.nuviowatch.data

import android.content.Context
import app.nuviowatch.core.Cinemeta
import app.nuviowatch.core.EpisodeInfo
import app.nuviowatch.core.NuvioClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** Episode lists for the series people are following, refreshed daily for new air dates. */
class EpisodeCache(context: Context, private val cinemeta: Cinemeta) {
    @Serializable
    private data class Entry(val fetchedAt: Long, val episodes: List<EpisodeInfo>)

    private val file = File(context.filesDir, "episodes.json")
    private val lock = Mutex()
    private var stored: Map<String, Entry> =
        runCatching { NuvioClient.json.decodeFromString<Map<String, Entry>>(file.readText()) }.getOrDefault(emptyMap())
    private val _episodes = MutableStateFlow(stored.mapValues { it.value.episodes })
    val episodes: StateFlow<Map<String, List<EpisodeInfo>>> = _episodes.asStateFlow()

    suspend fun ensure(ids: Collection<String>) = lock.withLock {
        val now = System.currentTimeMillis()
        val stale = ids.filter { id -> Cinemeta.canLookUp(id) && (stored[id]?.fetchedAt ?: 0) < now - DAY_MS }
        if (stale.isEmpty()) return@withLock
        val fetched = stale.mapNotNull { id -> cinemeta.episodes(id)?.let { id to Entry(now, it) } }.toMap()
        if (fetched.isEmpty()) return@withLock
        stored = stored + fetched
        _episodes.value = stored.mapValues { it.value.episodes }
        withContext(Dispatchers.IO) { runCatching { file.writeText(NuvioClient.json.encodeToString(stored)) } }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
