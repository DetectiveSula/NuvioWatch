package app.nuviowatch.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuviowatch.core.Fun
import app.nuviowatch.core.LiveEvent
import app.nuviowatch.core.LiveTracker
import app.nuviowatch.core.PlayState
import app.nuviowatch.core.StatsCalculator
import app.nuviowatch.core.episodeLabel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

enum class Tab(val label: String, val icon: String) { NOW("Now", "▶"), FAMILY("Family", "♥"), STATS("Stats", "★"), HISTORY("History", "☰"), SETTINGS("Settings", "⚙") }

@Composable
fun App() {
    val saved by Model.store.saved.collectAsState()
    Box(Modifier.fillMaxSize().background(Ink)) {
        if (saved.session == null) SignInScreen() else MainScreen()
    }
}

// ---------------------------------------------------------------- Sign in

@Composable
private fun SignInScreen() {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A1F5C), Ink))), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("NUVIO WATCH", style = Eyebrow, color = Accent)
            Text("Sign in with your Nuvio account", style = display(30))
            Text("The same email and password as the Nuvio app. Nothing in your account is changed.", color = TextMuted, fontSize = 14.sp)
            OutlinedTextField(email, { email = it }, singleLine = true, placeholder = { Text("Email") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, singleLine = true, placeholder = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        message = Model.signIn(email, password)
                        busy = false
                    }
                },
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text(if (busy) "Signing in…" else "Sign in", fontWeight = FontWeight.Bold) }
            message?.let { Text(it, color = Color(0xFFFF8AA0)) }
        }
    }
}

// ---------------------------------------------------------------- Main layout

@Composable
private fun MainScreen() {
    var tab by remember { mutableStateOf(Tab.NOW) }
    var statsFor by remember { mutableStateOf<Int?>(null) }
    var story by remember { mutableStateOf<Int?>(null) }
    val loading by Model.loading.collectAsState()
    if (story != null) {
        WrappedStory(story!!) { story = null }
        return
    }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(210.dp).fillMaxHeight().background(Color(0xFF0E0C16)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            Text("Nuvio Watch", style = display(22), modifier = Modifier.padding(start = 10.dp, bottom = 18.dp))
            Tab.entries.forEach { t ->
                val active = t == tab
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (active) Color.White else Color.Transparent)
                        .clickable { tab = t }.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t.icon, color = if (active) Ink else TextMuted, modifier = Modifier.width(26.dp))
                    Text(t.label, color = if (active) Ink else TextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = TextMuted)
                Text(if (loading) "  Syncing…" else "↻ Refresh", color = TextMuted, fontSize = 12.sp, modifier = Modifier.clickable { Model.refresh() })
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (tab) {
                Tab.NOW -> NowScreen()
                Tab.FAMILY -> FamilyScreen(onPerson = { statsFor = it; tab = Tab.STATS }, onStory = { story = it })
                Tab.STATS -> StatsScreen(statsFor) { statsFor = it }
                Tab.HISTORY -> HistoryScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
fun Page(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = display(32))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (active) Color.White else Color(0x1FFFFFFF)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (active) Ink else TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

// ---------------------------------------------------------------- Now

private val REACTIONS = listOf("🔥", "😂", "😱", "😭", "💤", "👏", "❤️")

@Composable
private fun NowScreen() {
    val clock by Model.clock.collectAsState()
    val devices by Model.liveDevices.collectAsState()
    val snap by Model.snapshot.collectAsState()
    val titles by Model.titles.collectAsState()
    val saved by Model.store.saved.collectAsState()
    val live = remember(devices, clock) { Model.liveNow(clock) }
    Page("Now") {
        if (live.isEmpty()) {
            GlassCard { Text("Nobody's watching right now.", color = TextMuted) }
        }
        live.forEach { e -> LiveCard(e, clock, mine = e.deviceId == saved.deviceId) }

        SectionTitle("Latest from everyone")
        snap.profiles.forEach { p ->
            val latest = snap.progress[p.index].orEmpty().maxByOrNull { it.lastWatchedMs }
            GlassCard(padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p, 40.dp)
                    Spacer(Modifier.width(14.dp))
                    if (latest == null) {
                        Text("${p.name} · nothing yet", color = TextMuted)
                    } else {
                        val info = titles[latest.contentId]
                        Poster(info?.poster, width = 38.dp, corner = 8.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(info?.name ?: latest.contentId, episodeLabel(latest.season, latest.episode), timeAgo(latest.lastWatchedMs, clock)).joinToString(" · "),
                                color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            ThinProgress(latest.fraction, TextMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCard(e: LiveEvent, clock: Long, mine: Boolean) {
    val info = Model.titleFor(e)
    val profile = Model.profileFor(e)
    val playing = e.state == PlayState.PLAYING
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth().height(230.dp).clip(shape).border(1.dp, GlassBorder, shape)) {
        RemoteImage(info?.background ?: info?.poster, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0xEE0A0910)))))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (playing) LiveRed else TextMuted))
                Text(if (playing) "  LIVE · ${e.deviceName}" else "  PAUSED · ${e.deviceName}", style = Eyebrow, color = Color.White)
            }
            Spacer(Modifier.height(6.dp))
            Text(info?.name ?: e.title, style = display(30), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(profile?.name ?: "Someone", e.subtitle).joinToString(" · "), color = Color.White.copy(alpha = 0.85f))
            if (e.durationMs > 0) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(320.dp)) { ThinProgress(LiveTracker.estimatedPosition(e, clock).toFloat() / e.durationMs, if (playing) LiveRed else TextMuted, 3.dp) }
            }
            if (!mine) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    REACTIONS.forEach { emoji ->
                        Text(
                            emoji, fontSize = 20.sp,
                            modifier = Modifier.clip(CircleShape).background(Color(0x33FFFFFF)).clickable { Model.react(e, emoji) }.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Family

@Composable
private fun FamilyScreen(onPerson: (Int) -> Unit, onStory: (Int) -> Unit) {
    val viewings by Model.viewings.collectAsState()
    val snap by Model.snapshot.collectAsState()
    val clock by Model.clock.collectAsState()
    val saved by Model.store.saved.collectAsState()
    val indexes = snap.profiles.map { it.index }
    val active = remember(saved.activeDays) { Model.activeDays() }
    val freezes = remember(saved.freezeDays) { Model.freezeDays() }
    val board = remember(viewings, active, clock / 600_000) { Fun.leaderboard(viewings, indexes, clock, activeDays = active, freezeDays = freezes) }
    val champion = remember(viewings, active, clock / 3_600_000) { Fun.champion(viewings, indexes, clock, activeDays = active, freezeDays = freezes) }
    val streaks = remember(viewings, active, clock / 60_000) { indexes.associateWith { Model.streak(it) } }
    Page("Family") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0, 1, YEAR).forEach { monthsAgo ->
                val month = LocalDate.now().minusMonths(maxOf(monthsAgo, 0).toLong())
                val label = if (monthsAgo == YEAR) LocalDate.now().year.toString() else month.month.getDisplayName(DateTextStyle.FULL, Locale.getDefault())
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF5B3FD1), Color(0xFFFF5C7A))))
                        .clickable { onStory(monthsAgo) }.padding(18.dp),
                ) {
                    Text(if (monthsAgo == 1) "WRAPPED" else "SO FAR", style = Eyebrow, color = Color.White.copy(alpha = 0.8f))
                    Text(label, style = display(26), color = Color.White)
                    Text("Click to play", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
        GlassCard {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("THIS WEEK", style = Eyebrow, color = TextMuted, modifier = Modifier.weight(1f))
                Text("Resets Monday", fontSize = 12.sp, color = TextMuted)
            }
            Spacer(Modifier.height(12.dp))
            val top = board.firstOrNull()?.points?.coerceAtLeast(1) ?: 1
            board.forEachIndexed { rank, entry ->
                val p = snap.profiles.firstOrNull { it.index == entry.profileIndex } ?: return@forEachIndexed
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPerson(p.index) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${rank + 1}", style = display(18), color = if (rank == 0) Gold else TextMuted, modifier = Modifier.width(30.dp))
                    Avatar(p, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        NameBadges(p.name, streaks[p.index] ?: 0, p.index == champion)
                        Spacer(Modifier.height(5.dp))
                        ThinProgress(entry.points.toFloat() / top, parseColor(p.avatarColorHex), 6.dp, Color(0x14FFFFFF))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${entry.points}", style = display(20))
                        Text("pts", fontSize = 11.sp, color = TextMuted)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("1 pt per 10 min watched · 3 per episode · 10 per film · 5 per streak day", fontSize = 11.sp, color = TextMuted)
        }
        GlassCard {
            SectionTitle("Everyone")
            snap.profiles.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    row.forEach { p ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onPerson(p.index) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(p, 64.dp)
                            Spacer(Modifier.height(8.dp))
                            NameBadges(p.name, streaks[p.index] ?: 0, p.index == champion)
                            Text(Fun.personality(viewings.filter { it.profileIndex == p.index }).title, fontSize = 12.sp, color = TextMuted)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun NameBadges(name: String, streak: Int, crowned: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (crowned) Text(" 👑", fontSize = 13.sp)
        if (streak >= 1) {
            Spacer(Modifier.width(4.dp))
            Text(
                "🔥$streak",
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1A1724)).border(1.dp, Color(0x33FF8A3D), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB27A),
            )
        }
    }
}

// ---------------------------------------------------------------- Stats

private enum class Range(val label: String, val days: Long?) { WEEK("Week", 7), MONTH("Month", 30), YEAR("Year", 365), ALL("All time", null) }

@Composable
private fun StatsScreen(profile: Int?, onProfile: (Int?) -> Unit) {
    val viewings by Model.viewings.collectAsState()
    val snap by Model.snapshot.collectAsState()
    val titles by Model.titles.collectAsState()
    val clock by Model.clock.collectAsState()
    val saved by Model.store.saved.collectAsState()
    var range by remember { mutableStateOf(Range.MONTH) }
    val stats = remember(viewings, titles, profile, range, saved.activeDays, clock / 60_000) {
        StatsCalculator.compute(
            viewings.filter { profile == null || it.profileIndex == profile }, titles, clock,
            sinceMs = range.days?.let { clock - it * 86_400_000L },
            extraActiveDays = if (profile == null) Model.activeDays().values.flatten().toSet() else Model.activeDays()[profile].orEmpty(),
            freezeDays = profile?.let { Model.freezeDays()[it] }.orEmpty(),
        )
    }
    Page("Stats") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Everyone", profile == null) { onProfile(null) }
            snap.profiles.forEach { p -> Chip(p.name, profile == p.index) { onProfile(p.index) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Range.entries.forEach { r -> Chip(r.label, r == range) { range = r } } }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Big("Watched", formatDuration(stats.totalWatchedMs), Modifier.weight(1f))
            Big("Episodes", "${stats.episodes}", Modifier.weight(1f))
            Big("Films", "${stats.movies}", Modifier.weight(1f))
            Big("Streak", "🔥 ${stats.currentStreak}", Modifier.weight(1f), "Best ${stats.longestStreak}")
        }
        if (stats.topTitles.isNotEmpty()) {
            GlassCard {
                SectionTitle("Most watched")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    stats.topTitles.forEach { t ->
                        Column(Modifier.width(110.dp)) {
                            Poster(titles[t.contentId]?.poster, 110.dp, 12.dp)
                            Text(titles[t.contentId]?.name ?: t.contentId, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text(formatDuration(t.watchedMs), fontSize = 12.sp, color = TextMuted)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassCard(Modifier.weight(1f)) {
                SectionTitle("When")
                val max = stats.hours.maxOrNull()?.coerceAtLeast(1) ?: 1
                Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    stats.hours.forEach { m ->
                        Box(Modifier.weight(1f).fillMaxHeight((m.toFloat() / max).coerceAtLeast(0.02f)).clip(RoundedCornerShape(3.dp)).background(Accent))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("12am", "6am", "12pm", "6pm", "11pm").forEachIndexed { i, l ->
                        Text(l, fontSize = 11.sp, color = TextMuted, modifier = Modifier.weight(1f))
                    }
                }
            }
            GlassCard(Modifier.weight(1f)) {
                SectionTitle("Genres")
                val top = stats.topGenres.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
                stats.topGenres.take(6).forEach { (g, n) ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(g, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                        Box(Modifier.weight(1f)) { ThinProgress(n.toFloat() / top, Accent, 6.dp, Color(0x14FFFFFF)) }
                        Text(" $n", fontSize = 12.sp, color = TextMuted)
                    }
                }
                if (stats.topGenres.isEmpty()) Text("Nothing yet", color = TextMuted)
            }
        }
        GlassCard {
            SectionTitle("Last 5 weeks")
            val max = stats.days.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
            stats.days.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    week.forEach { d ->
                        val v = d.minutes.toFloat() / max
                        Box(
                            Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (d.minutes == 0L) Color(0x0FFFFFFF) else Accent.copy(alpha = 0.25f + 0.75f * v)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Big(label: String, value: String, modifier: Modifier, sub: String? = null) {
    GlassCard(modifier, padding = 16.dp) {
        Text(label, fontSize = 12.sp, color = TextMuted)
        Text(value, style = display(26))
        sub?.let { Text(it, fontSize = 12.sp, color = TextMuted) }
    }
}

// ---------------------------------------------------------------- History

@Composable
private fun HistoryScreen() {
    val viewings by Model.viewings.collectAsState()
    val titles by Model.titles.collectAsState()
    val clock by Model.clock.collectAsState()
    var who by remember { mutableStateOf<Int?>(null) }
    val snap by Model.snapshot.collectAsState()
    Page("History") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Everyone", who == null) { who = null }
            snap.profiles.forEach { p -> Chip(p.name, who == p.index) { who = p.index } }
        }
        val list = remember(viewings, who) { viewings.filter { who == null || it.profileIndex == who }.filter { it.atMs > 0 }.sortedByDescending { it.atMs }.take(200) }
        GlassCard(padding = 10.dp) {
            list.forEach { v ->
                val info = titles[v.contentId]
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Poster(info?.poster, 36.dp, 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info?.name ?: v.fallbackTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                Model.profileName(v.profileIndex),
                                episodeLabel(v.season, v.episode),
                                if (v.completed) "finished" else "stopped at ${(v.fraction * 100).toInt()}%",
                            ).joinToString(" · "),
                            fontSize = 13.sp, color = TextMuted,
                        )
                    }
                    Text(timeAgo(v.atMs, clock), fontSize = 12.sp, color = TextMuted)
                }
            }
            if (list.isEmpty()) Text("Nothing yet.", color = TextMuted, modifier = Modifier.padding(8.dp))
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
private fun SettingsScreen() {
    val saved by Model.store.saved.collectAsState()
    val snap by Model.snapshot.collectAsState()
    val statuses by Model.statuses.collectAsState()
    var name by remember(saved.deviceName) { mutableStateOf(saved.deviceName) }
    Page("Settings") {
        GlassCard {
            Toggle("Watching alerts", "A Mac notification when someone starts watching, and a summary when they stop.", saved.alertsOn) { on -> Model.store.update { it.copy(alertsOn = on) } }
            Toggle("Also alert about this Mac", "Useful for testing.", saved.alertOwnDevice) { on -> Model.store.update { it.copy(alertOwnDevice = on) } }
            Toggle("Start when I log in", "Runs quietly in the menu bar so alerts and Nuvio Desktop reporting always work.", saved.startAtLogin) { on ->
                Model.store.update { it.copy(startAtLogin = on) }
                LoginItem.set(on)
            }
        }
        GlassCard {
            SectionTitle("This Mac")
            Toggle("Report what Nuvio Desktop plays", "Everyone sees it live, like on phones and TVs.", saved.reportPlayback) { on -> Model.store.update { it.copy(reportPlayback = on) } }
            Text("In Nuvio Desktop → Settings, turn on Discord Rich Presence. Discord itself isn't needed.", fontSize = 13.sp, color = TextMuted)
            Spacer(Modifier.height(12.dp))
            Text("Name", fontSize = 12.sp, color = TextMuted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.width(260.dp))
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { Model.store.update { it.copy(deviceName = name.trim().ifBlank { "Mac" }) } }, enabled = name.trim() != saved.deviceName) { Text("Save") }
            }
            Spacer(Modifier.height(12.dp))
            Text("Who usually watches on this Mac?", fontSize = 12.sp, color = TextMuted)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Not set", saved.profileIndex == null) { Model.store.update { it.copy(profileIndex = null) } }
                snap.profiles.forEach { p -> Chip(p.name, saved.profileIndex == p.index) { Model.store.update { it.copy(profileIndex = p.index) } } }
            }
        }
        GlassCard {
            SectionTitle("Mute")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                snap.profiles.forEach { p ->
                    val muted = p.index in saved.mutedProfiles
                    Chip(if (muted) "🔕 ${p.name}" else p.name, muted) {
                        Model.store.update { it.copy(mutedProfiles = if (muted) it.mutedProfiles - p.index else it.mutedProfiles + p.index) }
                    }
                }
            }
            Text("Muted people don't send you alerts.", fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 6.dp))
        }
        GlassCard {
            SectionTitle("Status")
            listOf(
                "Live connection" to "stream", "Last live report received" to "received", "Last alert" to "alert", "Last summary" to "summary",
                "Nuvio Desktop" to "nuvio", "Nuvio Desktop player" to "player", "Last report sent from here" to "send", "Nuvio sync" to "sync",
            ).forEach { (label, key) ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(label, fontSize = 12.sp, color = TextMuted)
                    Text(statuses[key] ?: "None yet", fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { Model.showBubble("😂", "Test reaction") }) { Text("Test a reaction pop-up") }
        }
        GlassCard {
            SectionTitle("Updates")
            val update by MacUpdater.available.collectAsState()
            val updateMessage by MacUpdater.message.collectAsState()
            val scope = rememberCoroutineScope()
            Text("Installed: ${BuildInfo.VERSION}")
            update?.let { Text("Available: ${it.versionName}", color = Mint, fontWeight = FontWeight.SemiBold) }
            updateMessage?.let { Text(it, fontSize = 13.sp, color = TextMuted) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (update != null) {
                    Button(onClick = { MacUpdater.install() }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink)) { Text("Install update") }
                }
                OutlinedButton(onClick = { scope.launch { MacUpdater.check(manual = true) } }) { Text("Check for updates") }
            }
            Text("Nuvio Watch checks every few hours and sends a notification when there's a new version.", fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 6.dp))
        }
        GlassCard {
            SectionTitle("Account")
            Text(saved.session?.email?.ifBlank { null } ?: "Signed in")
            Text("Nuvio Watch for Mac ${BuildInfo.VERSION}", fontSize = 12.sp, color = TextMuted)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { Model.signOut() }) { Text("Sign out", color = Color(0xFFFF8AA0)) }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 13.sp, color = TextMuted)
        }
        Switch(checked, onChange)
    }
}
