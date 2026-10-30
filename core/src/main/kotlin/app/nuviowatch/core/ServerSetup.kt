package app.nuviowatch.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile

const val OFFICIAL_SERVER_URL = "https://api.nuvio.tv"

/** Strings in an app binary that look like Nuvio's server address or public client key. */
data class ServerCandidates(
    val keys: List<String>,
    val urls: List<String>,
) {
    operator fun plus(other: ServerCandidates) =
        ServerCandidates((keys + other.keys).distinct(), (urls + other.urls).distinct())

    companion object {
        val EMPTY = ServerCandidates(emptyList(), emptyList())
    }
}

/**
 * Finds the public ("publishable"/"anon") key that the official Nuvio app ships with.
 * Supabase designs this key to be embedded in apps; it only identifies the project.
 */
object KeyExtractor {
    private val publishable = Regex("sb_publishable_[A-Za-z0-9_-]{16,}")
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{20,}")
    private val supabaseUrl = Regex("https://[a-z0-9]{20}\\.supabase\\.co")
    private val nuvioApiUrl = Regex("https://api\\.nuvio\\.tv")

    fun scan(text: CharSequence): ServerCandidates {
        val keys = publishable.findAll(text).map { it.value }.toList() +
            jwt.findAll(text).map { it.value }.filter(::isAnonJwt).toList()
        val urls = nuvioApiUrl.findAll(text).map { it.value }.toList() +
            supabaseUrl.findAll(text).map { it.value }.toList()
        return ServerCandidates(keys.distinct(), urls.distinct())
    }

    fun scan(bytes: ByteArray): ServerCandidates = scan(String(bytes, Charsets.ISO_8859_1))

    /** Scans the compiled code inside an APK file. */
    fun scanApk(apk: File): ServerCandidates = ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
            .fold(ServerCandidates.EMPTY) { acc, entry -> acc + scan(zip.getInputStream(entry).use { it.readBytes() }) }
    }

    private fun isAnonJwt(token: String): Boolean = runCatching {
        val payload = String(Base64.getUrlDecoder().decode(token.split('.')[1].padEnd((token.split('.')[1].length + 3) / 4 * 4, '=')))
        payload.contains("\"role\":\"anon\"")
    }.getOrDefault(false)
}

class ServerSetup(private val http: OkHttpClient = OkHttpClient()) {

    @Serializable
    private data class Discovery(
        val service: String = "",
        @SerialName("backend_url") val backendUrl: String = "",
        @SerialName("publishable_key") val publishableKey: String = "",
    )

    /** Self-hosted Nuvio servers publish their settings at /.well-known/nuvio. */
    suspend fun discover(serverUrl: String): ServerConfig? = withContext(Dispatchers.IO) {
        val base = serverUrl.trim().let { if ("://" in it) it else "https://$it" }.trimEnd('/')
        runCatching {
            http.newCall(Request.Builder().url("$base/.well-known/nuvio").build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val doc = NuvioClient.json.decodeFromString<Discovery>(response.body?.string().orEmpty())
                if (!doc.service.equals("nuvio", true) || doc.publishableKey.isBlank()) return@use null
                ServerConfig(doc.backendUrl.ifBlank { base }, doc.publishableKey)
            }
        }.getOrNull()
    }

    /** True when the server accepts the key (the auth settings endpoint needs a valid key). */
    suspend fun isValid(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder()
                    .url("${config.baseUrl}/auth/v1/settings")
                    .header("apikey", config.publishableKey)
                    .build(),
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Tries every url/key pair and returns the first one the server accepts. */
    suspend fun firstWorking(candidates: ServerCandidates, extraUrls: List<String> = listOf(OFFICIAL_SERVER_URL)): ServerConfig? {
        val urls = (extraUrls + candidates.urls).map { it.trimEnd('/') }.distinct()
        for (url in urls) for (key in candidates.keys) {
            val config = ServerConfig(url, key)
            if (isValid(config)) return config
        }
        return null
    }
}
