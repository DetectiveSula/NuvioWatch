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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nuviowatch.core.AlertPolicy
import app.nuviowatch.core.LiveState
import app.nuviowatch.core.Viewing
import app.nuviowatch.core.episodeLabel
import app.nuviowatch.monitor.Notifier
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- Now

@Composable
fun NowScreen(state: UiState, vm: MainViewModel, onOpenSettings: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.settings.enabled) {
            item {
                SectionCard(Modifier.clickable(onClick = onOpenSettings)) {
                    Text("Alerts are off", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Turn on alerts in Settings to get a notification when someone starts watching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.error?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
        }
        items(state.now, key = { it.profile.index }) { item -> ProfileNowCard(item, state) }
        item {
            Text(
                "Nuvio shares progress when someone pauses, skips or stops, so \"active now\" can lag a few minutes behind.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ProfileNowCard(item: ProfileNow, state: UiState) {
    val entry = item.latest
    val live = AlertPolicy.liveState(entry, state.clock)
    val title = entry?.let { state.titles[it.contentId] }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(item.profile)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (item.totalToday > 0) "${formatDuration(item.totalToday)} today" else "Nothing today",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (live) {
                LiveState.ACTIVE_NOW -> Pill("● Active now", LiveRed)
                LiveState.RECENT -> Pill(timeAgo(entry!!.lastWatchedMs, state.clock), MaterialTheme.colorScheme.primary)
                LiveState.IDLE -> entry?.let { Pill(timeAgo(it.lastWatchedMs, state.clock), MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (entry != null) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Poster(title?.poster, width = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title?.name ?: entry.contentId, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    episodeLabel(entry.season, entry.episode)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { entry.fraction }, modifier = Modifier.fillMaxWidth())
                    if (entry.duration > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatDuration(entry.position)} of ${formatDuration(entry.duration)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Stats

@Composable
private fun ProfileFilter(state: UiState, selected: Int?, onSelect: (Int?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Everyone") }) }
        items(state.snapshot.profiles, key = { it.index }) { p ->
            FilterChip(selected = selected == p.index, onClick = { onSelect(p.index) }, label = { Text(p.name) })
        }
    }
}

@Composable
fun StatsScreen(state: UiState) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val stats = remember(state.viewings, state.titles, selected, state.clock / 3_600_000) { state.stats(selected) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        ProfileFilter(state, selected) { selected = it }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Time watched", formatDuration(stats.totalWatchedMs), Modifier.weight(1f))
                StatTile("In progress", stats.inProgress.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Films", stats.movies.toString(), Modifier.weight(1f))
                StatTile("Episodes", stats.episodes.toString(), Modifier.weight(1f))
                StatTile("Shows", stats.shows.toString(), Modifier.weight(1f))
            }

            SectionCard {
                SectionTitle("Last 30 days (minutes per day)")
                if (stats.days.isEmpty()) {
                    Text("No viewing yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val fmt = DateTimeFormatter.ofPattern("d MMM")
                    BarChart(
                        stats.days.map { it.minutes },
                        Triple(stats.days.first().date.format(fmt), stats.days[stats.days.size / 2].date.format(fmt), "Today"),
                        highlightLast = true,
                    )
                }
            }

            SectionCard {
                SectionTitle("Time of day")
                BarChart(stats.hours, Triple("Midnight", "Noon", "11pm"))
            }

            if (stats.topTitles.isNotEmpty()) {
                SectionCard {
                    SectionTitle("Most watched")
                    stats.topTitles.forEachIndexed { i, t ->
                        val info = state.titles[t.contentId]
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", Modifier.width(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Poster(info?.poster, width = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(info?.name ?: t.contentId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (t.series) "${t.plays} episode${if (t.plays == 1) "" else "s"}" else "Film",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(formatDuration(t.watchedMs), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            if (stats.topGenres.isNotEmpty()) {
                SectionCard {
                    SectionTitle("Favourite genres")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        stats.topGenres.forEach { (genre, count) -> Pill("$genre · $count", MaterialTheme.colorScheme.primary) }
                    }
                }
            }

            Text(
                "Times are estimates: unfinished items count up to where they were left, and finished ones count their full length.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- History

@Composable
fun HistoryScreen(state: UiState) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val items = remember(state.viewings, selected) {
        state.viewings.filter { (selected == null || it.profileIndex == selected) && it.atMs > 0 }.take(500)
    }
    Column {
        Spacer(Modifier.height(8.dp))
        ProfileFilter(state, selected) { selected = it }
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            if (items.isEmpty()) {
                item { Text("Nothing watched yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(items, key = { "${it.profileIndex}|${it.contentId}|${it.season}|${it.episode}" }) { v ->
                HistoryRow(v, state, showProfile = selected == null)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun HistoryRow(v: Viewing, state: UiState, showProfile: Boolean) {
    val info = state.titles[v.contentId]
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Poster(info?.poster, width = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info?.name ?: v.fallbackTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val details = listOfNotNull(
                episodeLabel(v.season, v.episode),
                if (showProfile) state.profileName(v.profileIndex) else null,
                timeAgo(v.atMs, state.clock),
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (v.completed) {
            Pill("Finished", MaterialTheme.colorScheme.primary)
        } else {
            Text("${(v.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
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
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
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
                    color = if (state.lastPollError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { vm.checkNow() }, modifier = Modifier.padding(top = 8.dp)) { Text("Check now") }
            }
        }

        SectionCard {
            SectionTitle("Check every")
            val options = listOf(30, 45, 60, 120)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, seconds ->
                    SegmentedButton(
                        selected = state.settings.intervalSeconds == seconds,
                        onClick = { vm.setInterval(seconds) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(if (seconds < 60) "${seconds}s" else "${seconds / 60}m") }
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingSwitch("Also alert when they finish", null, state.settings.notifyFinished) { vm.setNotifyFinished(it) }
        }

        if (state.snapshot.profiles.isNotEmpty()) {
            SectionCard {
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

        SectionCard {
            SectionTitle("Reliability")
            Text(
                "Android may pause background apps to save battery. These two settings keep alerts on time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            val power = context.getSystemService(PowerManager::class.java)
            val unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                },
                enabled = !unrestricted,
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

        SectionCard {
            SectionTitle("Account")
            Text(state.email.ifBlank { "Signed in" })
            Text(state.serverUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { vm.signOut() }) { Text("Sign out") }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
