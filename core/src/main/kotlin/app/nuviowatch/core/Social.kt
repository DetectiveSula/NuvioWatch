package app.nuviowatch.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An emoji someone sends to whoever is watching. */
@Serializable
data class Reaction(
    val type: String = "reaction",
    val id: String,
    val fromDeviceId: String,
    val fromName: String,
    val toProfile: Int? = null,
    /** The device that was playing, so it can show the reaction even if its profile is unknown. */
    val toDeviceId: String,
    val emoji: String,
    val title: String,
    val at: Long,
)

/** A nudge to someone: "Come watch with me!" or "Watch together?". */
@Serializable
data class Ping(
    val type: String = "ping",
    val id: String,
    val kind: String, // "join" or "together"
    val fromDeviceId: String,
    val fromName: String,
    val toProfile: Int,
    val title: String,
    val deviceName: String? = null,
    val at: Long,
)

enum class PredictionKind { FINISH_SEASON, FINISH_FILM, WATCH_HOURS }

/** "Will Mani finish season 6 by Sunday?" — settled automatically from Nuvio history. */
@Serializable
data class Prediction(
    val type: String = "prediction",
    val id: String,
    val byName: String,
    val profileIndex: Int,
    val kind: PredictionKind,
    val contentId: String? = null,
    val season: Int? = null,
    val hours: Int? = null,
    val deadlineMs: Long,
    val createdAt: Long,
)

@Serializable
data class Vote(
    val type: String = "vote",
    val predictionId: String,
    val voterId: String,
    val voterName: String,
    val yes: Boolean,
    val at: Long,
)

enum class Outcome { PENDING, YES, NO }

data class Tally(val yes: List<String>, val no: List<String>)

object Social {
    // "type" tells the message kinds apart, so it must be written even though it has a default.
    private val json = Json(NuvioClient.json) { encodeDefaults = true }

    /** Decodes any message on the live channel: playback, reaction, prediction or vote. */
    fun parse(text: String): Any? = runCatching {
        val type = json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.contentOrNull
        when (type) {
            "reaction" -> json.decodeFromString<Reaction>(text)
            "prediction" -> json.decodeFromString<Prediction>(text)
            "vote" -> json.decodeFromString<Vote>(text)
            "ping" -> json.decodeFromString<Ping>(text)
            else -> json.decodeFromString<LiveEvent>(text)
        }
    }.getOrNull()

    fun encode(message: Any): String = when (message) {
        is Reaction -> json.encodeToString(message)
        is Prediction -> json.encodeToString(message)
        is Vote -> json.encodeToString(message)
        is Ping -> json.encodeToString(message)
        is LiveEvent -> json.encodeToString(message)
        else -> error("Not a live message: $message")
    }

    /** Settles a prediction from what the profile has watched. */
    fun resolve(p: Prediction, viewings: List<Viewing>, episodes: Map<String, List<EpisodeInfo>>, nowMs: Long): Outcome {
        val mine = viewings.filter { it.profileIndex == p.profileIndex && it.atMs in p.createdAt - 1..p.deadlineMs }
        val done = when (p.kind) {
            PredictionKind.FINISH_FILM -> mine.any { it.contentId == p.contentId && it.completed }
            PredictionKind.FINISH_SEASON -> {
                val season = episodes[p.contentId]?.filter { it.season == p.season && (it.releasedMs ?: 0) <= p.deadlineMs }.orEmpty()
                val finished = viewings.filter { it.profileIndex == p.profileIndex && it.contentId == p.contentId && it.season == p.season && it.completed && it.atMs <= p.deadlineMs }
                    .map { it.episode }.toSet()
                season.isNotEmpty() && season.all { it.episode in finished }
            }
            PredictionKind.WATCH_HOURS -> mine.sumOf { it.watchedMs } >= (p.hours ?: 0) * 3_600_000L
        }
        return when {
            done -> Outcome.YES
            nowMs > p.deadlineMs -> Outcome.NO
            else -> Outcome.PENDING
        }
    }

    fun question(p: Prediction, name: String, title: String?, deadline: String): String = when (p.kind) {
        PredictionKind.FINISH_SEASON -> "Will $name finish ${title ?: "the show"} season ${p.season} by $deadline?"
        PredictionKind.FINISH_FILM -> "Will $name finish ${title ?: "the film"} by $deadline?"
        PredictionKind.WATCH_HOURS -> "Will $name watch ${p.hours}+ hours by $deadline?"
    }

    /** Each voter's latest vote counts. */
    fun tally(predictionId: String, votes: List<Vote>): Tally {
        val latest = votes.filter { it.predictionId == predictionId }.groupBy { it.voterId }.values.map { it.maxBy { v -> v.at } }
        return Tally(latest.filter { it.yes }.map { it.voterName }, latest.filterNot { it.yes }.map { it.voterName })
    }
}
