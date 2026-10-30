package app.nuviowatch.data

import android.content.Context
import app.nuviowatch.BuildConfig
import app.nuviowatch.core.Cinemeta
import app.nuviowatch.core.KeyExtractor
import app.nuviowatch.core.NuvioClient
import app.nuviowatch.core.OFFICIAL_SERVER_URL
import app.nuviowatch.core.ServerCandidates
import app.nuviowatch.core.ServerConfig
import app.nuviowatch.core.ServerSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Shared objects for the whole app. */
class AppGraph(private val context: Context) {
    val prefs = Prefs(context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val titles = TitleCache(context, Cinemeta(http))
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
     * Works out the Nuvio server settings without the user typing a key:
     * the key baked in at build time, then the official Nuvio app if it's installed.
     */
    suspend fun autoDetectServer(): ServerConfig? {
        prefs.serverConfig?.let { if (serverSetup.isValid(it)) return it }
        val urls = BuildConfig.DEFAULT_SERVER_URLS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(OFFICIAL_SERVER_URL) }
        val builtIn = ServerCandidates(listOfNotNull(BuildConfig.DEFAULT_SERVER_KEY.takeIf { it.isNotBlank() }), urls)
        val found = serverSetup.firstWorking(builtIn, urls)
            ?: serverSetup.firstWorking(scanInstalledNuvio() ?: ServerCandidates.EMPTY, urls)
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

    /** Reads the public server key out of the installed official Nuvio app. */
    private suspend fun scanInstalledNuvio(): ServerCandidates? = withContext(Dispatchers.IO) {
        runCatching {
            val info = context.packageManager.getApplicationInfo(NUVIO_PACKAGE, 0)
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty())
                .map { KeyExtractor.scanApk(File(it)) }
                .fold(ServerCandidates.EMPTY, ServerCandidates::plus)
        }.getOrNull()
    }

    private companion object {
        const val NUVIO_PACKAGE = "com.nuvio.app"
    }
}
