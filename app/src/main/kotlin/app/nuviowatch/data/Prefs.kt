package app.nuviowatch.data

import android.content.Context
import androidx.core.content.edit
import app.nuviowatch.core.NuvioClient
import app.nuviowatch.core.ProgressEntry
import app.nuviowatch.core.ServerConfig
import app.nuviowatch.core.Session
import app.nuviowatch.core.SessionStore
import app.nuviowatch.core.WatchSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

data class MonitorSettings(
    val enabled: Boolean = false,
    val intervalSeconds: Int = 45,
    val notifyFinished: Boolean = false,
    /** Profiles the user doesn't want alerts about (usually their own). */
    val mutedProfiles: Set<Int> = emptySet(),
)

/** Small settings and state, kept in the app's private storage. */
class Prefs(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("nuvio_watch", Context.MODE_PRIVATE)
    private val json = NuvioClient.json

    private val _signedIn = MutableStateFlow(readSession() != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<MonitorSettings> = _settings.asStateFlow()

    override var session: Session?
        get() = readSession()
        set(value) {
            prefs.edit { if (value == null) remove(KEY_SESSION) else putString(KEY_SESSION, json.encodeToString(value)) }
            _signedIn.value = value != null
        }

    private fun readSession(): Session? =
        prefs.getString(KEY_SESSION, null)?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }

    var serverConfig: ServerConfig?
        get() = prefs.getString(KEY_SERVER, null)?.let { runCatching { json.decodeFromString<ServerConfig>(it) }.getOrNull() }
        set(value) = prefs.edit { if (value == null) remove(KEY_SERVER) else putString(KEY_SERVER, json.encodeToString(value)) }

    fun updateSettings(change: (MonitorSettings) -> MonitorSettings) {
        val next = change(_settings.value)
        prefs.edit {
            putBoolean("monitor_enabled", next.enabled)
            putInt("monitor_interval", next.intervalSeconds)
            putBoolean("notify_finished", next.notifyFinished)
            putStringSet("muted_profiles", next.mutedProfiles.map(Int::toString).toSet())
        }
        _settings.value = next
    }

    private fun readSettings() = MonitorSettings(
        enabled = prefs.getBoolean("monitor_enabled", false),
        intervalSeconds = prefs.getInt("monitor_interval", 45),
        notifyFinished = prefs.getBoolean("notify_finished", false),
        mutedProfiles = prefs.getStringSet("muted_profiles", emptySet()).orEmpty().mapNotNull(String::toIntOrNull).toSet(),
    )

    /** Position in each profile's change feed, so only new changes are checked. */
    fun cursor(profileId: Int): Long? = prefs.getLong("cursor_$profileId", -1).takeIf { it >= 0 }
    fun setCursor(profileId: Int, value: Long) = prefs.edit { putLong("cursor_$profileId", value) }

    /** Each profile's current viewing session, waiting to be summarised. */
    var sessions: Map<Int, WatchSession>
        get() = prefs.getString("sessions", null)
            ?.let { runCatching { json.decodeFromString<Map<Int, WatchSession>>(it) }.getOrNull() }
            .orEmpty()
        set(value) = prefs.edit { putString("sessions", json.encodeToString(value)) }

    /** Last known position of each film or episode, so a session counts only new viewing. */
    fun knownPositions(profileId: Int): Map<String, Long> =
        prefs.getString("positions_$profileId", null)
            ?.let { runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull() }
            .orEmpty()

    fun setKnownPositions(profileId: Int, positions: Map<String, Long>) =
        prefs.edit { putString("positions_$profileId", json.encodeToString(positions)) }

    /** Alerts about a profile are paused until this time (set by "Mute today"). */
    fun mutedUntil(profileId: Int): Long = prefs.getLong("muted_until_$profileId", 0)
    fun setMutedUntil(profileId: Int, at: Long) = prefs.edit { putLong("muted_until_$profileId", at) }

    fun lastAlertAt(key: String): Long? = prefs.getLong("alert_$key", -1).takeIf { it >= 0 }
    fun setLastAlertAt(key: String, at: Long) = prefs.edit { putLong("alert_$key", at) }

    var lastPollAt: Long
        get() = prefs.getLong("last_poll", 0)
        set(value) = prefs.edit { putLong("last_poll", value) }

    var lastPollError: String?
        get() = prefs.getString("last_poll_error", null)
        set(value) = prefs.edit { putString("last_poll_error", value) }

    /** Newest progress change the monitor has seen for each profile. */
    var liveEntries: Map<Int, ProgressEntry>
        get() = prefs.getString("live_entries", null)
            ?.let { runCatching { json.decodeFromString<Map<Int, ProgressEntry>>(it) }.getOrNull() }
            .orEmpty()
        set(value) = prefs.edit { putString("live_entries", json.encodeToString(value)) }

    /** Forgets everything tied to the account, keeping the server settings. */
    fun clearAccount() {
        val server = prefs.getString(KEY_SERVER, null)
        prefs.edit {
            clear()
            if (server != null) putString(KEY_SERVER, server)
        }
        _signedIn.value = false
        _settings.value = MonitorSettings()
    }

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_SERVER = "server"
    }
}
