package app.nuviowatch.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where the Nuvio sync backend lives. Both values come from the official app or a self-hosted server. */
@Serializable
data class ServerConfig(
    val url: String,
    val publishableKey: String,
) {
    val baseUrl: String get() = url.trim().trimEnd('/')
}

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch seconds when [accessToken] stops working. */
    val expiresAt: Long,
    val userId: String = "",
    val email: String = "",
)

@Serializable
data class Profile(
    @SerialName("profile_index") val index: Int = 1,
    val name: String = "",
    @SerialName("avatar_color_hex") val avatarColorHex: String = "#1E88E5",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** One row of a profile's "continue watching" progress, as stored by Nuvio. */
@Serializable
data class ProgressEntry(
    @SerialName("progress_key") val progressKey: String = "",
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String = "",
    @SerialName("video_id") val videoId: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    /** Milliseconds into the video. */
    val position: Long = 0,
    /** Video length in milliseconds (0 when unknown). */
    val duration: Long = 0,
    @SerialName("last_watched") val lastWatched: Long = 0,
) {
    val lastWatchedMs: Long get() = normalizeEpochMs(lastWatched)
    val isCompleted: Boolean get() = isComplete(position, duration)
    val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

/** A change to a profile's progress, read from Nuvio's delta feed. */
@Serializable
data class ProgressEvent(
    @SerialName("event_id") val eventId: Long,
    val operation: String,
    @SerialName("progress_key") val progressKey: String = "",
    @SerialName("content_id") val contentId: String = "",
    @SerialName("content_type") val contentType: String = "",
    @SerialName("video_id") val videoId: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long = 0,
    val duration: Long = 0,
    @SerialName("last_watched") val lastWatched: Long = 0,
) {
    val isUpsert: Boolean get() = operation.equals("upsert", ignoreCase = true)

    fun toEntry() = ProgressEntry(
        progressKey = progressKey,
        contentId = contentId,
        contentType = contentType,
        videoId = videoId,
        season = season,
        episode = episode,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
    )
}

/** Something a profile has finished (or marked as watched). */
@Serializable
data class WatchedItem(
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String = "",
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("watched_at") val watchedAt: Long = 0,
) {
    val watchedAtMs: Long get() = normalizeEpochMs(watchedAt)
}

/** Title details looked up from Cinemeta, cached on the phone. */
@Serializable
data class TitleInfo(
    val name: String,
    val poster: String? = null,
    /** Wide backdrop image for the "Now playing" card. */
    val background: String? = null,
    val genres: List<String> = emptyList(),
    /** Typical runtime of the film, or of one episode for a series. */
    val runtimeMinutes: Int? = null,
    val year: String? = null,
)

/** Nuvio treats 90% watched as finished. */
fun isComplete(positionMs: Long, durationMs: Long): Boolean =
    durationMs >= MIN_REAL_DURATION_MS && positionMs.toDouble() / durationMs >= 0.90

/** Some rows store seconds, others milliseconds. */
fun normalizeEpochMs(value: Long): Long = if (value in 1 until 100_000_000_000L) value * 1000 else value

fun isSeries(contentType: String, season: Int?, videoId: String = ""): Boolean =
    season != null ||
        contentType.equals("series", true) ||
        contentType.equals("episode", true) ||
        contentType.equals("anime", true) ||
        contentType.equals("tv", true) ||
        videoId.count { it == ':' } >= 2

fun episodeLabel(season: Int?, episode: Int?): String? =
    if (season != null && episode != null) "S$season · E$episode" else null

private const val MIN_REAL_DURATION_MS = 121_000L
