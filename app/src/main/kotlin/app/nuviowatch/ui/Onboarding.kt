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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuviowatch.live.NowPlayingListener
import app.nuviowatch.monitor.Notifier

/** First launch: who are you, alerts, playback reporting and battery, one step at a time. */
@Composable
fun OnboardingScreen(state: UiState, vm: MainViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = 4
    val next = { if (step < steps - 1) step++ else vm.finishOnboarding() }
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(Ink).glow(DefaultTint)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(steps) { i ->
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (i <= step) Color.White else Color(0x33FFFFFF)))
                }
            }
            Spacer(Modifier.height(36.dp))
            AnimatedContent(targetState = step, label = "step") { s ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (s) {
                        0 -> {
                            StepTitle("Who are you?", "Pick your profile, so reactions and invites come from you and reminders reach you.")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                state.snapshot.profiles.forEach { p ->
                                    val active = state.device.profileIndex == p.index
                                    Column(
                                        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                            .background(if (active) Color(0x33FFFFFF) else Color.Transparent)
                                            .clickable { vm.setDeviceProfile(p.index) }
                                            .padding(10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        ProfileAvatar(p, size = 56.dp, ring = if (active) Color.White else null)
                                        Spacer(Modifier.height(6.dp))
                                        Text(p.name, fontSize = 14.sp)
                                    }
                                }
                            }
                            TextButton(onClick = { vm.setDeviceProfile(null); next() }) { Text("It's a shared device (like the TV)") }
                        }
                        1 -> {
                            StepTitle("Get alerts", "A notification when someone starts watching, plus recaps, badges and reminders. Skip this on a TV.")
                            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                                if (granted) vm.setMonitoring(true)
                                next()
                            }
                            PrimaryButton(if (state.settings.enabled) "Alerts are on ✓" else "Turn on alerts") {
                                if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(context)) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else { vm.setMonitoring(true); next() }
                            }
                        }
                        2 -> {
                            StepTitle(
                                "Share what plays here",
                                "If people watch Nuvio on this device, Nuvio Watch can tell the family the moment it plays. Android calls this \"notification access\"; it's only used to see Nuvio's play and pause state.",
                            )
                            PrimaryButton(if (state.device.hasAccess) "Allowed ✓" else "Allow notification access") {
                                runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                            Text("On a Fire Stick, run this once from a computer instead:", fontSize = 12.sp, color = TextMuted)
                            Text(state.device.adbCommand, fontSize = 11.sp, color = Color(0xFFC3CAFF))
                        }
                        else -> {
                            StepTitle("Keep it running", "Android pauses background apps to save battery. Allowing these keeps alerts on time.")
                            val power = context.getSystemService(PowerManager::class.java)
                            val unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
                            PrimaryButton(if (unrestricted) "Battery: unrestricted ✓" else "Allow running in background") {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                                }
                            }
                            if (Build.VERSION.SDK_INT >= 31) {
                                OutlinedButton(onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Allow precise timing") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.finishOnboarding() }) { Text("Skip setup", color = TextMuted) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = next,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
                ) { Text(if (step == steps - 1) "Done" else "Next") }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, text: String) {
    Text(title, style = display(34))
    Text(text, color = TextMuted, fontSize = 15.sp, lineHeight = 21.sp)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text(label, fontSize = 16.sp) }
}
