@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.nuviowatch.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuviowatch.core.AlertPolicy
import app.nuviowatch.core.LiveState
import app.nuviowatch.core.Profile
import app.nuviowatch.core.Viewing
import app.nuviowatch.core.episodeLabel
import app.nuviowatch.monitor.Notifier
import coil.compose.AsyncImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

private val BottomSpace = 110.dp

@Composable
private fun CountUpDuration(ms: Long, style: TextStyle) {
    val value = remember { Animatable(0f) }
    LaunchedEffect(ms) { value.animateTo(ms.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    Text(formatDuration(value.value.toLong()), style = style)
}

@Composable
private fun ScreenHeader(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = display(30), modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun ProfileChips(state: UiState, selected: Int?, onSelect: (Int?) -> Unit) {
    @Composable
    fun Chip(label: String, profile: Profile?, active: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(50)
        Row(
            Modifier
                .height(36.dp)
                .clip(shape)
                .background(if (active) Color.White else Color.Transparent)
                .border(1.dp, if (active) Color.White else Color(0x33FFFFFF), shape)
                .clickable(role = Role.Tab, onClick = onClick)
                .padding(start = if (profile != null) 5.dp else 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (profile != null) {
                ProfileAvatar(profile, size = 26.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, color = if (active) Ink else TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { Chip("Everyone", null, selected == null) { onSelect(null) } }
        items(state.snapshot.profiles, key = { it.index }) { p ->
            Chip(p.name, p, selected == p.index) { onSelect(p.index) }
        }
    }
}

// ---------------------------------------------------------------- Now

@Composable
fun NowScreen(state: UiState, onOpenSettings: () -> Unit) {
    val hero = state.now.filter { it.latest != null }.maxWithOrNull(
        compareBy<ProfileNow>({ AlertPolicy.liveState(it.latest, state.clock) == LiveState.ACTIVE_NOW }, { it.latest!!.lastWatchedMs }),
    )
    val heroInfo = hero?.latest?.let { state.titles[it.contentId] }
    val tint = rememberImageTint(heroInfo?.poster)
    val others = state.now.filter { it != hero }

    LazyColumn(Modifier.fillMaxSize().glow(tint)) {
        item(key = "hero") { Hero(state, hero, tint) }

        if (!state.settings.enabled) {
            item(key = "alerts-off") {
                GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onOpenSettings)) {
                    Text("Alerts are off", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Turn them on in Settings to get a notification when someone starts watching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }
        }

        state.error?.let { error ->
            item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }

        if (others.isNotEmpty()) {
            item(key = "others") {
                Column(Modifier.padding(top = 18.dp)) {
                    Box(Modifier.padding(horizontal = 20.dp)) { SectionTitle("Everyone else") }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(others, key = { it.profile.index }) { OtherProfileCard(it, state) }
                    }
                }
            }
        }

        item(key = "today") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.Bottom) {
                Text("Watched today", color = TextMuted, modifier = Modifier.weight(1f))
                CountUpDuration(state.now.sumOf { it.totalToday }, display(28))
            }
        }

        item(key = "note") {
            Text(
                "Nuvio only shares progress when someone pauses, skips or stops, so \"active\" means they did one of those recently. It can't tell whether they're still watching.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.navigationBarsPadding().height(BottomSpace))
        }
    }
}

@Composable
private fun Hero(state: UiState, hero: ProfileNow?, tint: Color) {
    val entry = hero?.latest
    val info = entry?.let { state.titles[it.contentId] }
    val live = AlertPolicy.liveState(entry, state.clock) == LiveState.ACTIVE_NOW
    Box(Modifier.fillMaxWidth().height(480.dp).background(tint.copy(alpha = 0.6f))) {
        val image = info?.background ?: info?.poster
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (live) 1f else 0.55f,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Ink.copy(alpha = 0.6f),
                    0.22f to Color.Transparent,
                    0.45f to Color.Transparent,
                    1f to Ink,
                ),
            ),
        )

        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Nuvio Watch", style = display(19), modifier = Modifier.weight(1f))
            state.now.forEachIndexed { i, p ->
                val isLive = AlertPolicy.liveState(p.latest, state.clock) == LiveState.ACTIVE_NOW
                Box(Modifier.offset(x = (-8 * i).dp)) {
                    ProfileAvatar(p.profile, size = 28.dp, ring = if (isLive) LiveRed else Ink)
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entry == null || hero == null) {
                Text("Nothing watched yet", style = display(36))
                Text("When someone watches on Nuvio, it shows up here.", color = TextMuted)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ago = timeAgo(entry.lastWatchedMs, state.clock).uppercase()
                if (live) {
                    LiveDot()
                    Spacer(Modifier.width(4.dp))
                    Text("${hero.profile.name.uppercase()} · ACTIVE ${ago}", style = Eyebrow, color = Color(0xFFFFB3C1))
                } else {
                    Text("${hero.profile.name.uppercase()} WATCHED · $ago", style = Eyebrow, color = TextMuted)
                }
            }
            Text(info?.name ?: entry.contentId, style = display(44), maxLines = 2, overflow = TextOverflow.Ellipsis)
            listOfNotNull(episodeLabel(entry.season, entry.episode), info?.year).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color(0xFFD6D1E6))
            }
            if (entry.duration > 0) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatClock(entry.position), style = MaterialTheme.typography.labelSmall, color = Color(0xFFD6D1E6))
                    Box(Modifier.weight(1f).padding(horizontal = 10.dp)) { ThinProgress(entry.fraction, if (live) Color.White else TextMuted) }
                    Text(formatClock(entry.duration), style = MaterialTheme.typography.labelSmall, color = Color(0xFFD6D1E6))
                }
            }
        }
    }
}

@Composable
private fun OtherProfileCard(item: ProfileNow, state: UiState) {
    val entry = item.latest
    val info = entry?.let { state.titles[it.contentId] }
    val live = AlertPolicy.liveState(entry, state.clock) == LiveState.ACTIVE_NOW
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.width(170.dp).clip(shape).background(Glass).border(1.dp, if (live) LiveRed else GlassBorder, shape)) {
        Box(Modifier.fillMaxWidth().height(96.dp).background(parseColor(item.profile.avatarColorHex).copy(alpha = 0.35f))) {
            (info?.background ?: info?.poster)?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
            Box(Modifier.align(Alignment.BottomStart).padding(10.dp)) { ProfileAvatar(item.profile, size = 26.dp) }
            if (live) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { LiveDot(6.dp) }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Text(info?.name ?: entry?.contentId ?: "Nothing yet", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = when {
                entry == null -> item.profile.name
                entry.isCompleted -> "${item.profile.name} · Finished · ${timeAgo(entry.lastWatchedMs, state.clock)}"
                else -> "${item.profile.name} · ${(entry.fraction * 100).toInt()}% · ${timeAgo(entry.lastWatchedMs, state.clock)}"
            }
            Text(details, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- Stats

@Composable
fun StatsScreen(state: UiState) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var range by rememberSaveable { mutableStateOf(StatsRange.MONTH) }
    val stats = remember(state.viewings, state.titles, selected, range, state.clock / 3_600_000) { state.stats(selected, range) }
    val tint = rememberImageTint(stats.topTitles.firstOrNull()?.let { state.titles[it.contentId]?.poster })

    Column(Modifier.fillMaxSize().glow(tint, 0.4f).verticalScroll(rememberScrollState())) {
        ScreenHeader("Stats") {
            Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x14FFFFFF)).padding(3.dp)) {
                StatsRange.entries.forEach { r ->
                    Text(
                        r.label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (r == range) Color.White else Color.Transparent)
                            .clickable(role = Role.Tab) { range = r }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (r == range) Ink else TextMuted,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        ProfileChips(state, selected) { selected = it }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard(padding = 18.dp) {
                Text(range.heading.uppercase(), style = Eyebrow, color = TextMuted)
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUpDuration(stats.totalWatchedMs, display(52))
                }
                Text(
                    "${stats.movies} films · ${stats.episodes} episodes · ${stats.inProgress} in progress",
                    color = Color(0xFFD6D1E6),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (selected == null && stats.perProfile.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    val max = stats.perProfile.values.max().coerceAtLeast(1)
                    stats.perProfile.entries.sortedByDescending { it.value }.forEach { (index, ms) ->
                        val p = state.profile(index)
                        Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p?.name ?: "Profile $index", Modifier.width(64.dp), fontSize = 13.sp, color = Color(0xFFD6D1E6), maxLines = 1)
                            Box(Modifier.weight(1f)) {
                                ThinProgress(ms.toFloat() / max, visibleOnDark(parseColor(p?.avatarColorHex ?: "#8C9BFF")), height = 10.dp, track = Color(0x14FFFFFF))
                            }
                            Text(formatDuration(ms), Modifier.width(64.dp).padding(start = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }

            GlassCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Activity", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Best streak ${stats.longestStreak} day${if (stats.longestStreak == 1) "" else "s"}", fontSize = 12.sp, color = TextMuted)
                }
                Spacer(Modifier.height(12.dp))
                Heatmap(stats.days.map { it.minutes })
                if (stats.currentStreak > 1) {
                    Spacer(Modifier.height(10.dp))
                    Text("On a ${stats.currentStreak}-day streak", fontSize = 12.sp, color = LiveRed, fontWeight = FontWeight.SemiBold)
                }
            }

            if (stats.topTitles.isNotEmpty()) {
                Column {
                    Box(Modifier.padding(start = 4.dp)) { SectionTitle("Top titles") }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(stats.topTitles.take(10).withIndex().toList(), key = { it.value.contentId }) { (i, t) ->
                            TopTitle(i + 1, state.titles[t.contentId]?.poster, state.titles[t.contentId]?.name ?: t.contentId, formatDuration(t.watchedMs))
                        }
                    }
                }
            }

            GlassCard(padding = 18.dp) {
                SectionTitle("Time of day")
                BarChart(stats.hours, Triple("Midnight", "Noon", "11pm"))
            }

            if (stats.topGenres.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stats.topGenres.forEach { (genre, count) -> Pill("$genre $count", Color(0xFFC3CAFF)) }
                }
            }

            Text(
                "Times are estimates: unfinished items count up to where they were left, and finished ones count their full length.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        Spacer(Modifier.navigationBarsPadding().height(BottomSpace))
    }
}

/** Weeks as columns (Monday at the top), coloured by minutes watched. */
@Composable
private fun Heatmap(minutes: List<Long>) {
    val max = (minutes.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val padded = minutes + List((7 - minutes.size % 7) % 7) { -1L }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("M", "", "W", "", "F", "", "S").forEach {
                Box(Modifier.size(width = 12.dp, height = 20.dp), contentAlignment = Alignment.Center) {
                    Text(it, fontSize = 10.sp, color = TextMuted)
                }
            }
        }
        padded.chunked(7).forEach { week ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                week.forEach { m ->
                    val color = when {
                        m < 0 -> Color.Transparent
                        m == 0L -> Color(0x14FFFFFF)
                        m <= max / 3 -> Accent.copy(alpha = 0.3f)
                        m <= max * 2 / 3 -> Accent.copy(alpha = 0.6f)
                        else -> Accent
                    }
                    Box(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(5.dp)).background(color))
                }
            }
        }
    }
}

@Composable
private fun TopTitle(rank: Int, poster: String?, name: String, time: String) {
    Column(Modifier.width(128.dp)) {
        Box(Modifier.height(150.dp).fillMaxWidth()) {
            Box(Modifier.align(Alignment.TopEnd)) { Poster(poster, width = 100.dp, corner = 14.dp) }
            Text(
                rank.toString(),
                modifier = Modifier.align(Alignment.BottomStart),
                style = display(72).copy(drawStyle = Stroke(width = 5f)),
                color = Accent,
            )
        }
        Text(name, Modifier.padding(start = 28.dp, top = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(time, Modifier.padding(start = 28.dp), fontSize = 12.sp, color = TextMuted)
    }
}

// ---------------------------------------------------------------- History

private data class DayGroup(val label: String, val totalMs: Long, val items: List<Viewing>)

private fun groupByDay(viewings: List<Viewing>, now: Long): List<DayGroup> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
    return viewings.groupBy { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (date, items) ->
            val label = when {
                date == today -> "Today"
                date == today.minusDays(1) -> "Yesterday"
                date.isAfter(today.minusDays(7)) -> date.dayOfWeek.getDisplayName(DateTextStyle.FULL, Locale.getDefault())
                else -> date.format(dateFormat)
            }
            DayGroup(label, items.sumOf { it.watchedMs }, items)
        }
}

@Composable
fun HistoryScreen(state: UiState) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val groups = remember(state.viewings, selected, state.clock / 3_600_000) {
        groupByDay(state.viewings.filter { (selected == null || it.profileIndex == selected) && it.atMs > 0 }.take(500), state.clock)
    }
    LazyColumn(Modifier.fillMaxSize().glow(DefaultTint, 0.4f)) {
        item(key = "header") {
            ScreenHeader("History")
            ProfileChips(state, selected) { selected = it }
            Spacer(Modifier.height(4.dp))
        }
        if (groups.isEmpty()) {
            item { Text("Nothing watched yet", color = TextMuted, modifier = Modifier.padding(20.dp)) }
        }
        groups.forEach { group ->
            item(key = "day-${group.label}") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        Text(group.label, style = display(18), modifier = Modifier.weight(1f))
                        Text(formatDuration(group.totalMs), fontSize = 12.sp, color = TextMuted)
                    }
                    GlassCard(padding = 14.dp) {
                        group.items.forEachIndexed { i, v ->
                            HistoryRow(v, state, showProfile = selected == null)
                            if (i < group.items.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding().height(BottomSpace)) }
    }
}

@Composable
private fun HistoryRow(v: Viewing, state: UiState, showProfile: Boolean) {
    val info = state.titles[v.contentId]
    val profile = state.profile(v.profileIndex)
    val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(v.atMs).atZone(ZoneId.systemDefault()))
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Poster(info?.poster, width = 38.dp, corner = 8.dp)
            if (showProfile && profile != null) {
                Box(Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp)) { ProfileAvatar(profile, size = 20.dp) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(info?.name ?: v.fallbackTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            val details = listOfNotNull(episodeLabel(v.season, v.episode), if (showProfile) profile?.name else null, time)
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = TextMuted)
            if (!v.completed && v.fraction > 0f) {
                Spacer(Modifier.height(7.dp))
                ThinProgress(v.fraction, Accent, height = 3.dp, track = Color(0x1AFFFFFF))
            }
        }
        if (v.completed) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.CheckCircle, contentDescription = "Finished", tint = Accent, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setMonitoring(true)
    }
    Column(Modifier.fillMaxSize().glow(DefaultTint, 0.4f).verticalScroll(rememberScrollState())) {
        ScreenHeader("Settings")
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard {
                SettingSwitch(
                    title = "Watching alerts",
                    subtitle = "Checks Nuvio in the background and notifies you when someone starts watching.",
                    checked = state.settings.enabled,
                ) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(context)) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setMonitoring(on)
                    }
                }
                if (state.settings.enabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            state.lastPollError != null -> "Last check failed: ${state.lastPollError}"
                            state.lastPollAt > 0 -> "Last checked ${timeAgo(state.lastPollAt, state.clock)}"
                            else -> "Waiting for the first check…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.lastPollError != null) MaterialTheme.colorScheme.error else TextMuted,
                    )
                    OutlinedButton(onClick = { vm.checkNow() }, modifier = Modifier.padding(top = 8.dp)) { Text("Check now") }
                }
            }

            GlassCard {
                SectionTitle("Check every")
                val options = listOf(30, 45, 60, 120)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color(0x14FFFFFF)).padding(3.dp)) {
                    options.forEach { seconds ->
                        val active = state.settings.intervalSeconds == seconds
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (active) Color.White else Color.Transparent)
                                .clickable(role = Role.RadioButton) { vm.setInterval(seconds) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (seconds < 60) "${seconds}s" else "${seconds / 60}m",
                                color = if (active) Ink else TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                SettingSwitch("Also alert when they finish", null, state.settings.notifyFinished) { vm.setNotifyFinished(it) }
            }

            if (state.snapshot.profiles.isNotEmpty()) {
                GlassCard {
                    SectionTitle("Alert me about")
                    state.snapshot.profiles.forEach { p ->
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProfileAvatar(p, size = 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(p.name, Modifier.weight(1f))
                            Switch(checked = p.index !in state.settings.mutedProfiles, onCheckedChange = { vm.setProfileAlerts(p.index, it) })
                        }
                    }
                }
            }

            GlassCard {
                SectionTitle("Reliability")
                Text(
                    "Android may pause background apps to save battery. These two settings keep alerts on time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                val power = context.getSystemService(PowerManager::class.java)
                val unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                    },
                    enabled = !unrestricted,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (unrestricted) "Battery: unrestricted ✓" else "Allow running in background") }
                if (Build.VERSION.SDK_INT >= 31) {
                    val alarms = context.getSystemService(android.app.AlarmManager::class.java)
                    val exact = alarms.canScheduleExactAlarms()
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                        },
                        enabled = !exact,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (exact) "Precise timing ✓" else "Allow precise timing (Alarms & reminders)") }
                }
            }

            GlassCard {
                SectionTitle("Account")
                Text(state.email.ifBlank { "Signed in" })
                Text(state.serverUrl, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.signOut() }) { Text("Sign out", color = Color(0xFFFF8AA0)) }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(BottomSpace))
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
