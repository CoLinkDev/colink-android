package com.colink.android.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.ui.components.StateMessage

@Composable
fun AuthScreen(
    modifier: Modifier = Modifier,
    onAuthenticated: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel(),
) {
    AuthContent(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        showHeader = true,
        onAuthenticated = onAuthenticated,
        viewModel = viewModel,
    )
}

@Composable
fun AuthDialogContent(
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    AuthContent(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        showHeader = false,
        onAuthenticated = onAuthenticated,
        viewModel = viewModel,
    )
}

@Composable
private fun AuthContent(
    modifier: Modifier,
    verticalArrangement: Arrangement.Vertical,
    showHeader: Boolean,
    onAuthenticated: () -> Unit,
    viewModel: AuthViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var identifier by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var localErrorResId by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(settings.serverUrl) {
        if (serverUrl.isBlank()) {
            serverUrl = settings.serverUrl
        }
    }

    LaunchedEffect(uiState.authenticated) {
        if (uiState.authenticated) {
            onAuthenticated()
            viewModel.consumeAuthenticated()
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showHeader) {
            Spacer(modifier = Modifier.height(24.dp))
        }
        if (showHeader) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.colink_logo),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    text = "CoLink",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.auth_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_url_label)) },
                    leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = identifier,
                    onValueChange = { identifier = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.email_or_username_label)) },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.password_label)) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) stringResource(R.string.hide_password_desc) else stringResource(R.string.show_password_desc),
                            )
                        }
                    },
                    singleLine = true,
                )

                val errorMsg = when {
                    localErrorResId != null -> stringResource(localErrorResId!!)
                    uiState.error != null -> uiState.error
                    else -> null
                }
                StateMessage(errorMsg)

                Button(
                    onClick = {
                        localErrorResId = validate(
                            serverUrl = serverUrl,
                            identifier = identifier,
                            password = password,
                        )
                        if (localErrorResId != null) {
                            return@Button
                        }
                        val normalizedServerUrl = serverUrl.trim()
                        viewModel.login(normalizedServerUrl, identifier, password)
                    },
                    enabled = !uiState.loading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    if (uiState.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.login_btn))
                    }
                }
            }
        }
        if (showHeader) {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun validate(
    serverUrl: String,
    identifier: String,
    password: String,
): Int? {
    if (serverUrl.isBlank()) {
        return R.string.err_server_url_required
    }
    if (identifier.isBlank()) {
        return R.string.err_email_or_username_required
    }
    if (password.isBlank()) {
        return R.string.err_password_required
    }
    return null
}
