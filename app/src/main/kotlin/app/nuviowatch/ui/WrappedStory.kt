package app.nuviowatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import app.nuviowatch.core.Fun
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import app.nuviowatch.core.Wrapped
import app.nuviowatch.core.WrappedPlus
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/** Wrapped for the whole year, instead of a month. */
internal const val YEAR = -1

// The same colours as the rest of the app's story slides.
private val Purple = Color(0xFF5B3FD1)
private val Pink = Color(0xFFC2364F)
private val Green = Color(0xFF1F8A70)
private val Orange = Color(0xFFB8690A)
private val Blue = Color(0xFF2B6CD4)
private val Plum = Color(0xFF9B2C8F)
private val Gold = Color(0xFFFFD166)
private val CardFill = Color(0x0FFFFFFF)
private val CardLine = Color(0x14FFFFFF)
private val PosterColors = listOf(Color(0xFF2B6CD4), Color(0xFFFF5C7A), Color(0xFF1F8A70), Color(0xFF9B2C8F), Color(0xFFE8890C))

private enum class Kind { OPENING, HOURS, TOP, CAST, QUIZ, PRIME, AWARDS, CARD }

private data class Data(val w: Wrapped, val plus: WrappedPlus, val label: String, val year: Boolean)

@Composable
internal fun WrappedStory(state: UiState, monthsAgo: Int, onClose: () -> Unit) {
    val year = monthsAgo == YEAR
    val start = if (year) LocalDate.now().withDayOfYear(1) else LocalDate.now().minusMonths(monthsAgo.toLong()).withDayOfMonth(1)
    val end = if (year) start.plusYears(1) else start.plusMonths(1)
    val label = if (year) start.year.toString() else start.month.getDisplayName(DateTextStyle.FULL, Locale.getDefault())
    val data = remember(state.viewings, state.titles, state.social.ratings, state.social.reactions, monthsAgo) {
        Fun.wrapped(state.viewings, state.titles, start, end, label)?.let { w ->
            val byName = state.snapshot.profiles.associate { it.name.lowercase() to it.index }
            val reactions = state.social.reactions
                .filter { r -> Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()).toLocalDate().let { !it.isBefore(start) && it.isBefore(end) } }
                .mapNotNull { byName[it.fromName.lowercase()] }.groupingBy { it }.eachCount()
            val plus = Fun.wrappedPlus(state.viewings, state.titles, start, end, state.clock, state.social.ratings, reactions)
            Data(w, plus, label, year)
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (data == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing watched in $label yet", style = display(26), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text("Tap to go back", color = TextMuted, modifier = Modifier.clickable(onClick = onClose).padding(12.dp))
            }
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
        val pager = rememberPagerState { slides.size }
        val scope = rememberCoroutineScope()
        val handles = slides.map { rememberShareHandle() }
        fun go(to: Int) {
            if (to > slides.lastIndex) onClose() else scope.launch { pager.animateScrollToPage(to.coerceAtLeast(0)) }
        }

        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { i ->
            Box(
                Modifier.fillMaxSize().pointerInput(i) {
                    // Tap the left third to go back, anywhere else to go on.
                    detectTapGestures { offset -> go(if (offset.x < size.width / 3f) i - 1 else i + 1) }
                },
            ) {
                Box(Modifier.fillMaxSize().capturable(handles[i])) {
                    when (slides[i]) {
                        Kind.OPENING -> Opening(state, data)
                        Kind.HOURS -> Hours(state, data)
                        Kind.TOP -> TopShows(state, data)
                        Kind.CAST -> Cast(state, data)
                        Kind.QUIZ -> Quiz(state, data)
                        Kind.PRIME -> PrimeTime(state, data)
                        Kind.AWARDS -> Awards(state, data)
                        Kind.CARD -> ShareCard(state, data)
                    }
                }
                if (slides[i] == Kind.CARD) {
                    Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(50)).background(Color.White), contentAlignment = Alignment.Center) {
                                ShareButton(handles[i], "Share to family chat", "Our Family Wrapped · ${data.label}", color = Ink)
                            }
                            Box(
                                Modifier.size(52.dp).clip(CircleShape).background(CardFill).border(1.dp, CardLine, CircleShape)
                                    .clickable(role = Role.Button, onClickLabel = "Watch again") { go(0) },
                                contentAlignment = Alignment.Center,
                            ) { Text("↺", fontSize = 22.sp, color = Color.White) }
                        }
                    }
                }
            }
        }

        // Story progress bars and controls.
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(slides.size) { i ->
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(if (i <= pager.currentPage) Color.White else Color.White.copy(alpha = 0.3f)))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("NUVIO WATCH", style = Eyebrow, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                if (slides[pager.currentPage] != Kind.CARD) ShareButton(handles[pager.currentPage], "Share", "Our Family Wrapped · ${data.label}")
                IconButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 20.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------- Building blocks

@Composable
private fun Slide(top: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to top, 0.72f to Ink))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 76.dp, bottom = 32.dp),
        content = content,
    )
}

@Composable
private fun Label(text: String, color: Color = Color.White.copy(alpha = 0.8f)) =
    Text(text.uppercase(), style = Eyebrow, color = color)

@Composable
private fun Heading(text: String, size: Int = 40) =
    Text(text, style = display(size), color = Color.White, lineHeight = (size * 1.05f).sp)

@Composable
private fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(CardFill).border(1.dp, CardLine, shape).padding(16.dp), content = content)
}

@Composable
private fun Avatar(state: UiState, index: Int, size: Dp) {
    val profile = state.snapshot.profiles.firstOrNull { it.index == index }
    if (profile != null) {
        ProfileAvatar(profile, size = size)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(Purple), contentAlignment = Alignment.Center) {
            Text(state.profileName(index).take(1), fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
private fun PosterTile(state: UiState, id: String?, width: Dp?, n: Int, modifier: Modifier = Modifier) {
    val url = id?.let { state.titles[it]?.poster }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.then(if (width != null) Modifier.width(width) else Modifier).aspectRatio(2f / 3f).clip(shape)
            .background(PosterColors[n % PosterColors.size]).border(1.dp, Color(0x24FFFFFF), shape),
    ) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

private fun hours(ms: Long) = ms / 3_600_000

private fun clock(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH)).lowercase()

// ---------------------------------------------------------------- 1 · Opening

@Composable
private fun Opening(state: UiState, d: Data) {
    val people = d.w.perProfile.size
    Slide(Purple) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val ids = d.plus.topTitles.map { it.contentId } + List(5) { null }
            PosterTile(state, ids[1], 112.dp, 0, Modifier.align(Alignment.CenterStart).offset(y = (-10).dp).rotate(-9f))
            PosterTile(state, ids[0], 136.dp, 1, Modifier.align(Alignment.Center).offset(y = (-30).dp).rotate(4f))
            PosterTile(state, ids[2], 112.dp, 2, Modifier.align(Alignment.CenterEnd).offset(y = 10.dp).rotate(11f))
        }
        Label("The Family Wrapped", Accent)
        Text(d.label, style = display(if (d.label.length <= 4) 104 else 64), color = Color.White, lineHeight = if (d.label.length <= 4) 96.sp else 64.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "${hours(d.w.totalMs)} hours. $people ${if (people == 1) "person" else "people"}. One sofa.\nLet's see what you lot got up to.",
            fontSize = 20.sp, color = TextPrimary, lineHeight = 28.sp,
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tap to start", fontSize = 13.sp, color = TextMuted, modifier = Modifier.weight(1f))
            d.w.perProfile.take(5).forEachIndexed { n, (i, _) ->
                Box(Modifier.offset(x = (-8 * n).dp)) { Avatar(state, i, 32.dp) }
            }
        }
    }
}

// ---------------------------------------------------------------- 2 · Hours

@Composable
private fun Hours(state: UiState, d: Data) {
    val total = d.w.totalMs
    val h = hours(total)
    val compare = when {
        h >= 48 -> "That's ${h / 24} whole days. Enough to watch ${h / 2} films back to back."
        h >= 4 -> "Enough to watch ${h / 2} films back to back."
        else -> "Just getting started."
    }
    val most = d.w.perProfile.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
    Slide(Purple) {
        Label("Together you watched")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (h > 0) "$h" else "${total / 60_000}", style = display(120), color = Color.White, lineHeight = 112.sp)
            Text(if (h > 0) " hours" else " min", style = display(28), color = Color.White, modifier = Modifier.padding(bottom = 18.dp))
        }
        Text(compare, fontSize = 20.sp, color = Color.White, lineHeight = 28.sp)
        Spacer(Modifier.weight(1f))
        Label("Who put in the hours", TextMuted)
        Spacer(Modifier.height(14.dp))
        d.w.perProfile.take(6).forEachIndexed { n, (i, ms) ->
            Row(Modifier.fillMaxWidth()) {
                Text(state.profileName(i), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(formatDuration(ms), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(6.dp))
            ThinProgress(ms.toFloat() / most, if (n == 0) Accent else TextPrimary, height = 6.dp, track = Color(0x1AFFFFFF))
            Spacer(Modifier.height(14.dp))
        }
        val jab = buildString {
            d.plus.previousMs?.let { prev ->
                val change = ((total - prev) * 100 / prev).toInt()
                append(if (change >= 0) "Up $change% on the ${if (d.year) "year" else "month"} before. " else "Down ${-change}% on the ${if (d.year) "year" else "month"} before. ")
            }
            val p = d.w.perProfile
            if (p.size >= 3 && p[0].second > p[p.size - 1].second + p[p.size - 2].second) {
                append("${state.profileName(p[0].first)} alone out-watched ${state.profileName(p[p.size - 2].first)} and ${state.profileName(p[p.size - 1].first)} combined.")
            } else if (p.isNotEmpty()) {
                append("${state.profileName(p[0].first)} watched the most.")
            }
        }
        Glass { Text(jab, fontSize = 15.sp, lineHeight = 21.sp) }
    }
}

// ---------------------------------------------------------------- 3 · Top shows podium

@Composable
private fun TopShows(state: UiState, d: Data) {
    val top = d.plus.topTitles
    val everyone = d.w.perProfile.size
    Slide(Pink) {
        Label(if (top.size >= 3) "The family's top 3" else "The family's favourites")
        Heading("The shows that ruled the house")
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Podium order: 2nd, 1st, 3rd.
            listOf(1, 0, 2).forEach { rank ->
                val t = top.getOrNull(rank)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (t != null) {
                        if (rank == 0) Text("👑", fontSize = 26.sp)
                        PosterTile(state, t.contentId, if (rank == 0) 104.dp else 90.dp, rank)
                        Spacer(Modifier.height(8.dp))
                        Text(state.titles[t.contentId]?.name ?: t.contentId, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                    }
                    val step = when (rank) { 0 -> 160.dp; 1 -> 116.dp; else -> 84.dp }
                    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                    Column(
                        Modifier.fillMaxWidth().height(step).clip(shape)
                            .background(if (rank == 0) Gold.copy(alpha = 0.14f) else CardFill)
                            .border(1.dp, if (rank == 0) Gold.copy(alpha = 0.4f) else CardLine, shape)
                            .padding(top = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${rank + 1}", style = display(if (rank == 0) 44 else 34), color = if (rank == 0) Gold else Color.White)
                        if (t != null) {
                            Text(
                                formatDuration(t.ms) + if (rank == 0 && t.watchers == everyone && everyone > 1) " · all $everyone of you" else "",
                                fontSize = 12.sp, color = if (rank == 0) TextPrimary else TextMuted, textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        d.w.longestBinge?.let { (i, id, n) ->
            Glass { Text("Biggest binge: ${state.profileName(i)} watched $n episodes of ${state.titles[id]?.name ?: id} in one day.", fontSize = 15.sp, lineHeight = 21.sp) }
        }
    }
}

// ---------------------------------------------------------------- 4 · Meet the cast

@Composable
private fun Cast(state: UiState, d: Data) {
    val cast = d.plus.personas.take(4)
    Slide(Green) {
        Label("Your watching personalities")
        Heading("Meet the cast")
        Spacer(Modifier.height(20.dp))
        cast.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().weight(1f).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { n, p ->
                    Glass(Modifier.weight(1f).fillMaxHeight().padding(top = if (n == 1) 20.dp else 0.dp)) {
                        Avatar(state, p.profileIndex, 44.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("${state.profileName(p.profileIndex)} is", fontSize = 13.sp, color = TextMuted)
                        Text(p.personality.title, style = display(22), color = Color.White, lineHeight = 24.sp)
                        Spacer(Modifier.weight(1f))
                        Text(p.stat, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (cast.size <= 2) Spacer(Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- 5 · Guess who

@Composable
private fun Quiz(state: UiState, d: Data) {
    val quiz = d.plus.quiz ?: return
    var picked by remember { mutableStateOf<Int?>(null) }
    val people = d.w.perProfile.map { it.first }
    Slide(Orange) {
        Label("Quick quiz")
        Heading("Who watched the most ${quiz.genre.lowercase()} ${if (d.year) "this year" else "in ${d.label}"}?", 36)
        Spacer(Modifier.height(8.dp))
        Text("Pass the phone round. Everyone guesses, then tap.", fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        people.forEach { i ->
            val shape = RoundedCornerShape(22.dp)
            val right = i == quiz.answer
            val (bg, line, fg) = when {
                picked == null -> Triple(CardFill, CardLine, TextPrimary)
                right -> Triple(Color.White, Color.White, Ink)
                i == picked -> Triple(LiveRed.copy(alpha = 0.14f), LiveRed, TextPrimary)
                else -> Triple(Color(0x08FFFFFF), Color(0x0DFFFFFF), Color(0xFF8F89A3))
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).heightIn(min = 64.dp).clip(shape).background(bg).border(1.dp, line, shape)
                    .clickable(role = Role.Button) { picked = if (picked == null) i else null }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(state, i, 40.dp)
                Spacer(Modifier.width(14.dp))
                Text(state.profileName(i), fontWeight = FontWeight.Bold, fontSize = 19.sp, color = fg, modifier = Modifier.weight(1f))
                if (picked != null && (right || i == picked)) {
                    val n = quiz.counts[i] ?: 0
                    Text(if (right) "$n" else "Nope", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = fg)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val chosen = picked
        if (chosen == null) {
            Text("Tap a name to reveal the answer", fontSize = 14.sp, color = TextMuted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        } else {
            val shape = RoundedCornerShape(22.dp)
            val second = quiz.counts.filterKeys { it != quiz.answer }.values.maxOrNull() ?: 0
            Column(Modifier.fillMaxWidth().clip(shape).background(Accent.copy(alpha = 0.14f)).border(1.dp, Accent.copy(alpha = 0.4f), shape).padding(16.dp)) {
                Text(if (chosen == quiz.answer) "Correct! You know your family." else "Not quite!", style = display(20), color = Color.White)
                Text(
                    "It was ${state.profileName(quiz.answer)}: ${quiz.counts[quiz.answer] ?: 0} ${quiz.genre.lowercase()} watches. Nobody else got past $second.",
                    fontSize = 15.sp, lineHeight = 21.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 6 · Prime time

@Composable
private fun PrimeTime(state: UiState, d: Data) {
    val (day, band) = d.plus.peak ?: return
    val dayName = DayOfWeek.of(day).getDisplayName(DateTextStyle.FULL, Locale.getDefault())
    val bandName = WrappedPlus.BAND_NAMES[band]
    val heading = if (band in 2..4) "${dayName}s, $bandName" else "$dayName $bandName"
    val max = d.plus.heat.flatten().maxOrNull()?.coerceAtLeast(1) ?: 1
    Slide(Blue) {
        Label("Family prime time")
        Heading(heading, 44)
        Spacer(Modifier.height(8.dp))
        Text(
            "The busiest slot in the house" + if (d.plus.peakWeeks >= 2) ", on ${d.plus.peakWeeks} different weeks." else ".",
            fontSize = 16.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.weight(1f))
        Glass {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.width(40.dp))
                listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
            WrappedPlus.BANDS.forEachIndexed { b, name ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 12.sp, color = TextMuted, modifier = Modifier.width(40.dp))
                    (0 until 7).forEach { dd ->
                        val v = d.plus.heat[dd][b].toFloat() / max
                        val peak = dd + 1 == day && b == band
                        val fill = when {
                            peak -> Color.White
                            v == 0f -> Color(0x0FFFFFFF)
                            v < 0.34f -> Accent.copy(alpha = 0.3f)
                            v < 0.67f -> Accent.copy(alpha = 0.6f)
                            else -> Accent
                        }
                        Box(
                            Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(8.dp)).background(fill)
                                .then(if (peak) Modifier.border(3.dp, Gold, RoundedCornerShape(8.dp)) else Modifier),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            d.plus.latest?.let { (i, at) -> MiniFact("Latest night", clock(at), state.profileName(i), Modifier.weight(1f)) }
            d.plus.earliest?.let { (i, at) -> MiniFact("Earliest start", clock(at), state.profileName(i), Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun MiniFact(label: String, value: String, who: String, modifier: Modifier) {
    Glass(modifier) {
        Text(label, fontSize = 12.sp, color = TextMuted)
        Text(value, style = display(22), color = Color.White)
        Text(who, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- 7 · Awards

@Composable
private fun Awards(state: UiState, d: Data) {
    Slide(Plum) {
        Label("The Nuvio Awards ${if (d.year) d.label else "· ${d.label}"}")
        Heading("And the winner is…")
        Spacer(Modifier.height(20.dp))
        d.plus.awards.forEach { a ->
            Glass(Modifier.padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when (a.key) {
                        "quitter" -> "🏆" to Gold
                        "late" -> "🌙" to Accent
                        "reactions" -> "❤" to LiveRed
                        "films" -> "🎬" to Accent
                        else -> "★" to Gold
                    }
                    Text(icon, fontSize = 24.sp, color = tint, modifier = Modifier.width(36.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.title, fontSize = 13.sp, color = TextMuted)
                        Text("${state.profileName(a.profileIndex)} · ${a.value}", style = display(19), color = Color.White)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        d.w.longestStreak?.let { (i, days) ->
            Text("Longest streak: ${state.profileName(i)} watched something $days days in a row.", fontSize = 15.sp, color = TextMuted, lineHeight = 21.sp)
        }
    }
}

// ---------------------------------------------------------------- 8 · Share card

@Composable
private fun ShareCard(state: UiState, d: Data) {
    val peak = d.plus.peak?.let { (day, band) ->
        DayOfWeek.of(day).getDisplayName(DateTextStyle.SHORT, Locale.getDefault()) + " " + WrappedPlus.BANDS[band].let { if (it == "Late") "late" else it.lowercase() }
    }
    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 80.dp, bottom = 100.dp)) {
        Label("That's a wrap", TextMuted)
        Spacer(Modifier.height(12.dp))
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier.fillMaxWidth().weight(1f).clip(shape)
                .background(Brush.verticalGradient(listOf(Purple, Color(0xFF1A1438))))
                .border(1.dp, Color(0x1FFFFFFF), shape).padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Label("The Family Wrapped", Modifier.weight(1f))
                Text(d.label, style = display(if (d.label.length <= 4) 42 else 28), color = Color.White)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until 3).forEach { n ->
                    Box(Modifier.weight(1f)) { PosterTile(state, d.plus.topTitles.getOrNull(n)?.contentId, null, n + 1, Modifier.fillMaxWidth()) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row {
                Stat("Hours together", "${hours(d.w.totalMs)}", Modifier.weight(1f))
                Stat("Titles", "${d.w.titles}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Stat("Top watcher", d.w.topWatcher?.let { state.profileName(it.first) } ?: "-", Modifier.weight(1f), small = true)
                Stat("Prime time", peak ?: "-", Modifier.weight(1f), small = true)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
            Spacer(Modifier.height(10.dp))
            d.plus.personas.take(5).forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(state.profileName(p.profileIndex), fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(p.personality.title, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) =
    Text(text.uppercase(), style = Eyebrow, color = Color.White.copy(alpha = 0.8f), modifier = modifier)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, small: Boolean = false) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
        Text(value, style = display(if (small) 22 else 30), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
