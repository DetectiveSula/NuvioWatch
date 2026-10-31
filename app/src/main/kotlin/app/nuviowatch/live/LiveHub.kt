package app.nuviowatch.live

import android.content.Context
import app.nuviowatch.BuildConfig
import app.nuviowatch.core.FamilyDb
import app.nuviowatch.core.LiveChannel
import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.LiveRelay
import app.nuviowatch.core.Profile
import app.nuviowatch.core.TitleInfo
import app.nuviowatch.data.Snapshot
import app.nuviowatch.graph

/** Shared helpers for live playback reports. */
object LiveHub {
    private var cached: Pair<String, LiveRelay>? = null
    private var cachedDb: Pair<String, FamilyDb>? = null

    /** The family's shared database, or null until signed in. */
    @Synchronized
    fun db(context: Context): FamilyDb? {
        val userId = context.graph.prefs.session?.userId?.takeIf { it.isNotBlank() } ?: return null
        cachedDb?.let { (id, db) -> if (id == userId) return db }
        return FamilyDb(BuildConfig.FAMILY_DB_URL, BuildConfig.FAMILY_DB_KEY, userId, context.graph.http).also { cachedDb = userId to it }
    }

    /** Best effort: store a message so phones that were off can catch up later. */
    suspend fun store(context: Context, id: String, kind: String, text: String, at: Long) {
        runCatching { db(context)?.add(id, kind, text, at) }
            .onFailure { context.graph.prefs.setStatus("database", "Couldn't save: ${it.message}") }
            .onSuccess { context.graph.prefs.setStatus("database", "Saved $kind") }
    }

    /** The account's relay, or null until signed in. */
    @Synchronized
    fun relay(context: Context): LiveRelay? {
        val userId = context.graph.prefs.session?.userId?.takeIf { it.isNotBlank() } ?: return null
        cached?.let { (id, relay) -> if (id == userId) return relay }
        return LiveRelay(LiveChannel(userId), context.graph.http).also { cached = userId to it }
    }

    /**
     * Who is watching: the device's assigned profile, else the profile whose Nuvio
     * history has a title with this name (most recent first).
     */
    fun profileFor(event: LiveEvent, snapshot: Snapshot, titles: Map<String, TitleInfo>): Profile? {
        event.profileIndex?.let { index -> snapshot.profiles.firstOrNull { it.index == index }?.let { return it } }
        val ids = titleIds(event.title, titles)
        if (ids.isEmpty()) return null
        return snapshot.profiles
            .mapNotNull { p ->
                val latest = snapshot.progress[p.index].orEmpty().filter { it.contentId in ids }.maxOfOrNull { it.lastWatchedMs }
                    ?: snapshot.watched[p.index].orEmpty().filter { it.contentId in ids }.maxOfOrNull { it.watchedAtMs }
                latest?.let { p to it }
            }
            .maxByOrNull { it.second }
            ?.first
    }

    /** The catalogue id for a media-session title, matched by name. */
    fun titleIdFor(event: LiveEvent, titles: Map<String, TitleInfo>): String? = titleIds(event.title, titles).firstOrNull()

    /** Title details for a media-session title, matched by name. */
    fun titleFor(event: LiveEvent, titles: Map<String, TitleInfo>): TitleInfo? =
        titleIds(event.title, titles).firstNotNullOfOrNull { titles[it] }

    private fun titleIds(title: String, titles: Map<String, TitleInfo>): Set<String> {
        val wanted = normalize(title)
        if (wanted.isEmpty()) return emptySet()
        return titles.filterValues { normalize(it.name) == wanted || wanted.startsWith(normalize(it.name) + " ") }.keys
    }

    private fun normalize(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
