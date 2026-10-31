package app.nuviowatch.data

import android.content.Context
import androidx.core.content.edit
import app.nuviowatch.core.NuvioClient
import app.nuviowatch.core.ProgressEntry
import app.nuviowatch.core.ServerConfig
import app.nuviowatch.core.Session
import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.Prediction
import app.nuviowatch.core.Reaction
import app.nuviowatch.core.Vote
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

    /** Stable id for this device in live reports. */
    val deviceId: String
        get() = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also { id ->
            prefs.edit { putString("device_id", id) }
        }

    /** Name shown for this device, e.g. "Living room TV". */
    var deviceName: String
        get() = prefs.getString("device_name", null) ?: android.os.Build.MODEL
        set(value) = prefs.edit { putString("device_name", value.trim()) }

    /** Profile that watches on this device, or null to work it out from the title. */
    var deviceProfile: Int?
        get() = prefs.getInt("device_profile", -1).takeIf { it >= 0 }
        set(value) = prefs.edit { putInt("device_profile", value ?: -1) }

    var dailyRecap: Boolean
        get() = prefs.getBoolean("daily_recap", true)
        set(value) = prefs.edit { putBoolean("daily_recap", value) }

    /** Hour of the day (0–23) the daily recap arrives; the weekly one comes on Sunday at the same time. */
    var recapHour: Int
        get() = prefs.getInt("recap_hour", 21)
        set(value) = prefs.edit { putInt("recap_hour", value) }

    var weeklyRecap: Boolean
        get() = prefs.getBoolean("weekly_recap", true)
        set(value) = prefs.edit { putBoolean("weekly_recap", value) }

    /** Normally a phone doesn't alert about its own playback; this turns that on (handy for testing). */
    var alertOwnDevice: Boolean
        get() = prefs.getBoolean("alert_own_device", false)
        set(value) = prefs.edit { putBoolean("alert_own_device", value) }

    var reportPlayback: Boolean
        get() = prefs.getBoolean("report_playback", true)
        set(value) = prefs.edit { putBoolean("report_playback", value) }

    /** Latest live state of every device on the account. */
    var liveDevices: Map<String, LiveEvent>
        get() = prefs.getString("live_devices", null)
            ?.let { runCatching { json.decodeFromString<Map<String, LiveEvent>>(it) }.getOrNull() }
            .orEmpty()
        set(value) = prefs.edit { putString("live_devices", json.encodeToString(value)) }

    /** Last relay message read, so a reconnect picks up where it left off. */
    var liveSince: String?
        get() = prefs.getString("live_since", null)
        set(value) = prefs.edit { putString("live_since", value) }

    /** Newest progress time seen per profile, for when Nuvio's change feed isn't available. */
    fun lastSeenProgress(profileId: Int): Long = prefs.getLong("last_seen_$profileId", 0)
    fun setLastSeenProgress(profileId: Int, at: Long) = prefs.edit { putLong("last_seen_$profileId", at) }

    /** Latest status line from each part of the app, shown in Settings for troubleshooting. */
    fun setStatus(part: String, text: String) =
        prefs.edit { putString("status_$part", text).putLong("status_at_$part", System.currentTimeMillis()) }

    fun status(part: String): Pair<String, Long>? =
        prefs.getString("status_$part", null)?.let { it to prefs.getLong("status_at_$part", 0) }

    var reactions: List<Reaction>
        get() = prefs.getString("reactions", null)?.let { runCatching { json.decodeFromString<List<Reaction>>(it) }.getOrNull() }.orEmpty()
        set(value) = prefs.edit { putString("reactions", json.encodeToString(value.takeLast(40))) }

    var predictions: List<Prediction>
        get() = prefs.getString("predictions", null)?.let { runCatching { json.decodeFromString<List<Prediction>>(it) }.getOrNull() }.orEmpty()
        set(value) = prefs.edit { putString("predictions", json.encodeToString(value.takeLast(50))) }

    var votes: List<Vote>
        get() = prefs.getString("votes", null)?.let { runCatching { json.decodeFromString<List<Vote>>(it) }.getOrNull() }.orEmpty()
        set(value) = prefs.edit { putString("votes", json.encodeToString(value.takeLast(300))) }

    /** Predictions whose result has already been announced. */
    var announcedPredictions: Set<String>
        get() = prefs.getStringSet("announced_predictions", emptySet()).orEmpty()
        set(value) = prefs.edit { putStringSet("announced_predictions", value) }

    /** Name this device uses when reacting or voting: its profile, else its device name. */
    fun myName(profiles: List<app.nuviowatch.core.Profile>): String =
        deviceProfile?.let { index -> profiles.firstOrNull { it.index == index }?.name } ?: deviceName

        /** True once the first-launch setup has been finished or skipped. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit { putBoolean("onboarded", value) }

    /** Badges already announced, as "profile|BADGE"; null until the first check seeds it quietly. */
    var announcedBadges: Set<String>?
        get() = if (prefs.contains("announced_badges")) prefs.getStringSet("announced_badges", emptySet()).orEmpty() else null
        set(value) = prefs.edit { if (value == null) remove("announced_badges") else putStringSet("announced_badges", value) }

    /** Last "once a day/week" announcement sent, by name (e.g. "winner" → "2026-W44"). */
    fun lastSent(name: String): String? = prefs.getString("last_sent_$name", null)
    fun setLastSent(name: String, value: String) = prefs.edit { putString("last_sent_$name", value) }

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
