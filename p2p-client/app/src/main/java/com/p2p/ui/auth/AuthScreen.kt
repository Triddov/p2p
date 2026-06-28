package com.p2p.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.p2p.ui.components.FullScreenLoader

@Composable
fun AuthScreen(
    onAuthenticated: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val profile by viewModel.localProfile.collectAsState()

    // Авто-вход: только если профиль есть И username уже задан
    // Без проверки username - экран ввода ника исчезает сразу после верификации
    LaunchedEffect(profile) {
        if (profile != null && profile!!.username != null) {
            onAuthenticated()
        }
    }

    when (val state = uiState) {
        is AuthUiState.Initial -> EmailInput(
            onRequestCode = { email -> viewModel.requestCode(email) }
        )

        is AuthUiState.CodeSent -> CodeInput(
            email = state.email,
            onVerify = { code -> viewModel.verifyCode(state.email, code) },
            onBack = { viewModel.resetState() }
        )

        is AuthUiState.Authenticated -> {
            if (state.profile.username == null) {
                UsernameInput(
                    onSetUsername = { username -> viewModel.setUsername(username) }
                )
            } else {
                LaunchedEffect(Unit) {
                    onAuthenticated()
                }
            }
        }

        is AuthUiState.UsernameSet -> {
            LaunchedEffect(Unit) {
                onAuthenticated()
            }
        }

        is AuthUiState.Loading -> FullScreenLoader()

        is AuthUiState.Error -> Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Error: ${state.message}", color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.resetState() }) {
                Text("Retry")
            }
        }
    }
}

@Composable
fun EmailInput(onRequestCode: (String) -> Unit) {
    var email by remember { mutableStateOf(TextFieldValue("")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "P2P Messenger",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onRequestCode(email.text) },
            modifier = Modifier.fillMaxWidth(),
            enabled = email.text.isNotBlank()
        ) {
            Text("Send Verification Code")
        }
    }
}

@Composable
fun CodeInput(
    email: String,
    onVerify: (String) -> Unit,
    onBack: () -> Unit
) {
    var code by remember { mutableStateOf(TextFieldValue("")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enter verification code sent to:",
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = email,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("6-digit code") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onVerify(code.text) },
            modifier = Modifier.fillMaxWidth(),
            enabled = code.text.length == 6
        ) {
            Text("Verify")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}

@Composable
fun UsernameInput(onSetUsername: (String) -> Unit) {
    var username by remember { mutableStateOf(TextFieldValue("")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Choose a username",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            placeholder = { Text("alice_crypto") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "3-32 characters, lowercase, numbers, underscore",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onSetUsername(username.text) },
            modifier = Modifier.fillMaxWidth(),
            enabled = username.text.length in 3..32
        ) {
            Text("Continue")
        }
    }
}