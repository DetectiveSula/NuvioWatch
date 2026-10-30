package app.nuviowatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.nuviowatch.core.OFFICIAL_SERVER_URL

@Composable
fun SignInScreen(state: UiState, vm: MainViewModel) {
    var showServer by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize().background(Ink).glow(DefaultTint), color = Color.Transparent) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Nuvio Watch", style = display(38))
            Spacer(Modifier.height(6.dp))
            Text(
                "Sign in with your Nuvio account to see what every profile is watching.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            when {
                state.server == ServerStatus.DETECTING -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Connecting to Nuvio…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.server == ServerStatus.NOT_FOUND || showServer -> ServerForm(state, vm, onDone = { showServer = false })
                else -> {
                    LoginForm(state, vm)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showServer = true }) { Text("Use a different server") }
                }
            }
        }
    }
}

@Composable
private fun LoginForm(state: UiState, vm: MainViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { vm.signIn(email, password) },
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
        enabled = !state.loading && email.isNotBlank() && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in")
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "This app only reads your account. It never changes anything in Nuvio.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ServerForm(state: UiState, vm: MainViewModel, onDone: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(state.serverUrl.ifBlank { OFFICIAL_SERVER_URL }) }
    var key by rememberSaveable { mutableStateOf("") }
    if (state.server == ServerStatus.NOT_FOUND) {
        Text("Couldn't find Nuvio's server key", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.nuvioInstalled) {
                "The key couldn't be read from the Nuvio app on this phone. Paste it below, or enter a self-hosted server address."
            } else {
                "Install the official Nuvio app on this phone and tap Try again, or paste the key below. For a self-hosted server, enter its address and leave the key empty."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Public key (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    state.error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(20.dp))
    Button(onClick = { vm.useCustomServer(url, key); onDone() }, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
        Text("Connect")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { vm.detectServer(); onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Try again automatically") }
}
