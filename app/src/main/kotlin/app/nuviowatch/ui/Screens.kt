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
import androidx.compose.ui.graphics.graphicsLayer
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
import app.nuviowatch.core.LiveTracker
import app.nuviowatch.core.PlayState
import app.nuviowatch.core.Profile
import app.nuviowatch.core.Viewing
import app.nuviowatch.core.episodeLabel
import app.nuviowatch.live.LiveHub
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
fun NowScreen(state: UiState, vm: MainViewModel, onOpenSettings: () -> Unit) {
    val hero = state.now.filter { it.latest != null }.maxWithOrNull(
        compareBy<ProfileNow>({ AlertPolicy.liveState(it.latest, state.clock) == LiveState.ACTIVE_NOW }, { it.latest!!.lastWatchedMs }),
    )
    val live = state.liveNow.firstOrNull()
    val heroInfo = live?.info ?: hero?.latest?.let { state.titles[it.contentId] }
    val tint = rememberImageTint(heroInfo?.poster)
    val others = state.now.filter { if (live != null) it.profile.index != live.profile?.index else it != hero }

    val burst = state.social.reactions.lastOrNull()?.takeIf { state.clock - it.at < 15_000 && it.fromDeviceId != state.social.myDeviceId }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().glow(tint)) {
        item(key = "hero") {
            if (live != null) LiveHero(state, live, tint, onReact = { vm.react(live, it) }) else Hero(state, hero, tint)
        }
        state.liveNow.drop(1).forEach { other ->
            item(key = "live-${other.event.deviceId}") { LiveRow(other, state.clock, onReact = { vm.react(other, it) }) }
        }
        state.social.message?.let { msg ->
            item(key = "social-msg") { Text(msg, fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        }
        val recent = state.social.reactions.filter { state.clock - it.at < 3 * 3_600_000L }.takeLast(6).reversed()
        if (recent.isNotEmpty()) {
            item(key = "reactions") {
                Column(Modifier.padding(top = 16.dp)) {
                    Box(Modifier.padding(horizontal = 20.dp)) { SectionTitle("Reactions") }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(recent, key = { it.id }) { r ->
                            val to = r.toProfile?.let { state.profileName(it) } ?: "TV"
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(Glass).border(1.dp, GlassBorder, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(r.emoji, fontSize = 18.sp)
                                Spacer(Modifier.width(6.dp))
                                Text("${r.fromName} → $to", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                Text(timeAgo(r.at, state.clock), fontSize = 11.sp, color = TextMuted)
                            }
                        }
                    }
                }
            }
        }
        if (state.devices.isNotEmpty()) {
            item(key = "devices") { DeviceStrip(state) }
        }

        if (!state.settings.enabled) {
            item(key = "alerts-off") {
                GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onOpenSettings)) {
                    Text("Alerts are off", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Turn them on in Settings to get a summary each time someone watches something.",
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
        // A reaction just arrived: show it big for a moment.
        burst?.let { r ->
            val scale = remember(r.id) { Animatable(0.2f) }
            LaunchedEffect(r.id) { scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f)) }
            Column(
                Modifier.align(Alignment.Center).padding(bottom = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(r.emoji, fontSize = 110.sp, modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value })
                Text("${r.fromName} reacted", style = display(20), color = Color.White)
            }
        }
    }
}

/** Every device in the house: what it's playing, or how long it's been idle. */
@Composable
private fun DeviceStrip(state: UiState) {
    Column(Modifier.padding(top = 16.dp)) {
        Box(Modifier.padding(horizontal = 20.dp)) { SectionTitle("Devices") }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(state.devices, key = { it.deviceId }) { d ->
                val fresh = state.clock - d.at < 5 * 60_000L
                val playing = fresh && d.state == PlayState.PLAYING
                val paused = fresh && d.state == PlayState.PAUSED
                val shape = RoundedCornerShape(18.dp)
                Column(
                    Modifier
                        .width(150.dp)
                        .clip(shape)
                        .background(if (playing) Color(0x1FFF5C7A) else Glass)
                        .border(1.dp, if (playing) Color(0x66FF5C7A) else GlassBorder, shape)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (playing) "▶" else if (paused) "❚❚" else "■", fontSize = 12.sp, color = if (playing) LiveRed else TextMuted)
                        Spacer(Modifier.width(6.dp))
                        Text(d.deviceName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            playing || paused -> LiveHub.titleFor(d, state.titles)?.name ?: d.title
                            else -> "Idle · ${timeAgo(d.at, state.clock)}"
                        },
                        fontSize = 12.sp,
                        color = if (playing || paused) TextPrimary else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if ((playing || paused) && d.durationMs > 0) {
                        Spacer(Modifier.height(8.dp))
                        ThinProgress(LiveTracker.estimatedPosition(d, state.clock).toFloat() / d.durationMs, if (playing) LiveRed else TextMuted, height = 3.dp, track = Color(0x1AFFFFFF))
                    }
                }
            }
        }
    }
}

/** Quick emoji reactions to whoever is watching. */
@Composable
private fun ReactionBar(onReact: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("🔥", "😂", "😱", "😭", "💤", "👏", "❤️").forEach { e ->
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(0x26FFFFFF)).clickable(role = Role.Button, onClickLabel = "React $e") { onReact(e) },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 20.sp) }
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

/** The hero when a device is reporting Nuvio playback right now. */
@Composable
private fun LiveHero(state: UiState, live: LiveNow, tint: Color, onReact: (String) -> Unit) {
    val event = live.event
    val playing = event.state == PlayState.PLAYING
    val position = LiveTracker.estimatedPosition(event, state.clock)
    val who = live.profile?.name ?: "Someone"
    Box(Modifier.fillMaxWidth().height(480.dp).background(tint.copy(alpha = 0.6f))) {
        (live.info?.background ?: live.info?.poster)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to Ink.copy(alpha = 0.6f), 0.22f to Color.Transparent, 0.45f to Color.Transparent, 1f to Ink),
            ),
        )
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Nuvio Watch", style = display(19), modifier = Modifier.weight(1f))
            state.now.forEachIndexed { i, p ->
                Box(Modifier.offset(x = (-8 * i).dp)) {
                    ProfileAvatar(p.profile, size = 28.dp, ring = if (p.profile.index == live.profile?.index) LiveRed else Ink)
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) {
                    LiveDot()
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    "${who.uppercase()} ${if (playing) "IS WATCHING" else "PAUSED"} · ${event.deviceName.uppercase()}",
                    style = Eyebrow,
                    color = if (playing) Color(0xFFFFB3C1) else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(live.info?.name ?: event.title, style = display(44), maxLines = 2, overflow = TextOverflow.Ellipsis)
            event.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color(0xFFD6D1E6), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (event.durationMs > 0) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatClock(position), style = MaterialTheme.typography.labelSmall, color = Color(0xFFD6D1E6))
                    Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        ThinProgress(position.toFloat() / event.durationMs, if (playing) Color.White else TextMuted)
                    }
                    Text(formatClock(event.durationMs), style = MaterialTheme.typography.labelSmall, color = Color(0xFFD6D1E6))
                }
            }
            if (event.deviceId != state.social.myDeviceId) {
                Spacer(Modifier.height(4.dp))
                ReactionBar(onReact)
            }
        }
    }
}

/** Extra devices playing at the same time. */
@Composable
private fun LiveRow(live: LiveNow, now: Long, onReact: (String) -> Unit) {
    val playing = live.event.state == PlayState.PLAYING
    GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Poster(live.info?.poster, width = 36.dp, corner = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${live.profile?.name ?: "Someone"} ${if (playing) "is watching" else "paused"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (playing) Color(0xFFFFB3C1) else TextMuted,
                )
                Text(live.info?.name ?: live.event.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(live.event.subtitle, live.event.deviceName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                )
            }
            if (playing) LiveDot(6.dp)
        }
        if (live.event.durationMs > 0) {
            Spacer(Modifier.height(10.dp))
            ThinProgress(LiveTracker.estimatedPosition(live.event, now).toFloat() / live.event.durationMs, Accent, height = 3.dp, track = Color(0x1AFFFFFF))
        }
        Spacer(Modifier.height(10.dp))
        ReactionBar(onReact)
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
                    subtitle = "Sends one summary after someone finishes watching, about 20 minutes after they stop.",
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
                SectionTitle("Recaps")
                SettingSwitch("Daily recap", "What everyone watched today.", state.recaps.daily) { vm.setRecaps(daily = it) }
                Spacer(Modifier.height(10.dp))
                SettingSwitch("Weekly recap", "Sunday awards: top watcher, show of the week, binge, night owl.", state.recaps.weekly) {
                    vm.setRecaps(weekly = it)
                }
                Spacer(Modifier.height(12.dp))
                Text("Send at", fontSize = 12.sp, color = TextMuted)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(18, 20, 21, 22, 23).forEach { h ->
                        ChoicePill("%02d:00".format(h), state.recaps.hour == h) { vm.setRecaps(hour = h) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { vm.sendRecapNow() }) { Text("Send recap now") }
                state.recaps.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(top = 6.dp)) }
            }

            if (state.snapshot.profiles.isNotEmpty()) {
                GlassCard {
                    SectionTitle("Sound for each person")
                    Text(
                        "Give each person their own alert sound and vibration, so you know who pressed play without looking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    state.snapshot.profiles.forEach { p ->
                        Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProfileAvatar(p, size = 30.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(p.name, Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                val channel = Notifier.personChannel(context, p.index, p.name)
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            .putExtra(Settings.EXTRA_CHANNEL_ID, channel),
                                    )
                                }
                            }) { Text("Sound") }
                        }
                    }
                }
            }

            DeviceCard(state, vm)

            MacCard(state)

            GlassCard {
                SectionTitle("Status")
                state.statuses.forEach { (label, value) ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text(label, fontSize = 12.sp, color = TextMuted)
                        Text(value, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { vm.sendTest() }) { Text("Send test alert") }
                state.testMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(top = 6.dp)) }
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

/** Setting up live reports from Nuvio Desktop on a Mac. */
@Composable
private fun MacCard(state: UiState) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    GlassCard {
        SectionTitle("Mac (Nuvio Desktop)")
        Text(
            "Get instant alerts from Nuvio Desktop on a Mac too. It works through Nuvio Desktop's Discord Rich Presence setting; Discord itself isn't needed.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Spacer(Modifier.height(10.dp))
        listOf(
            "1. Copy the command below and paste it into Terminal on the Mac (Applications → Utilities → Terminal), then press Return.",
            "2. In Nuvio Desktop → Settings, turn on Discord Rich Presence, then restart it.",
        ).forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        Spacer(Modifier.height(10.dp))
        val command = "curl -fsSL https://raw.githubusercontent.com/DetectiveSula/NuvioWatch/main/mac-helper/get.sh | bash -s ${state.accountId} \"Mac\""
        Text(
            command,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x1AFFFFFF)).padding(12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFC3CAFF),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(command))
            copied = true
        }) { Text(if (copied) "Copied ✓" else "Copy command") }
        Text(
            "The code in the command is your setup code. Keep it within the family: anyone with it can see what's playing.",
            fontSize = 11.sp,
            color = TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Settings for reporting what Nuvio is playing on this device. */
@Composable
private fun DeviceCard(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val device = state.device
    var name by rememberSaveable(device.name) { mutableStateOf(device.name) }
    GlassCard {
        SectionTitle("This device")
        SettingSwitch(
            title = "Report what's playing here",
            subtitle = "When Nuvio plays on this phone or TV, everyone's Nuvio Watch sees it instantly.",
            checked = device.reportPlayback,
        ) { vm.setReportPlayback(it) }

        Spacer(Modifier.height(12.dp))
        SettingSwitch(
            title = "Also alert about this device",
            subtitle = "Off by default, since you know what you're watching. Turn on to test with one phone.",
            checked = device.alertOwnDevice,
        ) { vm.setAlertOwnDevice(it) }

        if (device.reportPlayback) {
            Spacer(Modifier.height(12.dp))
            if (device.hasAccess) {
                Text("Playback reporting is on ✓", color = Color(0xFF7CE0C3), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            } else {
                Text(
                    "Needs \"notification access\" so it can see Nuvio's play and pause state. It doesn't read your notifications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                var failed by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        failed = runCatching {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isFailure
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow notification access") }
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (failed) "This device has no settings screen for it. " else "") +
                        "On a Fire Stick or some Android TVs, turn it on from a computer with:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Text(device.adbCommand, style = MaterialTheme.typography.labelSmall, color = Color(0xFFC3CAFF))
            }

            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Device name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (name.trim() != device.name && name.isNotBlank()) {
                OutlinedButton(onClick = { vm.setDeviceName(name) }, modifier = Modifier.padding(top = 6.dp)) { Text("Save name") }
            }

            Spacer(Modifier.height(14.dp))
            Text("Who watches on this device?", fontWeight = FontWeight.Medium)
            Text(
                "\"Work it out\" matches the title to whoever has it in their Nuvio history.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePill("Work it out", device.profileIndex == null) { vm.setDeviceProfile(null) }
                state.snapshot.profiles.forEach { p ->
                    ChoicePill(p.name, device.profileIndex == p.index) { vm.setDeviceProfile(p.index) }
                }
            }
        }
    }
}

@Composable
private fun ChoicePill(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        modifier = Modifier
            .clip(shape)
            .background(if (active) Color.White else Color.Transparent)
            .border(1.dp, if (active) Color.White else Color(0x33FFFFFF), shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (active) Ink else TextPrimary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
    )
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
