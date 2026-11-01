package app.nuviowatch.data

import android.content.Context
import app.nuviowatch.BuildConfig
import app.nuviowatch.core.Cinemeta
import app.nuviowatch.core.NuvioClient
import app.nuviowatch.core.OFFICIAL_SERVER_URL
import app.nuviowatch.core.ServerCandidates
import app.nuviowatch.core.ServerConfig
import app.nuviowatch.core.ServerSetup
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared objects for the whole app. */
class AppGraph(private val context: Context) {
    val prefs = Prefs(context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val cinemeta = Cinemeta(http)
    val titles = TitleCache(context, cinemeta)
    val episodes = EpisodeCache(context, cinemeta)
    val updates = app.nuviowatch.core.Updates(http)
    val repository = Repository(context, ::client, titles)
    private val serverSetup = ServerSetup(http)

    private var cachedClient: Pair<ServerConfig, NuvioClient>? = null

    /** A client for the saved server, or null until a server key is known. */
    @Synchronized
    fun client(): NuvioClient? {
        val config = prefs.serverConfig ?: return null
        cachedClient?.let { (c, client) -> if (c == config) return client }
        return NuvioClient(config, prefs, http).also { cachedClient = config to it }
    }

    fun signOut() {
        prefs.clearAccount()
        repository.clear()
    }

    /**
     * Works out the Nuvio server settings without the user typing a key, using the key
     * built into the app (the GitHub build reads it from the official Nuvio release).
     */
    suspend fun autoDetectServer(): ServerConfig? {
        prefs.serverConfig?.let { if (serverSetup.isValid(it)) return it }
        val urls = BuildConfig.DEFAULT_SERVER_URLS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(OFFICIAL_SERVER_URL) }
        val builtIn = ServerCandidates(listOfNotNull(BuildConfig.DEFAULT_SERVER_KEY.takeIf { it.isNotBlank() }), urls)
        val found = serverSetup.firstWorking(builtIn, urls)
        if (found != null) prefs.serverConfig = found
        return found
    }

    /** Uses a server the user typed in: a self-hosted address, optionally with its key. */
    suspend fun useCustomServer(url: String, key: String): ServerConfig? {
        val config = if (key.isNotBlank()) {
            ServerConfig(url.let { if ("://" in it) it else "https://$it" }, key.trim())
        } else {
            serverSetup.discover(url)
        } ?: return null
        if (!serverSetup.isValid(config)) return null
        prefs.serverConfig = config
        return config
    }

    val isNuvioInstalled: Boolean
        get() = runCatching { context.packageManager.getApplicationInfo(NUVIO_PACKAGE, 0) }.isSuccess

    private companion object {
        const val NUVIO_PACKAGE = "com.nuvio.app"
    }
}
