package app.nuviowatch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NuvioWatchTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                if (!state.signedIn) SignInScreen(state, viewModel) else MainScreen(state, viewModel)
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(state: UiState, vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Ink)) {
        if (state.loading && state.snapshot.profiles.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            Crossfade(targetState = tab, label = "tab") { current ->
                if (current == SETTINGS_TAB) {
                    SettingsScreen(state, vm)
                } else {
                    PullToRefreshBox(isRefreshing = state.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                        when (current) {
                            0 -> NowScreen(state, vm, onOpenSettings = { tab = SETTINGS_TAB })
                            1 -> FamilyScreen(state, vm)
                            2 -> StatsScreen(state)
                            else -> HistoryScreen(state)
                        }
                    }
                }
            }
        }
        FloatingNav(tabs, tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
    }
}
