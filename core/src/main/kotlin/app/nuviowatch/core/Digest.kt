package app.nuviowatch.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DigestPeriod { DAY, WEEK }

/** A recap notification: a headline, a one-line summary and a few detail lines. */
data class Digest(
    val title: String,
    val summary: String,
    val lines: List<String>,
)

/** Builds the daily and weekly family recaps from what everyone watched. */
object DigestBuilder {
    /**
     * Returns null when nothing was watched in the period.
     * [names] maps profile index to name; [titleName] gives a title's display name.
     */
    fun build(
        period: DigestPeriod,
        viewings: List<Viewing>,
        names: Map<Int, String>,
        titleName: (String) -> String?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Digest? {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val from = when (period) {
            DigestPeriod.DAY -> today
            DigestPeriod.WEEK -> today.minusDays(6)
        }
        val inPeriod = viewings.filter { it.atMs > 0 && !dateOf(it.atMs, zone).isBefore(from) && it.watchedMs > 0 }
        if (inPeriod.isEmpty()) return null

        fun name(index: Int) = names[index] ?: "Profile $index"
        fun title(id: String, fallback: String) = titleName(id) ?: fallback

        val total = inPeriod.sumOf { it.watchedMs }
        val perProfile = inPeriod.groupBy { it.profileIndex }.mapValues { (_, vs) -> vs.sumOf { it.watchedMs } }
            .entries.sortedByDescending { it.value }
        val topTitle = inPeriod.groupBy { it.contentId }.maxBy { (_, vs) -> vs.sumOf { it.watchedMs } }
        val topTitleName = title(topTitle.key, topTitle.value.first().fallbackTitle)

        val summary = perProfile.take(4).joinToString(" · ") { (index, ms) -> "${name(index)} ${short(ms)}" }

        return when (period) {
            DigestPeriod.DAY -> Digest(
                title = "Today: ${short(total)} watched",
                summary = summary,
                lines = buildList {
                    perProfile.forEach { (index, ms) ->
                        val what = inPeriod.filter { it.profileIndex == index }.groupBy { it.contentId }
                            .maxBy { (_, vs) -> vs.sumOf { it.watchedMs } }
                        add("${name(index)}: ${short(ms)} · ${title(what.key, what.value.first().fallbackTitle)}")
                    }
                },
            )
            DigestPeriod.WEEK -> {
                val (topIndex, topMs) = perProfile.first()
                val binge = inPeriod.filter { it.series }
                    .groupBy { it.profileIndex to it.contentId }
                    .maxByOrNull { it.value.size }
                    ?.takeIf { it.value.size >= 3 }
                val nightOwl = inPeriod
                    .map { it to Instant.ofEpochMilli(it.atMs).atZone(zone) }
                    .filter { (_, at) -> at.hour >= 23 || at.hour < 4 }
                    .maxByOrNull { (_, at) -> (at.hour + 24 - 4) % 24 * 60 + at.minute } // latest past 4am-start day
                Digest(
                    title = "This week: ${short(total)} watched",
                    summary = summary,
                    lines = buildList {
                        add("🏆 Top watcher: ${name(topIndex)} (${short(topMs)})")
                        add("📺 Show of the week: $topTitleName")
                        binge?.let { (key, vs) ->
                            add("🍿 Binge of the week: ${name(key.first)}, ${vs.size} episodes of ${title(key.second, vs.first().fallbackTitle)}")
                        }
                        nightOwl?.let { (v, at) ->
                            add("🌙 Night owl: ${name(v.profileIndex)}, still watching at %02d:%02d".format(at.hour, at.minute))
                        }
                    },
                )
            }
        }
    }

    private fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private fun short(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60}h ${"%02d".format(minutes % 60)}m" else "${minutes}m"
    }
}
