package app.nuviowatch.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Looks up names, posters, genres and runtimes for IMDb ids using Cinemeta,
 * the free public catalogue that Stremio-style apps such as Nuvio use.
 */
class Cinemeta(
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://v3-cinemeta.strem.io",
) {
    @Serializable
    private data class Response(val meta: Meta? = null)

    @Serializable
    private data class Meta(
        val name: String? = null,
        val poster: String? = null,
        val genres: List<String>? = null,
        val genre: List<String>? = null,
        val runtime: String? = null,
        val year: String? = null,
        val releaseInfo: String? = null,
    )

    /** The title, or null if Cinemeta doesn't know it. Throws when Cinemeta can't be reached. */
    suspend fun fetch(id: String, series: Boolean): TitleInfo? = withContext(Dispatchers.IO) {
        if (!canLookUp(id)) return@withContext null
        val type = if (series) "series" else "movie"
        http.newCall(Request.Builder().url("$baseUrl/meta/$type/$id.json").build()).execute().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw java.io.IOException("Cinemeta returned ${response.code}")
            val meta = NuvioClient.json.decodeFromString<Response>(response.body?.string().orEmpty()).meta
                ?: return@use null
            TitleInfo(
                name = meta.name ?: return@use null,
                poster = meta.poster,
                genres = meta.genres ?: meta.genre.orEmpty(),
                runtimeMinutes = parseRuntimeMinutes(meta.runtime),
                year = meta.year ?: meta.releaseInfo,
            )
        }
    }

    suspend fun lookup(id: String, series: Boolean): TitleInfo? = runCatching { fetch(id, series) }.getOrNull()

    /**
     * Looks up many titles, a few at a time. Titles Cinemeta doesn't know map to null;
     * titles that couldn't be checked (network trouble) are left out so they can be retried.
     */
    suspend fun lookupAll(requests: Map<String, Boolean>, parallelism: Int = 6): Map<String, TitleInfo?> = coroutineScope {
        val gate = Semaphore(parallelism)
        requests.map { (id, series) ->
            async { gate.withPermit { runCatching { fetch(id, series) }.map { id to it }.getOrNull() } }
        }.awaitAll().filterNotNull().toMap()
    }

    companion object {
        fun canLookUp(id: String) = id.startsWith("tt")

        /** Cinemeta runtimes look like "142 min", "1h 30min" or "45". */
        fun parseRuntimeMinutes(text: String?): Int? {
            if (text.isNullOrBlank()) return null
            val hours = Regex("(\\d+)\\s*h").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minutes = Regex("(\\d+)\\s*m").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (hours == 0) Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() else 0
            return (hours * 60 + (minutes ?: 0)).takeIf { it > 0 }
        }
    }
}
