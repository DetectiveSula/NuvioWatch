@file:OptIn(ExperimentalLayoutApi::class)

package app.nuviowatch.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuviowatch.live.ReactionOverlay
import app.nuviowatch.monitor.Notifier

/**
 * First launch, one step at a time: you and this device, alerts, recaps,
 * what this device shares, and keeping it reliable. Everything here can be
 * changed later in Settings.
 */
@Composable
fun OnboardingScreen(state: UiState, vm: MainViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = 5
    val next: () -> Unit = { if (step < steps - 1) step += 1 else vm.finishOnboarding() }
    val back: () -> Unit = { if (step > 0) step -= 1 }

    Box(Modifier.fillMaxSize().background(Ink).glow(DefaultTint)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(steps) { i ->
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (i <= step) Color.White else Color(0x33FFFFFF)))
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 28.dp)) {
                AnimatedContent(targetState = step, label = "step") { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (s) {
                            0 -> YouStep(state, vm)
                            1 -> AlertsStep(state, vm)
                            2 -> RecapsStep(state, vm)
                            3 -> DeviceStep(state, vm)
                            else -> ReliabilityStep(state)
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step == 0) {
                    TextButton(onClick = { vm.finishOnboarding() }) { Text("Skip setup", color = TextMuted) }
                } else {
                    TextButton(onClick = back) { Text("Back", color = TextMuted) }
                }
                Spacer(Modifier.weight(1f))
                Text("${step + 1} of $steps", color = TextMuted, fontSize = 13.sp)
                Spacer(Modifier.width(14.dp))
                Button(onClick = next, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink)) {
                    Text(if (step == steps - 1) "Done" else "Next")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Steps

@Composable
private fun YouStep(state: UiState, vm: MainViewModel) {
    StepTitle("You and this device", "Who watches here, and what to call this device. Reactions and invites come from you, and reminders reach you.")
    Label("Who watches on this device?")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.snapshot.profiles.forEach { p ->
            val active = state.device.profileIndex == p.index
            Column(
                Modifier.clip(RoundedCornerShape(18.dp))
                    .background(if (active) Color(0x33FFFFFF) else Color.Transparent)
                    .clickable(role = Role.RadioButton) { vm.setDeviceProfile(p.index) }
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProfileAvatar(p, size = 52.dp, ring = if (active) Color.White else null)
                Spacer(Modifier.height(6.dp))
                Text(p.name, fontSize = 14.sp)
            }
        }
    }
    Choice("Shared device, work it out from what's playing", state.device.profileIndex == null) { vm.setDeviceProfile(null) }

    Spacer(Modifier.height(6.dp))
    Label("Device name")
    var name by rememberSaveable(state.device.name) { mutableStateOf(state.device.name) }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; if (it.isNotBlank()) vm.setDeviceName(it) },
        singleLine = true,
        placeholder = { Text("e.g. Living room TV, Mani's phone") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AlertsStep(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    StepTitle("Alerts", "A notification when someone starts watching. On a TV you can leave this off.")
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setMonitoring(true)
    }
    SwitchRow("Watching alerts", "Notify me when someone presses play", state.settings.enabled) { on ->
        if (on && Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(context)) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        else vm.setMonitoring(on)
    }

    Label("Check every")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color(0x14FFFFFF)).padding(3.dp)) {
        listOf(30, 45, 60, 120).forEach { seconds ->
            val active = state.settings.intervalSeconds == seconds
            Box(
                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(50))
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable(role = Role.RadioButton) { vm.setInterval(seconds) },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (seconds < 60) "${seconds}s" else "${seconds / 60}m", color = if (active) Ink else TextPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Text("Live devices alert within seconds either way; this is for checking Nuvio's own history.", fontSize = 12.sp, color = TextMuted)

    if (state.snapshot.profiles.isNotEmpty()) {
        Label("Alert me about")
        state.snapshot.profiles.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(p, size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Text(p.name, Modifier.weight(1f))
                Switch(checked = p.index !in state.settings.mutedProfiles, onCheckedChange = { vm.setProfileAlerts(p.index, it) })
            }
        }
    }
}

@Composable
private fun RecapsStep(state: UiState, vm: MainViewModel) {
    StepTitle("Recaps", "A summary of what the family watched, plus badges, Monday's winner and streak reminders.")
    SwitchRow("Daily recap", "What everyone watched today", state.recaps.daily) { vm.setRecaps(daily = it) }
    SwitchRow("Weekly recap", "Sunday awards: top watcher, show of the week, binge, night owl", state.recaps.weekly) { vm.setRecaps(weekly = it) }
    Label("Send at")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(18, 20, 21, 22, 23).forEach { h -> Choice("%02d:00".format(h), state.recaps.hour == h) { vm.setRecaps(hour = h) } }
    }
}

@Composable
private fun DeviceStep(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    StepTitle("What plays here", "If people watch Nuvio on this device, share it with the family the moment it plays, and show their reactions on screen.")

    SwitchRow("Report what's playing here", "Tells the family when Nuvio plays, pauses or stops on this device", state.device.reportPlayback) {
        vm.setReportPlayback(it)
    }
    if (state.device.reportPlayback) {
        StatusOrButton(
            ok = state.device.hasAccess,
            okText = "Notification access allowed ✓",
            button = "Allow notification access",
            hint = "Only used to see Nuvio's play and pause state. On a Fire Stick, run this once from a computer instead: ${state.device.adbCommand}",
        ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Spacer(Modifier.height(4.dp))
    Label("Reactions over the video")
    var overlayOk by rememberSaveable { mutableStateOf(ReactionOverlay.canShow(context)) }
    LaunchedEffect(state.clock) { overlayOk = ReactionOverlay.canShow(context) }
    StatusOrButton(
        ok = overlayOk,
        okText = "Reactions will float over Nuvio here ✓",
        button = "Allow display over other apps",
        hint = "Otherwise reactions arrive as notifications. On a Fire Stick: ${ReactionOverlay.adbCommand(context)}",
    ) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun ReliabilityStep(state: UiState) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val refresh = state.clock // re-check permissions every few seconds, e.g. after returning from Settings
    StepTitle("Reliability", "Android quietens and pauses background apps. These keep alerts on time and popping up on screen.")
    val power = context.getSystemService(PowerManager::class.java)
    StatusOrButton(
        ok = power.isIgnoringBatteryOptimizations(context.packageName),
        okText = "Battery: unrestricted ✓",
        button = "Allow running in background",
        hint = null,
    ) { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
    if (Build.VERSION.SDK_INT >= 31) {
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        StatusOrButton(
            ok = alarms.canScheduleExactAlarms(),
            okText = "Precise timing ✓",
            button = "Allow precise timing",
            hint = null,
        ) { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
    }
    OutlinedButton(
        onClick = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Check notifications pop up") }
    Text(
        "In that screen, make sure notifications are on and set to Alert / Pop-up (not Silent). On Samsung, also turn on \"Pop-up notifications\".",
        fontSize = 12.sp,
        color = TextMuted,
    )
}

// ---------------------------------------------------------------- Pieces

@Composable
private fun StepTitle(title: String, text: String) {
    Text(title, style = display(32))
    Text(text, color = TextMuted, fontSize = 15.sp, lineHeight = 21.sp)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = Eyebrow, color = TextMuted, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, fontSize = 13.sp, color = TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Choice(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        modifier = Modifier.clip(shape)
            .background(if (active) Color.White else Color.Transparent)
            .border(1.dp, if (active) Color.White else Color(0x33FFFFFF), shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        color = if (active) Ink else TextPrimary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
    )
}

@Composable
private fun StatusOrButton(ok: Boolean, okText: String, button: String, hint: String?, onClick: () -> Unit) {
    if (ok) {
        Text(okText, color = Color(0xFF7CE0C3), fontWeight = FontWeight.SemiBold)
    } else {
        Button(
            onClick = { runCatching(onClick) },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(button, fontSize = 15.sp) }
        hint?.let { Text(it, fontSize = 12.sp, color = TextMuted) }
    }
}
