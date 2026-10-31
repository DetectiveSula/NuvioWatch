package app.nuviowatch.ui

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openFrom(intent)
        setContent {
            NuvioWatchTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                when {
                    !state.signedIn -> SignInScreen(state, viewModel)
                    !state.onboarded -> OnboardingScreen(state, viewModel)
                    else -> MainScreen(state, viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A notification can ask to open a Wrapped story (months ago). */
    private fun openFrom(intent: android.content.Intent?) {
        val months = intent?.getIntExtra(EXTRA_WRAPPED, Int.MIN_VALUE) ?: return
        if (months != Int.MIN_VALUE) {
            viewModel.openWrapped(months)
            intent.removeExtra(EXTRA_WRAPPED)
        }
    }

    companion object {
        const val EXTRA_WRAPPED = "open_wrapped"
    }
}

private const val SETTINGS_TAB = 4

private val tabs = listOf(
    NavItem("Now", Icons.Filled.PlayArrow),
    NavItem("Family", Icons.Filled.Favorite),
    NavItem("Stats", Icons.Filled.Star),
    NavItem("History", Icons.Filled.DateRange),
    NavItem("Settings", Icons.Filled.Settings),
)

@Composable
private fun MainScreen(state: UiState, vm: MainViewModel) {
    androidx.compose.runtime.CompositionLocalProvider(LocalOpenTitle provides { id, series -> vm.openTitle(id, series) }) {
        MainScreenContent(state, vm)
        state.social.openTitle?.let { TitleSheet(state, vm, it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(state: UiState, vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var fullScreen by remember { mutableStateOf(false) }
    // Opened from "Your Wrapped is ready": go to the Family tab, which plays it.
    androidx.compose.runtime.LaunchedEffect(state.pendingWrapped) { if (state.pendingWrapped != null) tab = 1 }
    val context = LocalContext.current
    val isTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

    val content: @Composable (Int) -> Unit = { current ->
        if (current == SETTINGS_TAB) {
            SettingsScreen(state, vm)
        } else {
            PullToRefreshBox(isRefreshing = state.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                when (current) {
                    0 -> NowScreen(state, vm, onOpenSettings = { tab = SETTINGS_TAB })
                    1 -> androidx.compose.runtime.CompositionLocalProvider(LocalFullScreen provides { fullScreen = it }) { FamilyScreen(state, vm) }
                    2 -> StatsScreen(state)
                    else -> HistoryScreen(state)
                }
            }
        }
    }

    if (isTv) {
        // TVs: tabs along the top, moved through with the remote, with a clear focus highlight.
        Column(Modifier.fillMaxSize().background(Ink)) {
            if (!fullScreen) TvNav(tabs, tab) { tab = it }
            Box(Modifier.weight(1f)) {
                if (state.loading && state.snapshot.profiles.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    Crossfade(targetState = tab, label = "tab") { content(it) }
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (state.loading && state.snapshot.profiles.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            Crossfade(targetState = tab, label = "tab") { content(it) }
        }
        if (!fullScreen) FloatingNav(tabs, tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TvNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Nuvio Watch", style = display(26))
        Spacer(Modifier.width(24.dp))
        items.forEachIndexed { i, item ->
            var focused by remember { mutableStateOf(false) }
            val active = i == selected
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .clip(shape)
                    .background(if (active) Color.White else if (focused) Color(0x33FFFFFF) else Color.Transparent)
                    .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, contentDescription = null, tint = if (active) Ink else TextPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(item.label, color = if (active) Ink else TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
