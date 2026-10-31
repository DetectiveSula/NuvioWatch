package app.nuviowatch.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuviowatch.core.Fun
import app.nuviowatch.core.Wrapped
import app.nuviowatch.core.WrappedPlus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/** Wrapped for the whole year, instead of a month. */
const val YEAR = -1

private val Purple = Color(0xFF5B3FD1)
private val Pink = Color(0xFFC2364F)
private val Green = Color(0xFF1F8A70)
private val Orange = Color(0xFFB8690A)
private val Blue = Color(0xFF2B6CD4)
private val Plum = Color(0xFF9B2C8F)
private val PosterColors = listOf(Color(0xFF2B6CD4), Color(0xFFFF5C7A), Color(0xFF1F8A70), Color(0xFF9B2C8F), Color(0xFFE8890C))

private enum class Kind { OPENING, HOURS, TOP, CAST, QUIZ, PRIME, AWARDS, CARD }

private class Data(val w: Wrapped, val plus: WrappedPlus, val label: String, val year: Boolean)

@Composable
fun WrappedStory(monthsAgo: Int, onClose: () -> Unit) {
    val viewings by Model.viewings.collectAsState()
    val titles by Model.titles.collectAsState()
    val saved by Model.store.saved.collectAsState()
    val year = monthsAgo == YEAR
    val start = if (year) LocalDate.now().withDayOfYear(1) else LocalDate.now().minusMonths(monthsAgo.toLong()).withDayOfMonth(1)
    val end = if (year) start.plusYears(1) else start.plusMonths(1)
    val label = if (year) start.year.toString() else start.month.getDisplayName(DateTextStyle.FULL, Locale.getDefault())
    val data = remember(viewings, titles, monthsAgo) {
        Fun.wrapped(viewings, titles, start, end, label)?.let { w ->
            val byName = Model.snapshot.value.profiles.associate { it.name.lowercase() to it.index }
            val reactions = saved.reactions.filter { r -> Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()).toLocalDate().let { !it.isBefore(start) && it.isBefore(end) } }
                .mapNotNull { byName[it.fromName.lowercase()] }.groupingBy { it }.eachCount()
            Data(w, Fun.wrappedPlus(viewings, titles, start, end, System.currentTimeMillis(), emptyList(), reactions), label, year)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF050409)), contentAlignment = Alignment.Center) {
        Text("✕  Close", color = TextMuted, modifier = Modifier.align(Alignment.TopEnd).padding(20.dp).clickable(onClick = onClose))
        if (data == null) {
            Text("Nothing watched in $label yet", style = display(26))
            return@Box
        }
        val slides = remember(data) {
            buildList {
                add(Kind.OPENING); add(Kind.HOURS)
                if (data.plus.topTitles.isNotEmpty()) add(Kind.TOP)
                if (data.plus.personas.isNotEmpty()) add(Kind.CAST)
                if (data.plus.quiz != null) add(Kind.QUIZ)
                if (data.plus.peak != null) add(Kind.PRIME)
                if (data.plus.awards.isNotEmpty()) add(Kind.AWARDS)
                add(Kind.CARD)
            }
        }
        var page by remember { mutableIntStateOf(0) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 44.sp, color = if (page > 0) Color.White else Color(0x33FFFFFF), modifier = Modifier.clickable { if (page > 0) page-- }.padding(20.dp))
            Box(Modifier.fillMaxHeight(0.94f).aspectRatio(0.5f).clip(RoundedCornerShape(26.dp)).clickable { if (page < slides.lastIndex) page++ }) {
                when (slides[page]) {
                    Kind.OPENING -> Opening(data)
                    Kind.HOURS -> Hours(data)
                    Kind.TOP -> TopShows(data)
                    Kind.CAST -> Cast(data)
                    Kind.QUIZ -> Quiz(data)
                    Kind.PRIME -> Prime(data)
                    Kind.AWARDS -> Awards(data)
                    Kind.CARD -> Card(data)
                }
                Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    slides.indices.forEach { i -> Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(if (i <= page) Color.White else Color.White.copy(alpha = 0.3f))) }
                }
            }
            Text("›", fontSize = 44.sp, color = if (page < slides.lastIndex) Color.White else Color(0x33FFFFFF), modifier = Modifier.clickable { if (page < slides.lastIndex) page++ }.padding(20.dp))
        }
    }
}

@Composable
private fun Slide(top: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to top, 0.72f to Ink)).padding(start = 22.dp, end = 22.dp, top = 40.dp, bottom = 24.dp), content = content)
}

@Composable
private fun Label(text: String, color: Color = Color.White.copy(alpha = 0.8f)) = Text(text.uppercase(), style = Eyebrow, color = color)

@Composable
private fun Heading(text: String, size: Int = 34) = Text(text, style = display(size), color = Color.White)

@Composable
private fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Glass).border(1.dp, GlassBorder, shape).padding(14.dp), content = content)
}

@Composable
private fun PosterTile(id: String?, width: Dp?, n: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val url = id?.let { Model.titles.value[it]?.poster }
    Box(modifier.then(if (width != null) Modifier.width(width) else Modifier).aspectRatio(2f / 3f).clip(shape).background(PosterColors[n % PosterColors.size]).border(1.dp, Color(0x24FFFFFF), shape)) {
        if (url != null) RemoteImage(url, Modifier.matchParentSize())
    }
}

private fun name(i: Int) = Model.profileName(i)
private fun profile(i: Int) = Model.snapshot.value.profiles.firstOrNull { it.index == i }
private fun hours(ms: Long) = ms / 3_600_000
private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH)).lowercase()

@Composable
private fun Opening(d: Data) = Slide(Purple) {
    Box(Modifier.fillMaxWidth().weight(1f)) {
        val ids = d.plus.topTitles.map { it.contentId } + List(3) { null }
        PosterTile(ids[1], 92.dp, 0, Modifier.align(Alignment.CenterStart).rotate(-9f))
        PosterTile(ids[0], 112.dp, 1, Modifier.align(Alignment.Center).offset(y = (-20).dp).rotate(4f))
        PosterTile(ids[2], 92.dp, 2, Modifier.align(Alignment.CenterEnd).offset(y = 10.dp).rotate(11f))
    }
    Label("The Family Wrapped", Accent)
    Text(d.label, style = display(if (d.label.length <= 4) 84 else (420 / d.label.length).coerceAtMost(60)), color = Color.White, maxLines = 1, softWrap = false)
    Text("${hours(d.w.totalMs)} hours. ${d.w.perProfile.size} people. One sofa.\nLet's see what you lot got up to.", fontSize = 17.sp, lineHeight = 24.sp)
}

@Composable
private fun Hours(d: Data) = Slide(Purple) {
    val h = hours(d.w.totalMs)
    Label("Together you watched")
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$h", style = display(96), color = Color.White)
        Text(" hours", style = display(24), color = Color.White, modifier = Modifier.padding(bottom = 16.dp))
    }
    Text(if (h >= 4) "Enough to watch ${h / 2} films back to back." else "Just getting started.", fontSize = 17.sp)
    Spacer(Modifier.weight(1f))
    val most = d.w.perProfile.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
    d.w.perProfile.take(6).forEachIndexed { n, (i, ms) ->
        Row { Text(name(i), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(formatDuration(ms), fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(4.dp))
        ThinProgress(ms.toFloat() / most, if (n == 0) Accent else TextPrimary, 6.dp, Color(0x1AFFFFFF))
        Spacer(Modifier.height(10.dp))
    }
    d.plus.previousMs?.let { prev ->
        val change = ((d.w.totalMs - prev) * 100 / prev).toInt()
        Glass { Text(if (change >= 0) "Up $change% on the ${if (d.year) "year" else "month"} before." else "Down ${-change}% on the ${if (d.year) "year" else "month"} before.", fontSize = 14.sp) }
    }
}

@Composable
private fun TopShows(d: Data) = Slide(Pink) {
    Label("The family's top 3")
    Heading("The shows that ruled the house")
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 0, 2).forEach { rank ->
            val t = d.plus.topTitles.getOrNull(rank)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (t != null) {
                    if (rank == 0) Text("👑", fontSize = 22.sp)
                    PosterTile(t.contentId, if (rank == 0) 86.dp else 72.dp, rank)
                    Text(Model.titles.value[t.contentId]?.name ?: t.contentId, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp))
                }
                val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
                Column(
                    Modifier.fillMaxWidth().height(when (rank) { 0 -> 130.dp; 1 -> 96.dp; else -> 70.dp }).clip(shape)
                        .background(if (rank == 0) Gold.copy(alpha = 0.14f) else Glass).border(1.dp, if (rank == 0) Gold.copy(alpha = 0.4f) else GlassBorder, shape).padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${rank + 1}", style = display(if (rank == 0) 36 else 28), color = if (rank == 0) Gold else Color.White)
                    t?.let { Text(formatDuration(it.ms), fontSize = 11.sp, color = TextMuted) }
                }
            }
        }
    }
    d.w.longestBinge?.let { (i, id, n) ->
        Spacer(Modifier.height(12.dp))
        Glass { Text("Biggest binge: ${name(i)} watched $n episodes of ${Model.titles.value[id]?.name ?: id} in one day.", fontSize = 14.sp) }
    }
}

@Composable
private fun Cast(d: Data) = Slide(Green) {
    Label("Your watching personalities")
    Heading("Meet the cast")
    Spacer(Modifier.height(14.dp))
    d.plus.personas.take(4).chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().weight(1f).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { p ->
                Glass(Modifier.weight(1f).fillMaxHeight()) {
                    Avatar(profile(p.profileIndex), 38.dp, name(p.profileIndex))
                    Text("${name(p.profileIndex)} is", fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 6.dp))
                    Text(p.personality.title, style = display(19), color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Text(p.stat, fontSize = 12.sp)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Quiz(d: Data) = Slide(Orange) {
    val quiz = d.plus.quiz ?: return@Slide
    var picked by remember { mutableStateOf<Int?>(null) }
    Label("Quick quiz")
    Heading("Who watched the most ${quiz.genre.lowercase()}?", 30)
    Spacer(Modifier.height(10.dp))
    val chosen = picked
    if (chosen == null) {
        Text("Everyone guesses, then click a name.", fontSize = 14.sp)
    } else {
        val second = quiz.counts.filterKeys { it != quiz.answer }.values.maxOrNull() ?: 0
        Glass {
            Text(if (chosen == quiz.answer) "Correct!" else "Not quite!", style = display(18), color = Color.White)
            Text("It was ${name(quiz.answer)}: ${quiz.counts[quiz.answer] ?: 0} watches. Nobody else got past $second.", fontSize = 13.sp)
        }
    }
    Spacer(Modifier.weight(1f))
    d.w.perProfile.map { it.first }.forEach { i ->
        val right = i == quiz.answer
        val shape = RoundedCornerShape(20.dp)
        val bg = when { chosen == null -> Glass; right -> Color.White; i == chosen -> LiveRed.copy(alpha = 0.14f); else -> Color(0x08FFFFFF) }
        val fg = when { chosen != null && right -> Ink; chosen != null && i != chosen -> Color(0xFF8F89A3); else -> TextPrimary }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = 50.dp).clip(shape).background(bg).border(1.dp, if (chosen != null && i == chosen && !right) LiveRed else GlassBorder, shape)
                .clickable { picked = if (picked == null) i else null }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(profile(i), 32.dp, name(i))
            Spacer(Modifier.width(12.dp))
            Text(name(i), fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.weight(1f))
            if (chosen != null && (right || i == chosen)) Text(if (right) "${quiz.counts[i] ?: 0}" else "Nope", fontWeight = FontWeight.Bold, color = fg)
        }
    }
}

@Composable
private fun Prime(d: Data) = Slide(Blue) {
    val (day, band) = d.plus.peak ?: return@Slide
    val dayName = DayOfWeek.of(day).getDisplayName(DateTextStyle.FULL, Locale.getDefault())
    Label("Family prime time")
    Heading(if (band in 2..4) "${dayName}s, ${WrappedPlus.BAND_NAMES[band]}" else "$dayName ${WrappedPlus.BAND_NAMES[band]}", 36)
    Text("The busiest slot in the house" + if (d.plus.peakWeeks >= 2) ", on ${d.plus.peakWeeks} different weeks." else ".", fontSize = 14.sp)
    Spacer(Modifier.weight(1f))
    val max = d.plus.heat.flatten().maxOrNull()?.coerceAtLeast(1) ?: 1
    Glass {
        WrappedPlus.BANDS.forEachIndexed { b, n ->
            Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n, fontSize = 11.sp, color = TextMuted, modifier = Modifier.width(36.dp))
                (0 until 7).forEach { dd ->
                    val v = d.plus.heat[dd][b].toFloat() / max
                    val peak = dd + 1 == day && b == band
                    val fill = when { peak -> Color.White; v == 0f -> Color(0x0FFFFFFF); v < 0.34f -> Accent.copy(alpha = 0.3f); v < 0.67f -> Accent.copy(alpha = 0.6f); else -> Accent }
                    Box(Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(6.dp)).background(fill).then(if (peak) Modifier.border(2.dp, Gold, RoundedCornerShape(6.dp)) else Modifier))
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        d.plus.latest?.let { (i, at) -> Glass(Modifier.weight(1f)) { Text("Latest night", fontSize = 11.sp, color = TextMuted); Text(clock(at), style = display(18)); Text(name(i), fontSize = 11.sp) } }
        d.plus.earliest?.let { (i, at) -> Glass(Modifier.weight(1f)) { Text("Earliest start", fontSize = 11.sp, color = TextMuted); Text(clock(at), style = display(18)); Text(name(i), fontSize = 11.sp) } }
    }
}

@Composable
private fun Awards(d: Data) = Slide(Plum) {
    Label("The Nuvio Awards · ${d.label}")
    Heading("And the winner is…")
    Spacer(Modifier.height(14.dp))
    d.plus.awards.forEach { a ->
        Glass(Modifier.padding(bottom = 8.dp)) {
            Text(a.title, fontSize = 12.sp, color = TextMuted)
            Text("${name(a.profileIndex)} · ${a.value}", style = display(17), color = Color.White)
        }
    }
}

@Composable
private fun Card(d: Data) {
    Column(Modifier.fillMaxSize().background(Ink).padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 20.dp)) {
        Label("That's a wrap", TextMuted)
        Spacer(Modifier.height(10.dp))
        val shape = RoundedCornerShape(20.dp)
        Column(Modifier.fillMaxWidth().weight(1f).clip(shape).background(Brush.verticalGradient(listOf(Purple, Color(0xFF1A1438)))).border(1.dp, Color(0x1FFFFFFF), shape).padding(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Label("The Family Wrapped")
                Spacer(Modifier.weight(1f))
                Text(d.label, style = display(if (d.label.length <= 4) 36 else 24), color = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until 3).forEach { n -> Box(Modifier.weight(1f)) { PosterTile(d.plus.topTitles.getOrNull(n)?.contentId, null, n + 1, Modifier.fillMaxWidth()) } }
            }
            Spacer(Modifier.height(14.dp))
            Row {
                Column(Modifier.weight(1f)) { Text("Hours together", fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f)); Text("${hours(d.w.totalMs)}", style = display(26)) }
                Column(Modifier.weight(1f)) { Text("Titles", fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f)); Text("${d.w.titles}", style = display(26)) }
            }
            Spacer(Modifier.weight(1f))
            d.plus.personas.take(5).forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(name(p.profileIndex), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(p.personality.title, fontSize = 13.sp)
                }
            }
        }
    }
}
