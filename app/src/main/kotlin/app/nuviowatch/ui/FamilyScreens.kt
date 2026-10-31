@file:OptIn(ExperimentalLayoutApi::class)

package app.nuviowatch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuviowatch.core.AbandonedTitle
import app.nuviowatch.core.BadgeProgress
import app.nuviowatch.core.Fun
import app.nuviowatch.core.Outcome
import app.nuviowatch.core.Prediction
import app.nuviowatch.core.PredictionKind
import app.nuviowatch.core.Profile
import app.nuviowatch.core.Social
import app.nuviowatch.core.StatsCalculator
import app.nuviowatch.core.UpNext
import app.nuviowatch.core.Wrapped
import app.nuviowatch.core.episodeLabel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

private sealed interface FamilyPage {
    data object Home : FamilyPage
    data class Person(val index: Int) : FamilyPage
    data class Story(val monthsAgo: Int) : FamilyPage
}

/** Leaderboard, Wrapped, picks, unfinished shows and everyone's profile pages. */
@Composable
fun FamilyScreen(state: UiState, vm: MainViewModel) {
    var personIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var storyMonthsAgo by rememberSaveable { mutableStateOf<Int?>(null) }
    val page: FamilyPage = when {
        storyMonthsAgo != null -> FamilyPage.Story(storyMonthsAgo!!)
        personIndex != null -> FamilyPage.Person(personIndex!!)
        else -> FamilyPage.Home
    }
    BackHandler(enabled = page != FamilyPage.Home) {
        if (storyMonthsAgo != null) storyMonthsAgo = null else personIndex = null
    }
    AnimatedContent(targetState = page, label = "family") { current ->
        when (current) {
            FamilyPage.Home -> FamilyHome(state, vm, onPerson = { personIndex = it }, onStory = { storyMonthsAgo = it })
            is FamilyPage.Person -> ProfilePage(state, current.index, onBack = { personIndex = null })
            is FamilyPage.Story -> WrappedStory(state, current.monthsAgo, onClose = { storyMonthsAgo = null })
        }
    }
}

// ---------------------------------------------------------------- Home

@Composable
private fun FamilyHome(state: UiState, vm: MainViewModel, onPerson: (Int) -> Unit, onStory: (Int) -> Unit) {
    val profiles = state.snapshot.profiles
    val indexes = profiles.map { it.index }
    val bonus = remember(state.viewings, state.titles, state.episodes, state.clock / 3_600_000) {
        Fun.challengeBonus(state.viewings, indexes, state.titles, state.episodes, state.clock)
    }
    val board = remember(state.viewings, bonus, state.clock / 600_000) { Fun.leaderboard(state.viewings, indexes, state.clock, bonus = bonus) }
    val boardShare = rememberShareHandle()
    val champion = remember(state.viewings, state.clock / 3_600_000) { Fun.champion(state.viewings, indexes, state.clock) }
    val streaks = remember(state.viewings, state.clock / 3_600_000) {
        indexes.associateWith { i -> Fun.streaks(state.viewings.filter { it.profileIndex == i }, state.clock).first }
    }
    var pickFor by rememberSaveable { mutableStateOf(profiles.firstOrNull()?.index) }
    val names = profiles.associate { it.index to it.name }

    Column(Modifier.fillMaxSize().glow(Color(0xFF7A3A6B), 0.45f).verticalScroll(rememberScrollState())) {
        Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Text("Family", style = display(30))
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Weekly leaderboard
            GlassCard(Modifier.capturable(boardShare), padding = 18.dp) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("THIS WEEK", style = Eyebrow, color = TextMuted, modifier = Modifier.weight(1f))
                    Text("Resets Monday", fontSize = 12.sp, color = TextMuted)
                }
                Spacer(Modifier.height(12.dp))
                val top = board.firstOrNull()?.points?.coerceAtLeast(1) ?: 1
                board.forEachIndexed { rank, entry ->
                    val p = profiles.firstOrNull { it.index == entry.profileIndex } ?: return@forEachIndexed
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPerson(p.index) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${rank + 1}", style = display(18), color = if (rank == 0) Color(0xFFFFD166) else TextMuted, modifier = Modifier.width(26.dp))
                        StreakAvatar(p, streaks[p.index] ?: 0, crowned = p.index == champion, size = 38.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(5.dp))
                            ThinProgress(entry.points.toFloat() / top, visibleOnDark(parseColor(p.avatarColorHex)), height = 6.dp, track = Color(0x14FFFFFF))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${entry.points}", style = display(20))
                            Text("pts", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "1 pt per 10 min watched · 3 per episode · 10 per film · 5 per streak day" +
                        (champion?.let { " · 👑 ${names[it]} won last week" } ?: ""),
                    fontSize = 11.sp,
                    color = TextMuted,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ShareButton(boardShare, "Share leaderboard", "This week's Nuvio leaderboard")
            }

            RouletteCard(state)
            PartyCard(state, vm)
            SuggestedCard(state)
            ChallengesCard(state)
            NewEpisodesCard(state)
            BestRatedCard(state)
            PredictionsCard(state, vm)

            // Up next
            val queue = remember(state.viewings, state.episodes, state.clock / 3_600_000) { Fun.upNext(state.viewings, state.episodes, state.clock) }
            if (queue.isNotEmpty()) {
                GlassCard(padding = 18.dp) {
                    SectionTitle("Up next")
                    queue.take(8).forEach { UpNextRow(it, state, showProfile = true) }
                }
            }

            // Wrapped
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(0, 1, YEAR).forEach { monthsAgo ->
                    val month = LocalDate.now().minusMonths(maxOf(monthsAgo, 0).toLong())
                    val label = if (monthsAgo == YEAR) LocalDate.now().year.toString() else month.month.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF5B3FD1), Color(0xFFFF5C7A))))
                            .clickable(role = Role.Button) { onStory(monthsAgo) }
                            .padding(16.dp),
                    ) {
                        Text(if (monthsAgo == 1) "WRAPPED" else "SO FAR", style = Eyebrow, color = Color.White.copy(alpha = 0.8f))
                        Text(label, style = display(24), color = Color.White)
                        Text("Tap to play", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }

            // Family picks
            if (profiles.size > 1) {
                GlassCard(padding = 18.dp) {
                    SectionTitle("Family picks")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        profiles.forEach { p -> SmallChoice("For ${p.name}", pickFor == p.index) { pickFor = p.index } }
                    }
                    Spacer(Modifier.height(14.dp))
                    val picks = remember(state.viewings, state.titles, pickFor) {
                        pickFor?.let { Fun.picks(it, state.viewings, state.titles, names) }.orEmpty()
                    }
                    if (picks.isEmpty()) {
                        Text("Nothing to suggest yet. Picks come from what others finished.", fontSize = 13.sp, color = TextMuted)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(picks, key = { it.contentId }) { pick ->
                                val info = state.titles[pick.contentId]
                                val openTitle = LocalOpenTitle.current
                                val series = state.viewings.any { it.contentId == pick.contentId && it.series }
                                Column(Modifier.width(120.dp).clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "Open") { openTitle(pick.contentId, series) }) {
                                    Poster(info?.poster, width = 120.dp, corner = 14.dp)
                                    Spacer(Modifier.height(6.dp))
                                    Text(info?.name ?: pick.fallbackTitle, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(pick.reason, fontSize = 11.sp, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            // Abandoned
            val abandoned = remember(state.viewings, state.clock / 3_600_000) { Fun.abandoned(state.viewings, state.clock).take(8) }
            if (abandoned.isNotEmpty()) {
                GlassCard(padding = 18.dp) {
                    SectionTitle("Left unfinished")
                    abandoned.forEach { AbandonedRow(it, state, showProfile = true) }
                }
            }

            // Everyone
            GlassCard(padding = 18.dp) {
                SectionTitle("Everyone")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    profiles.forEach { p ->
                        Column(
                            Modifier.width(72.dp).clip(RoundedCornerShape(14.dp)).clickable { onPerson(p.index) }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            StreakAvatar(p, streaks[p.index] ?: 0, crowned = p.index == champion, size = 56.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(p.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(110.dp))
    }
}

@Composable
private fun SmallChoice(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        modifier = Modifier
            .clip(shape)
            .background(if (active) Color.White else Color.Transparent)
            .border(1.dp, if (active) Color.White else Color(0x33FFFFFF), shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        color = if (active) Ink else TextPrimary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
    )
}

/** Avatar with a streak flame and, for last week's winner, a crown. */
@Composable
fun StreakAvatar(profile: Profile, streak: Int, crowned: Boolean, size: androidx.compose.ui.unit.Dp) {
    // The crown sits above the avatar; leave room for it so cards and rows don't clip it.
    Box(Modifier.padding(top = if (crowned) size * 0.45f else 0.dp)) {
        ProfileAvatar(profile, size = size)
        if (streak >= 2) {
            Text(
                "🔥$streak",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 6.dp, y = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF1A1724))
                    .border(1.dp, Color(0x33FF8A3D), RoundedCornerShape(50))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                fontSize = (size.value * 0.22f).coerceIn(9f, 13f).sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFB27A),
            )
        }
        if (crowned) {
            Text("👑", modifier = Modifier.align(Alignment.TopCenter).offset(y = (-size.value * 0.44f).dp), fontSize = (size.value * 0.32f).sp)
        }
    }
}

@Composable
private fun AbandonedRow(a: AbandonedTitle, state: UiState, showProfile: Boolean) {
    val openTitle = LocalOpenTitle.current
    val info = state.titles[a.contentId]
    val profile = state.profile(a.profileIndex)
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open") { openTitle(a.contentId, a.season != null) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(info?.poster, width = 36.dp, corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info?.name ?: a.fallbackTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val where = listOfNotNull(
                if (showProfile) profile?.name else null,
                episodeLabel(a.season, a.episode)?.let { "stopped at $it" } ?: "stopped at ${(a.fraction * 100).toInt()}%",
                timeAgo(a.lastAtMs, state.clock),
            )
            Text(where.joinToString(" · "), fontSize = 12.sp, color = TextMuted)
        }
    }
}

@Composable
private fun UpNextRow(u: UpNext, state: UiState, showProfile: Boolean) {
    val openTitle = LocalOpenTitle.current
    val info = state.titles[u.contentId]
    val profile = state.profile(u.profileIndex)
    val status = when {
        u.resume -> "Resume"
        u.availableNow -> "Ready"
        else -> u.next.releasedMs?.let { airsIn(it, state.clock) } ?: "Coming soon"
    }
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open") { openTitle(u.contentId, true) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Poster(info?.poster, width = 38.dp, corner = 8.dp)
            if (showProfile && profile != null) {
                Box(Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp)) { ProfileAvatar(profile, size = 20.dp) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(info?.name ?: u.contentId, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(episodeLabel(u.next.season, u.next.episode), u.next.title, if (showProfile) profile?.name else null).joinToString(" · "),
                fontSize = 12.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Pill(status, if (u.availableNow) Color(0xFF7CE0C3) else Color(0xFFFFD166))
    }
}

private fun airsIn(releasedMs: Long, nowMs: Long): String {
    val days = ((releasedMs - nowMs) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "Airs today"
        days == 1 -> "Tomorrow"
        days < 7 -> "In $days days"
        else -> "Airs " + java.time.Instant.ofEpochMilli(releasedMs).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM"))
    }
}

// ---------------------------------------------------------------- Predictions

@Composable
private fun PredictionsCard(state: UiState, vm: MainViewModel) {
    var creating by rememberSaveable { mutableStateOf(false) }
    val predictions = state.social.predictions.sortedByDescending { it.createdAt }.take(8)
    GlassCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PREDICTIONS", style = Eyebrow, color = TextMuted, modifier = Modifier.weight(1f))
            Text(
                if (creating) "Cancel" else "+ New",
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { creating = !creating }.padding(horizontal = 10.dp, vertical = 6.dp),
                color = Color(0xFFC3CAFF),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
        if (creating) {
            Spacer(Modifier.height(10.dp))
            NewPrediction(state) { profile, kind, contentId, season, hours, deadline ->
                vm.predict(profile, kind, contentId, season, hours, deadline)
                creating = false
            }
        }
        if (predictions.isEmpty() && !creating) {
            Spacer(Modifier.height(8.dp))
            Text("Bet on what the family will do, like \"Will Mani finish the season by Sunday?\" Everyone votes, and the app settles it from Nuvio.", fontSize = 13.sp, color = TextMuted)
        }
        predictions.forEach { p -> PredictionRow(p, state, vm) }
    }
}

@Composable
private fun PredictionRow(p: Prediction, state: UiState, vm: MainViewModel) {
    val outcome = remember(p, state.viewings, state.episodes, state.clock / 60_000) { Social.resolve(p, state.viewings, state.episodes, state.clock) }
    val tally = Social.tally(p.id, state.social.votes)
    val myVote = state.social.votes.filter { it.predictionId == p.id && it.voterId == state.social.myDeviceId }.maxByOrNull { it.at }?.yes
    val title = p.contentId?.let { state.titles[it]?.name }
    Column(Modifier.padding(top = 14.dp)) {
        Text(Social.question(p, state.profileName(p.profileIndex), title, deadlineLabel(p.deadlineMs)), fontWeight = FontWeight.SemiBold)
        Text("by ${p.byName}", fontSize = 11.sp, color = TextMuted)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (outcome) {
                Outcome.PENDING -> {
                    VoteButton("👍 Yes · ${tally.yes.size}", myVote == true) { vm.vote(p.id, true) }
                    Spacer(Modifier.width(8.dp))
                    VoteButton("👎 No · ${tally.no.size}", myVote == false) { vm.vote(p.id, false) }
                }
                Outcome.YES -> Pill("✅ It happened · ${tally.yes.joinToString().ifEmpty { "nobody" }} called it", Color(0xFF7CE0C3))
                Outcome.NO -> Pill("❌ Didn't happen · ${tally.no.joinToString().ifEmpty { "nobody" }} called it", Color(0xFFFF8AA0))
            }
        }
        if (outcome == Outcome.PENDING && (tally.yes.isNotEmpty() || tally.no.isNotEmpty())) {
            Text(
                listOfNotNull(
                    tally.yes.takeIf { it.isNotEmpty() }?.let { "Yes: ${it.joinToString()}" },
                    tally.no.takeIf { it.isNotEmpty() }?.let { "No: ${it.joinToString()}" },
                ).joinToString(" · "),
                fontSize = 11.sp,
                color = TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun VoteButton(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        modifier = Modifier
            .clip(shape)
            .background(if (active) Color.White else Color(0x14FFFFFF))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (active) Ink else TextPrimary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
    )
}

/** Pick a person, what they'll do, and by when. */
@Composable
private fun NewPrediction(state: UiState, onCreate: (Int, PredictionKind, String?, Int?, Int?, Long) -> Unit) {
    val profiles = state.snapshot.profiles
    var who by rememberSaveable { mutableStateOf(profiles.firstOrNull()?.index) }
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    var deadlineDays by rememberSaveable { mutableStateOf(7) }

    Text("Who?", fontSize = 12.sp, color = TextMuted)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { p -> SmallChoice(p.name, who == p.index) { who = p.index; choice = null } }
    }

    val person = who ?: return
    val mine = state.viewings.filter { it.profileIndex == person }
    // Things they're in the middle of: seasons of followed shows, unfinished films.
    val seasons = Fun.upNext(mine, state.episodes, state.clock).map { it.contentId to it.next.season }.distinct().take(4)
    val films = mine.filter { !it.series && !it.completed && it.fraction > 0f }.map { it.contentId }.distinct().take(3)
    data class Option(val key: String, val label: String, val kind: PredictionKind, val contentId: String?, val season: Int?, val hours: Int?)
    val options = seasons.map { (id, season) -> Option("s|$id|$season", "Finish ${state.titles[id]?.name ?: id} S$season", PredictionKind.FINISH_SEASON, id, season, null) } +
        films.map { id -> Option("f|$id", "Finish ${state.titles[id]?.name ?: id}", PredictionKind.FINISH_FILM, id, null, null) } +
        listOf(3, 5, 10).map { h -> Option("h|$h", "Watch $h+ hours", PredictionKind.WATCH_HOURS, null, null, h) }

    Spacer(Modifier.height(12.dp))
    Text("Will they…", fontSize = 12.sp, color = TextMuted)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> SmallChoice(o.label, choice == o.key) { choice = o.key } }
    }

    Spacer(Modifier.height(12.dp))
    Text("By…", fontSize = 12.sp, color = TextMuted)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Tonight", 3 to "3 days", 7 to "A week").forEach { (d, label) -> SmallChoice(label, deadlineDays == d) { deadlineDays = d } }
    }

    val selected = options.firstOrNull { it.key == choice }
    Spacer(Modifier.height(14.dp))
    Text(
        "Make prediction",
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected != null) Color.White else Color(0x14FFFFFF))
            .clickable(enabled = selected != null, role = Role.Button) {
                val zone = java.time.ZoneId.systemDefault()
                val deadline = LocalDate.now(zone).plusDays(deadlineDays.toLong()).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
                selected?.let { onCreate(person, it.kind, it.contentId, it.season, it.hours, deadline) }
            }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        color = if (selected != null) Ink else TextMuted,
        fontWeight = FontWeight.Bold,
    )
}

// ---------------------------------------------------------------- Profile page

@Composable
private fun ProfilePage(state: UiState, index: Int, onBack: () -> Unit) {
    val profile = state.profile(index) ?: return onBack()
    val mine = remember(state.viewings, index) { state.viewings.filter { it.profileIndex == index } }
    val stats = remember(mine, state.titles, state.clock / 3_600_000) { StatsCalculator.compute(mine, state.titles, state.clock) }
    val personality = remember(mine) { Fun.personality(mine) }
    val badges = remember(mine, state.titles, state.clock / 3_600_000) { Fun.badges(mine, state.titles, state.clock) }
    val champion = remember(state.viewings, state.clock / 3_600_000) {
        Fun.champion(state.viewings, state.snapshot.profiles.map { it.index }, state.clock)
    }
    val abandoned = remember(mine, state.clock / 3_600_000) { Fun.abandoned(mine, state.clock).take(5) }
    val color = visibleOnDark(parseColor(profile.avatarColorHex))

    Column(Modifier.fillMaxSize().glow(color, 0.55f).verticalScroll(rememberScrollState())) {
        Row(Modifier.statusBarsPadding().padding(start = 8.dp, top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StreakAvatar(profile, stats.currentStreak, crowned = index == champion, size = 96.dp)
            Spacer(Modifier.height(14.dp))
            Text(profile.name, style = display(34))
            Text(personality.title, style = display(18, FontWeight.Medium), color = color)
            Text(personality.blurb, color = TextMuted, textAlign = TextAlign.Center)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("Watched", formatDuration(stats.totalWatchedMs), Modifier.weight(1f))
                MiniStat("Episodes", stats.episodes.toString(), Modifier.weight(1f))
                MiniStat("Films", stats.movies.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("Streak", "🔥 ${stats.currentStreak}", Modifier.weight(1f))
                MiniStat("Best streak", "${stats.longestStreak} days", Modifier.weight(1f))
                state.liveTime[index]?.let { MiniStat("Live this week", formatDuration(it), Modifier.weight(1f)) }
            }

            GlassCard(padding = 18.dp) {
                val earned = badges.count { it.earned }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("BADGES", style = Eyebrow, color = TextMuted, modifier = Modifier.weight(1f))
                    Text("$earned of ${badges.size}", fontSize = 12.sp, color = TextMuted)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
                    badges.sortedByDescending { it.progress }.forEach { BadgeTile(it, Modifier.weight(1f)) }
                }
            }

            if (stats.topTitles.isNotEmpty()) {
                Column {
                    Box(Modifier.padding(start = 4.dp)) { SectionTitle("Favourites") }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(stats.topTitles.take(8), key = { it.contentId }) { t ->
                            Column(Modifier.width(100.dp)) {
                                Poster(state.titles[t.contentId]?.poster, width = 100.dp, corner = 12.dp)
                                Text(formatDuration(t.watchedMs), fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }

            if (stats.topGenres.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stats.topGenres.forEach { (g, n) -> Pill("$g $n", color) }
                }
            }

            val myQueue = remember(mine, state.episodes, state.clock / 3_600_000) { Fun.upNext(mine, state.episodes, state.clock) }
            if (myQueue.isNotEmpty()) {
                GlassCard(padding = 18.dp) {
                    SectionTitle("Up next")
                    myQueue.take(6).forEach { UpNextRow(it, state, showProfile = false) }
                }
            }

            if (abandoned.isNotEmpty()) {
                GlassCard(padding = 18.dp) {
                    SectionTitle("Left unfinished")
                    abandoned.forEach { AbandonedRow(it, state, showProfile = false) }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(110.dp))
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Glass).border(1.dp, GlassBorder, RoundedCornerShape(18.dp)).padding(14.dp)) {
        Text(value, style = display(20), maxLines = 1)
        Text(label, fontSize = 12.sp, color = TextMuted)
    }
}

@Composable
private fun BadgeTile(b: BadgeProgress, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (b.earned) Color(0x26FFD166) else Color(0x0AFFFFFF))
            .border(1.dp, if (b.earned) Color(0x66FFD166) else GlassBorder, shape)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(b.badge.emoji, fontSize = 28.sp, modifier = Modifier.background(Color.Transparent), color = if (b.earned) Color.Unspecified else Color.White.copy(alpha = 0.35f))
        Text(b.badge.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1, color = if (b.earned) TextPrimary else TextMuted)
        Spacer(Modifier.height(6.dp))
        if (b.earned) {
            Text("Unlocked", fontSize = 10.sp, color = Color(0xFFFFD166))
        } else {
            ThinProgress(b.progress, Accent, height = 3.dp, track = Color(0x1AFFFFFF))
        }
        Text(b.badge.rule, fontSize = 9.sp, color = TextMuted, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------------------------------------------------------- Wrapped story

@Composable
private fun WrappedStory(state: UiState, monthsAgo: Int, onClose: () -> Unit) {
    val year = monthsAgo == YEAR
    val start = if (year) LocalDate.now().withDayOfYear(1) else LocalDate.now().minusMonths(monthsAgo.toLong()).withDayOfMonth(1)
    val end = if (year) start.plusYears(1) else start.plusMonths(1)
    val label = if (year) start.year.toString() else start.month.getDisplayName(DateTextStyle.FULL, Locale.getDefault())
    val wrapped = remember(state.viewings, state.titles, monthsAgo) {
        Fun.wrapped(state.viewings, state.titles, start, end, label)
    }
    val slides = remember(wrapped) { wrapped?.let { slidesFor(it, state) } ?: emptyList() }
    val pager = rememberPagerState { slides.size.coerceAtLeast(1) }
    val scope = rememberCoroutineScope()
    val colors = listOf(Color(0xFF5B3FD1), Color(0xFFFF5C7A), Color(0xFF1F8A70), Color(0xFFE8890C), Color(0xFF2B6CD4), Color(0xFF9B2C8F))

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (wrapped == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing watched in $label yet", style = display(26), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text("Tap to go back", color = TextMuted, modifier = Modifier.clickable(onClick = onClose))
            }
            return@Box
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { i ->
            val slide = slides[i]
            val bg = colors[i % colors.size]
            val share = rememberShareHandle()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(bg, Ink)))
                    .clickable {
                        if (i < slides.lastIndex) scope.launch { pager.animateScrollToPage(i + 1) } else onClose()
                    }
                    .statusBarsPadding()
                    .padding(28.dp),
            ) {
                Box(Modifier.align(Alignment.BottomStart)) { ShareButton(share, "Share this", "Family Wrapped · $label") }
                Column(
                    Modifier.align(Alignment.CenterStart).capturable(share).background(Brush.verticalGradient(listOf(bg, Ink))).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(slide.eyebrow.uppercase(), style = Eyebrow, color = Color.White.copy(alpha = 0.8f))
                    slide.poster?.let { Poster(it, width = 140.dp, corner = 16.dp) }
                    if (slide.big.isNotEmpty()) Text(slide.big, style = display(if (slide.big.length > 14) 40 else 64), color = Color.White)
                    Text(slide.text, fontSize = 20.sp, color = Color.White, lineHeight = 28.sp)
                    slide.list.forEachIndexed { n, (who, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${n + 1}", style = display(22), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.width(34.dp))
                            Text(who, fontSize = 20.sp, color = Color.White, modifier = Modifier.weight(1f))
                            Text(value, style = display(20), color = Color.White)
                        }
                    }
                }
            }
        }
        // Story progress bars
        Row(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(slides.size) { i ->
                Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(if (i <= pager.currentPage) Color.White else Color.White.copy(alpha = 0.3f)))
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(top = 16.dp).align(Alignment.TopEnd)) {
            Text("✕", color = Color.White, fontSize = 20.sp)
        }
    }
}

private data class Slide(
    val eyebrow: String,
    val big: String,
    val text: String,
    val poster: String? = null,
    val list: List<Pair<String, String>> = emptyList(),
)

private fun slidesFor(w: Wrapped, state: UiState): List<Slide> {
    fun name(i: Int) = state.profileName(i)
    fun title(id: String) = state.titles[id]?.name ?: id
    val hours = w.totalMs / 3_600_000
    return buildList {
        add(Slide("Family Wrapped", w.label, "Here's what the family watched. Tap to continue."))
        add(Slide("Together you watched", formatDuration(w.totalMs), "That's ${if (hours >= 24) "${hours / 24} whole days" else "$hours hours"} across ${w.titles} titles: ${w.episodes} episodes and ${w.films} films."))
        w.topWatcher?.let { (i, ms) -> add(Slide("Top watcher", name(i), "${formatDuration(ms)} watched. Nobody else came close.")) }
        w.topTitle?.let { (id, ms) -> add(Slide("Most watched", "", "${title(id)}\n${formatDuration(ms)} of the family's time", poster = state.titles[id]?.poster)) }
        w.longestBinge?.let { (i, id, n) -> add(Slide("Biggest binge", "$n episodes", "${name(i)} watched $n episodes of ${title(id)} in one day.", poster = state.titles[id]?.poster)) }
        w.busiestDay?.let { (day, ms) ->
            add(Slide("Busiest day", day.format(DateTimeFormatter.ofPattern("EEE d MMM")), "${formatDuration(ms)} watched in a single day."))
        }
        w.topGenre?.let { add(Slide("Family favourite genre", it, "The house just can't get enough.")) }
        w.firstTitle?.takeIf { w.label.length == 4 }?.let { (id, at) ->
            val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            add(Slide("First watch of the year", "", "${title(id)}\non ${day.format(DateTimeFormatter.ofPattern("d MMMM"))}", poster = state.titles[id]?.poster))
        }
        w.longestStreak?.let { (i, days) -> add(Slide("Longest streak", "$days days", "${name(i)} watched something $days days in a row. 🔥")) }
        add(Slide("The final ranking", "", "", list = w.perProfile.map { (i, ms) -> name(i) to formatDuration(ms) }))
    }
}

/** Wrapped for the whole year, instead of a month. */
private const val YEAR = -1

// ---------------------------------------------------------------- What should we watch?

@Composable
private fun RouletteCard(state: UiState) {
    val profiles = state.snapshot.profiles
    var who by rememberSaveable { mutableStateOf(listOfNotNull(state.device.profileIndex)) }
    var result by remember { mutableStateOf<app.nuviowatch.core.RouletteOption?>(null) }
    var spinning by remember { mutableStateOf(false) }
    val openTitle = LocalOpenTitle.current
    val scope = rememberCoroutineScope()
    val names = profiles.associate { it.index to it.name }
    val options = remember(who, state.viewings, state.social.ratings) {
        Fun.roulette(who.toSet(), state.viewings, state.social.ratings, names = names)
    }
    GlassCard(padding = 18.dp) {
        SectionTitle("🎲 What should we watch?")
        Text("Who's watching?", fontSize = 12.sp, color = TextMuted)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            profiles.forEach { p ->
                SmallChoice(p.name, p.index in who) { who = if (p.index in who) who - p.index else who + p.index; result = null }
            }
        }
        Spacer(Modifier.height(12.dp))
        val shown = result
        if (shown != null) {
            val info = state.titles[shown.contentId]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x14FFFFFF)).clickable { openTitle(shown.contentId, shown.series) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Poster(info?.poster, width = 54.dp, corner = 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(info?.name ?: shown.fallbackTitle, style = display(20), maxLines = 2)
                    Text(shown.reason, fontSize = 12.sp, color = TextMuted)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            when {
                who.isEmpty() -> "Pick who's watching"
                options.isEmpty() -> "Nothing to suggest for this group yet"
                spinning -> "Spinning…"
                result == null -> "🎲 Spin"
                else -> "🎲 Spin again"
            },
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (options.isNotEmpty() && !spinning) Color.White else Color(0x14FFFFFF))
                .clickable(enabled = options.isNotEmpty() && !spinning, role = Role.Button) {
                    scope.launch {
                        spinning = true
                        // A quick shuffle through the options, slowing down, before landing on one.
                        repeat(10) { n ->
                            result = options.random()
                            kotlinx.coroutines.delay(60L + n * 25L)
                        }
                        spinning = false
                    }
                }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            color = if (options.isNotEmpty() && !spinning) Ink else TextMuted,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---------------------------------------------------------------- Watch party

@Composable
private fun PartyCard(state: UiState, vm: MainViewModel) {
    var link by rememberSaveable { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    GlassCard(padding = 18.dp) {
        SectionTitle("🎉 Watch party")
        Text(
            "Start a watch party in Nuvio, copy its link, then send it here. Everyone gets a notification with a Join button that opens Nuvio in sync.",
            fontSize = 13.sp,
            color = TextMuted,
        )
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            singleLine = true,
            placeholder = { Text("Party link or code") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = { clipboard.getText()?.text?.let { link = it } }) { Text("Paste") }
            androidx.compose.material3.Button(
                onClick = { vm.shareParty(link, state.liveNow.firstOrNull { it.event.deviceId == state.social.myDeviceId }?.let { it.info?.name ?: it.event.title }); link = "" },
                enabled = link.isNotBlank(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
            ) { Text("Send to family") }
        }
    }
}

// ---------------------------------------------------------------- Suggested for you

@Composable
private fun SuggestedCard(state: UiState) {
    val me = state.device.profileIndex ?: return
    val openTitle = LocalOpenTitle.current
    val mine = state.social.suggestions.filter { it.toProfile == me }
        .filter { s -> state.viewings.none { it.profileIndex == me && it.contentId == s.contentId } }
        .distinctBy { it.contentId }.takeLast(10).reversed()
    if (mine.isEmpty()) return
    GlassCard(padding = 18.dp) {
        SectionTitle("💡 Suggested for you")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(mine, key = { it.id }) { s ->
                Column(Modifier.width(110.dp).clip(RoundedCornerShape(14.dp)).clickable { openTitle(s.contentId, s.series) }) {
                    Poster(state.titles[s.contentId]?.poster, width = 110.dp, corner = 14.dp)
                    Text(s.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text("from ${s.fromName}", fontSize = 11.sp, color = TextMuted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Monthly challenges

@Composable
private fun ChallengesCard(state: UiState) {
    val month = java.time.YearMonth.now()
    val challenges = remember(month) { Fun.challengesFor(month) }
    val profiles = state.snapshot.profiles
    if (profiles.isEmpty()) return
    GlassCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("CHALLENGES · ${month.month.getDisplayName(DateTextStyle.FULL, Locale.getDefault()).uppercase()}", style = Eyebrow, color = TextMuted, modifier = Modifier.weight(1f))
            Text("+20 pts each", fontSize = 12.sp, color = Color(0xFFFFD166))
        }
        challenges.forEach { c ->
            Spacer(Modifier.height(14.dp))
            Text("${c.emoji} ${c.title}", fontWeight = FontWeight.SemiBold)
            Text(c.goalText, fontSize = 12.sp, color = TextMuted)
            Spacer(Modifier.height(6.dp))
            profiles.forEach { p ->
                val progress = remember(state.viewings, state.titles, state.episodes, p.index, c) {
                    Fun.challengeProgress(c, state.viewings.filter { it.profileIndex == p.index }, state.titles, state.episodes, month)
                }
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, Modifier.width(76.dp), fontSize = 12.sp, maxLines = 1)
                    Box(Modifier.weight(1f)) {
                        ThinProgress(progress.fraction, if (progress.done) Color(0xFF7CE0C3) else Accent, height = 6.dp, track = Color(0x14FFFFFF))
                    }
                    Text(if (progress.done) " ✓" else " ${minOf(progress.count, c.goal)}/${c.goal}", fontSize = 12.sp, modifier = Modifier.width(40.dp), color = if (progress.done) Color(0xFF7CE0C3) else TextMuted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- New episodes

@Composable
private fun NewEpisodesCard(state: UiState) {
    val zone = java.time.ZoneId.systemDefault()
    val today = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val upcoming = remember(state.viewings, state.episodes, today) {
        Fun.newEpisodes(state.viewings, state.episodes, today - 86_400_000L, today + 8 * 86_400_000L, state.clock)
    }
    if (upcoming.isEmpty()) return
    val openTitle = LocalOpenTitle.current
    GlassCard(padding = 18.dp) {
        SectionTitle("🆕 New episodes this week")
        upcoming.take(10).forEach { a ->
            val info = state.titles[a.contentId]
            val day = java.time.Instant.ofEpochMilli(a.episode.releasedMs!!).atZone(zone).toLocalDate()
            val whenText = when (day) {
                LocalDate.now(zone) -> "Today"
                LocalDate.now(zone).minusDays(1) -> "Yesterday"
                LocalDate.now(zone).plusDays(1) -> "Tomorrow"
                else -> day.dayOfWeek.getDisplayName(DateTextStyle.FULL, Locale.getDefault())
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { openTitle(a.contentId, true) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Poster(info?.poster, width = 34.dp, corner = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(info?.name ?: a.contentId, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(episodeLabel(a.episode.season, a.episode.episode), a.followers.joinToString { state.profileName(it) }).joinToString(" · "),
                        fontSize = 12.sp,
                        color = TextMuted,
                        maxLines = 1,
                    )
                }
                Pill(whenText, if (whenText == "Today") Color(0xFF7CE0C3) else Color(0xFFC3CAFF))
            }
        }
    }
}

// ---------------------------------------------------------------- Best rated

@Composable
private fun BestRatedCard(state: UiState) {
    val openTitle = LocalOpenTitle.current
    val since = state.clock - 30 * 86_400_000L
    val best = remember(state.social.ratings, state.clock / 3_600_000) {
        state.social.ratings.filter { it.at >= since }.map { it.contentId }.distinct()
            .mapNotNull { id -> app.nuviowatch.core.Social.familyScore(id, state.social.ratings)?.let { id to it } }
            .sortedByDescending { it.second.first }.take(8)
    }
    if (best.isEmpty()) return
    GlassCard(padding = 18.dp) {
        SectionTitle("⭐ Best rated this month")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(best, key = { it.first }) { (id, score) ->
                val series = state.viewings.any { it.contentId == id && it.series }
                Column(Modifier.width(100.dp).clip(RoundedCornerShape(14.dp)).clickable { openTitle(id, series) }) {
                    Poster(state.titles[id]?.poster, width = 100.dp, corner = 14.dp)
                    Text("★ %.1f".format(score.first), color = Color(0xFFFFD166), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Text(state.titles[id]?.name ?: id, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SmallChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color(0x1FFFFFFF))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (selected) Ink else TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
