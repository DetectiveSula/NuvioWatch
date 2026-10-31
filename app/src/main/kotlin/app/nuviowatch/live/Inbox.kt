package app.nuviowatch.live

import android.content.Context
import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.Ping
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

    suspend fun onMessage(context: Context, id: String, text: String) {
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
            is Ping -> {
                val forMe = message.fromDeviceId != prefs.deviceId && message.toProfile == prefs.deviceProfile
                if (forMe && System.currentTimeMillis() - message.at < 30 * 60_000L) Notifier.showPing(context, message)
            }
        }
        prefs.liveSince = id
    }

    private fun onReaction(context: Context, r: Reaction) {
        val prefs = context.graph.prefs
        if (prefs.reactions.any { it.id == r.id }) return
        prefs.reactions = prefs.reactions + r
        val forMe = r.fromDeviceId != prefs.deviceId &&
            (r.toDeviceId == prefs.deviceId || (r.toProfile != null && r.toProfile == prefs.deviceProfile))
        val fresh = System.currentTimeMillis() - r.at < 10 * 60_000L
        if (forMe && fresh) Notifier.showReaction(context, r)
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
        return send(context, Social.encode(reaction))
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
        return send(context, Social.encode(vote))
    }

    private suspend fun send(context: Context, text: String): Boolean {
        val relay = LiveHub.relay(context) ?: return false
        return runCatching { relay.publishText(text) }.isSuccess
    }
}
