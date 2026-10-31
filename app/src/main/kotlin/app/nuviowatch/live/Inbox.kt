package app.nuviowatch.live

import android.content.Context
import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.Comment
import app.nuviowatch.core.Party
import app.nuviowatch.core.Ping
import app.nuviowatch.core.Rating
import app.nuviowatch.core.Suggestion
import app.nuviowatch.core.Prediction
import app.nuviowatch.core.Reaction
import app.nuviowatch.core.Social
import app.nuviowatch.core.Vote
import app.nuviowatch.graph
import app.nuviowatch.monitor.Notifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Every message on the account's live channel comes through here: playback
 * reports, reactions, predictions and votes. Both the background service and
 * a playing device listen, so messages are de-duplicated by id.
 */
object Inbox {
    private val mutex = Mutex()
    private val seen = LinkedHashSet<String>()

    suspend fun onMessage(context: Context, id: String, text: String, fromRelay: Boolean = true) {
        val fresh = mutex.withLock {
            if (!seen.add(id)) return
            if (seen.size > 500) seen.remove(seen.first())
            true
        }
        if (!fresh) return
        val prefs = context.graph.prefs
        when (val message = Social.parse(text)) {
            is LiveEvent -> {
                prefs.setStatus("received", "${message.deviceName}: ${message.state.name.lowercase()} · ${message.title}")
                LiveReceiver.onEvent(context, id, message)
            }
            is Reaction -> onReaction(context, message)
            is Prediction -> onPrediction(context, message)
            is Vote -> if (prefs.votes.none { it.voterId == message.voterId && it.at == message.at }) prefs.votes = prefs.votes + message
            is Party -> if (message.fromDeviceId != prefs.deviceId && System.currentTimeMillis() - message.at < 2 * 3_600_000L) Notifier.showParty(context, message)
            is Suggestion -> if (prefs.suggestions.none { it.id == message.id }) {
                prefs.suggestions = prefs.suggestions + message
                if (message.toProfile == prefs.deviceProfile && message.fromProfile != prefs.deviceProfile) Notifier.showSuggestion(context, message)
            }
            is Rating -> if (prefs.ratings.none { it.id == message.id && it.at == message.at }) prefs.ratings = prefs.ratings + message
            is Comment -> if (prefs.comments.none { it.id == message.id }) {
                prefs.comments = prefs.comments + message
                maybeNotifyComment(context, message)
            }
            is Ping -> {
                val forMe = message.fromDeviceId != prefs.deviceId && message.toProfile == prefs.deviceProfile
                if (forMe && System.currentTimeMillis() - message.at < 30 * 60_000L) Notifier.showPing(context, message)
            }
        }
        if (fromRelay) prefs.liveSince = id
    }

    /** Fetches reactions, predictions and votes stored while this phone was offline. */
    suspend fun catchUp(context: Context) {
        val prefs = context.graph.prefs
        val db = LiveHub.db(context) ?: return
        val since = prefs.dbSince ?: (System.currentTimeMillis() - 3 * 86_400_000L)
        runCatching { db.since(since, setOf("reaction", "prediction", "vote", "suggest", "rating", "comment")) }
            .onSuccess { rows ->
                rows.forEach { onMessage(context, "db-${it.id}", it.text, fromRelay = false) }
                rows.maxOfOrNull { it.at }?.let { prefs.dbSince = it } ?: run { if (prefs.dbSince == null) prefs.dbSince = since }
                prefs.setStatus("database", "Up to date (${rows.size} new)")
            }
            .onFailure { prefs.setStatus("database", "Couldn't reach it: ${it.message}") }
    }

    private fun onReaction(context: Context, r: Reaction) {
        val prefs = context.graph.prefs
        if (prefs.reactions.any { it.id == r.id }) return
        prefs.reactions = prefs.reactions + r
        val forMe = r.fromDeviceId != prefs.deviceId &&
            (r.toDeviceId == prefs.deviceId || (r.toProfile != null && r.toProfile == prefs.deviceProfile))
        val fresh = System.currentTimeMillis() - r.at < 10 * 60_000L
        if (forMe && fresh) {
            if (r.toDeviceId == prefs.deviceId && ReactionOverlay.canShow(context)) {
                ReactionOverlay.show(context, r.emoji, r.fromName)
            } else {
                Notifier.showReaction(context, r)
            }
        }
    }

    private fun onPrediction(context: Context, p: Prediction) {
        val prefs = context.graph.prefs
        if (prefs.predictions.any { it.id == p.id }) return
        prefs.predictions = prefs.predictions + p
        val fromSomeoneElse = prefs.votes.none { it.predictionId == p.id && it.voterId == prefs.deviceId } &&
            p.byName != prefs.myName(context.graph.repository.snapshot.value.profiles)
        if (fromSomeoneElse && System.currentTimeMillis() - p.createdAt < 60 * 60_000L) Notifier.showPrediction(context, p)
    }

    // ------------------------------------------------------------ Sending

    suspend fun react(context: Context, to: LiveEvent, toProfile: Int?, emoji: String): Boolean {
        val graph = context.graph
        val prefs = graph.prefs
        val reaction = Reaction(
            id = UUID.randomUUID().toString(),
            fromDeviceId = prefs.deviceId,
            fromName = prefs.myName(graph.repository.snapshot.value.profiles),
            toProfile = toProfile,
            toDeviceId = to.deviceId,
            emoji = emoji,
            title = LiveHub.titleFor(to, graph.titles.titles.value)?.name ?: to.title,
            at = System.currentTimeMillis(),
        )
        prefs.reactions = prefs.reactions + reaction
        LiveHub.store(context, reaction.id, "reaction", Social.encode(reaction), reaction.at)
        return send(context, Social.encode(reaction))
    }

    /** Notify about a new comment only if it's on something I watch and won't spoil it. */
    private fun maybeNotifyComment(context: Context, c: Comment) {
        val graph = context.graph
        val me = graph.prefs.deviceProfile ?: return
        if (c.fromProfile == me || System.currentTimeMillis() - c.at > 3_600_000L) return
        val viewings = app.nuviowatch.recap.Nudges.viewings(graph.repository.snapshot.value, context)
        if (viewings.none { it.profileIndex == me && it.contentId == c.contentId }) return
        val reached = app.nuviowatch.core.Fun.latestEpisode(viewings, me, c.contentId)
        val (visible, _) = Social.visibleComments(listOf(c), c.contentId, reached)
        if (visible.isNotEmpty()) Notifier.showComment(context, c, graph.titles[c.contentId]?.name)
    }

    private fun myName(context: Context) = context.graph.prefs.myName(context.graph.repository.snapshot.value.profiles)

    suspend fun party(context: Context, code: String, title: String?): Boolean {
        val p = Party(id = UUID.randomUUID().toString(), fromDeviceId = context.graph.prefs.deviceId, fromName = myName(context), code = code, title = title, at = System.currentTimeMillis())
        return send(context, Social.encode(p))
    }

    suspend fun suggest(context: Context, toProfile: Int, contentId: String, series: Boolean, title: String): Boolean {
        val prefs = context.graph.prefs
        val s = Suggestion(
            id = UUID.randomUUID().toString(), fromName = myName(context), fromProfile = prefs.deviceProfile,
            toProfile = toProfile, contentId = contentId, series = series, title = title, at = System.currentTimeMillis(),
        )
        prefs.suggestions = prefs.suggestions + s
        LiveHub.store(context, s.id, "suggest", Social.encode(s), s.at)
        return send(context, Social.encode(s))
    }

    suspend fun rate(context: Context, profileIndex: Int, contentId: String, stars: Int): Boolean {
        val prefs = context.graph.prefs
        val r = Rating(id = "$profileIndex|$contentId", profileIndex = profileIndex, contentId = contentId, stars = stars.coerceIn(1, 5), at = System.currentTimeMillis())
        prefs.ratings = prefs.ratings + r
        LiveHub.store(context, "${r.id}|${r.at}", "rating", Social.encode(r), r.at)
        return send(context, Social.encode(r))
    }

    suspend fun comment(context: Context, contentId: String, season: Int?, episode: Int?, text: String): Boolean {
        val prefs = context.graph.prefs
        val c = Comment(
            id = UUID.randomUUID().toString(), contentId = contentId, season = season, episode = episode,
            fromName = myName(context), fromProfile = prefs.deviceProfile, text = text.trim(), at = System.currentTimeMillis(),
        )
        prefs.comments = prefs.comments + c
        LiveHub.store(context, c.id, "comment", Social.encode(c), c.at)
        return send(context, Social.encode(c))
    }

    /** "join": come watch with me; "together": let's watch this together. */
    suspend fun ping(context: Context, toProfile: Int, kind: String, title: String, deviceName: String? = null): Boolean {
        val graph = context.graph
        val prefs = graph.prefs
        val ping = Ping(
            id = UUID.randomUUID().toString(),
            kind = kind,
            fromDeviceId = prefs.deviceId,
            fromName = prefs.myName(graph.repository.snapshot.value.profiles),
            toProfile = toProfile,
            title = title,
            deviceName = deviceName,
            at = System.currentTimeMillis(),
        )
        return send(context, Social.encode(ping))
    }

    suspend fun predict(context: Context, prediction: Prediction): Boolean {
        val prefs = context.graph.prefs
        prefs.predictions = prefs.predictions + prediction
        LiveHub.store(context, prediction.id, "prediction", Social.encode(prediction), prediction.createdAt)
        return send(context, Social.encode(prediction))
    }

    suspend fun vote(context: Context, predictionId: String, yes: Boolean): Boolean {
        val graph = context.graph
        val prefs = graph.prefs
        val vote = Vote(
            predictionId = predictionId,
            voterId = prefs.deviceId,
            voterName = prefs.myName(graph.repository.snapshot.value.profiles),
            yes = yes,
            at = System.currentTimeMillis(),
        )
        prefs.votes = prefs.votes + vote
        LiveHub.store(context, "${vote.voterId}-${vote.predictionId}-${vote.at}", "vote", Social.encode(vote), vote.at)
        return send(context, Social.encode(vote))
    }

    private suspend fun send(context: Context, text: String): Boolean {
        val relay = LiveHub.relay(context) ?: return false
        return runCatching { relay.publishText(text) }.isSuccess
    }
}
