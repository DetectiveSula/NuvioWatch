package app.nuviowatch.data

import app.nuviowatch.core.Fun
import app.nuviowatch.core.StatsCalculator
import app.nuviowatch.core.TitleInfo
import app.nuviowatch.core.Viewing
import app.nuviowatch.core.episodeLabel
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate

/** The summary phones upload for the web dashboard (encrypted before it leaves the phone). */
object Dashboard {
    fun build(snapshot: Snapshot, titles: Map<String, TitleInfo>, viewings: List<Viewing>, liveTime: Map<Int, Long>, nowMs: Long, activeDays: Map<Int, Set<LocalDate>> = emptyMap()): String {
        val indexes = snapshot.profiles.map { it.index }
        val board = Fun.leaderboard(viewings, indexes, nowMs, activeDays = activeDays)
        val champion = Fun.champion(viewings, indexes, nowMs, activeDays = activeDays)
        val month = LocalDate.now().withDayOfMonth(1)
        val wrapped = Fun.wrapped(viewings, titles, month, month.plusMonths(1), month.month.name.lowercase().replaceFirstChar { it.uppercase() })
        val stats30 = StatsCalculator.compute(viewings, titles, nowMs, sinceMs = nowMs - 30 * 86_400_000L)

        return buildJsonObject {
            put("updatedAt", nowMs)
            putJsonArray("profiles") {
                snapshot.profiles.forEach { p ->
                    val mine = viewings.filter { it.profileIndex == p.index }
                    addJsonObject {
                        put("index", p.index)
                        put("name", p.name)
                        put("color", p.avatarColorHex)
                        p.avatarUrl?.let { put("avatar", it) }
                        put("streak", Fun.streaks(mine, nowMs, extraDays = activeDays[p.index].orEmpty()).first)
                        put("personality", Fun.personality(mine).title)
                        put("badges", Fun.badges(mine, titles, nowMs).count { it.earned })
                        liveTime[p.index]?.let { put("liveWeekMs", it) }
                    }
                }
            }
            putJsonArray("leaderboard") {
                board.forEach { e ->
                    addJsonObject {
                        put("index", e.profileIndex)
                        put("points", e.points)
                        put("watchedMs", e.watchedMs)
                    }
                }
            }
            champion?.let { put("champion", it) }
            putJsonObject("month") {
                put("label", wrapped?.label ?: "")
                put("totalMs", wrapped?.totalMs ?: 0)
                put("episodes", wrapped?.episodes ?: 0)
                put("films", wrapped?.films ?: 0)
                wrapped?.topWatcher?.let { put("topWatcher", it.first) }
                wrapped?.topTitle?.let { (id, ms) ->
                    put("topTitle", titles[id]?.name ?: id)
                    titles[id]?.poster?.let { put("topPoster", it) }
                    put("topTitleMs", ms)
                }
            }
            putJsonArray("topTitles") {
                stats30.topTitles.take(8).forEach { t ->
                    addJsonObject {
                        put("name", titles[t.contentId]?.name ?: t.contentId)
                        titles[t.contentId]?.poster?.let { put("poster", it) }
                        put("ms", t.watchedMs)
                    }
                }
            }
            putJsonArray("recent") {
                viewings.filter { it.atMs > 0 }.sortedByDescending { it.atMs }.take(25).forEach { v ->
                    addJsonObject {
                        put("profile", v.profileIndex)
                        put("title", titles[v.contentId]?.name ?: v.fallbackTitle)
                        titles[v.contentId]?.poster?.let { put("poster", it) }
                        episodeLabel(v.season, v.episode)?.let { put("episode", it) }
                        put("at", v.atMs)
                        put("done", v.completed)
                        put("fraction", v.fraction.toDouble())
                    }
                }
            }
            putJsonArray("genres") { stats30.topGenres.take(6).forEach { add(it.first) } }
        }.toString()
    }
}
