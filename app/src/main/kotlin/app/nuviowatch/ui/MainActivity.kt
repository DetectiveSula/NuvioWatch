package app.nuviowatch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

private enum class Tab(val label: String, val icon: ImageVector) {
    NOW("Now", Icons.Filled.PlayArrow),
    STATS("Stats", Icons.Filled.Star),
    HISTORY("History", Icons.Filled.DateRange),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(state: UiState, vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.NOW) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tab == Tab.NOW) "Nuvio Watch" else tab.label) },
                actions = {
                    if (tab != Tab.SETTINGS) {
                        IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.snapshot.profiles.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> when (tab) {
                    Tab.NOW -> NowScreen(state, vm, onOpenSettings = { tab = Tab.SETTINGS })
                    Tab.STATS -> StatsScreen(state)
                    Tab.HISTORY -> HistoryScreen(state)
                    Tab.SETTINGS -> SettingsScreen(state, vm)
                }
            }
            if (state.loading && state.snapshot.profiles.isNotEmpty()) {
                LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
            }
        }
    }
}
