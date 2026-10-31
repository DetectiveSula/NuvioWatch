package app.nuviowatch.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import app.nuviowatch.core.PlayState

fun main(args: Array<String>) {
    // Retire the old Terminal helper: this app now does its job.
    LoginItem.removeOldHelper()
    LoginItem.set(Model.store.saved.value.startAtLogin)
    NuvioDesktopBridge.start()
    Model.start()
    MacUpdater.start()

    application {
        var windowOpen by remember { mutableStateOf("--background" !in args) }
        val trayState = rememberTrayState()
        val devices by Model.liveDevices.collectAsState()
        val clock by Model.clock.collectAsState()

        LaunchedEffect(Unit) {
            Model.alerts.collect { trayState.sendNotification(Notification(it.title, it.text, Notification.Type.None)) }
        }
        LaunchedEffect(Unit) {
            // Clicking the app in the Dock (or opening it again) shows the window.
            runCatching {
                java.awt.Desktop.getDesktop().addAppEventListener(java.awt.desktop.AppReopenedListener { windowOpen = true })
            }
        }

        Tray(
            state = trayState,
            icon = painterResource("tray.svg"),
            tooltip = "Nuvio Watch",
            onAction = { windowOpen = true },
            menu = {
                val live = remember(devices, clock) { Model.liveNow(clock) }
                if (live.isEmpty()) {
                    Item("Nobody's watching", enabled = false, onClick = {})
                } else {
                    live.forEach { e ->
                        val who = Model.profileFor(e)?.name ?: e.deviceName
                        val what = Model.titleFor(e)?.name ?: e.title
                        Item("${if (e.state == PlayState.PLAYING) "▶" else "⏸"} $who · $what", enabled = false, onClick = {})
                    }
                }
                Separator()
                MacUpdater.available.value?.let { u -> Item("Install update ${u.versionName}", onClick = { MacUpdater.install() }) }
                Item("Open Nuvio Watch", onClick = { windowOpen = true })
                Item("Quit", onClick = ::exitApplication)
            },
        )

        Window(
            onCloseRequest = { windowOpen = false },
            visible = windowOpen,
            title = "Nuvio Watch",
            icon = painterResource("icon.svg"),
            state = rememberWindowState(size = DpSize(1120.dp, 780.dp)),
        ) {
            NuvioTheme { App() }
        }

        ReactionOverlay()
    }
}

/** Reactions float up in the corner of the screen, over Nuvio Desktop. */
@Composable
private fun ReactionOverlay() {
    val bubbles by Model.bubbles.collectAsState()
    if (bubbles.isEmpty()) return
    Window(
        onCloseRequest = {},
        title = "",
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
        state = rememberWindowState(position = WindowPosition(Alignment.BottomEnd), size = DpSize(340.dp, 360.dp)),
    ) {
        NuvioTheme {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom), horizontalAlignment = Alignment.End) {
                bubbles.forEach { b -> Bubble(b) }
            }
        }
    }
}

@Composable
private fun Bubble(b: Bubble) {
    var shown by remember(b.id) { mutableStateOf(false) }
    LaunchedEffect(b.id) { shown = true }
    AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { -it }) {
        Row(
            Modifier.clip(RoundedCornerShape(40.dp)).background(Color(0xCC0A0910)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(b.emoji, fontSize = 34.sp)
            Text("  ${b.from}", fontSize = 18.sp, color = Color.White)
        }
    }
}
