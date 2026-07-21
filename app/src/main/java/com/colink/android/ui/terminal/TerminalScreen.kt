package com.colink.android.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.network.ConnectionManager
import com.colink.android.network.TerminalEvent
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private sealed interface TerminalCommand {
    data class Opened(val deviceId: String, val sessionId: String) : TerminalCommand
    data class Input(val deviceId: String, val data: String) : TerminalCommand
    data class Resize(val deviceId: String, val cols: Int, val rows: Int) : TerminalCommand
    data class Close(val deviceId: String) : TerminalCommand
    data class Ended(val sessionId: String) : TerminalCommand
}

@HiltViewModel
class TerminalViewModel @Inject constructor(private val connectionManager: ConnectionManager) : ViewModel() {
    private val commands = Channel<TerminalCommand>(Channel.UNLIMITED)

    @Volatile
    var sessionId: String? = null
    private var connecting = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            var activeDeviceId: String? = null
            var activeSessionId: String? = null
            val pendingInput = ArrayDeque<TerminalCommand.Input>()
            var pendingResize: TerminalCommand.Resize? = null

            suspend fun sendInput(command: TerminalCommand.Input, sessionId: String) {
                connectionManager.sendTerminalInput(command.deviceId, sessionId, command.data)
                    .onFailure { error ->
                        CoLinkLog.w("Terminal", "terminal input delivery failed", error)
                    }
            }

            for (command in commands) {
                when (command) {
                    is TerminalCommand.Opened -> {
                        activeDeviceId = command.deviceId
                        activeSessionId = command.sessionId
                        while (pendingInput.isNotEmpty()) {
                            val input = pendingInput.removeFirst()
                            if (input.deviceId == activeDeviceId) {
                                sendInput(input, command.sessionId)
                            }
                        }
                        pendingResize?.takeIf { it.deviceId == activeDeviceId }?.let { resize ->
                            connectionManager.resizeTerminal(
                                resize.deviceId,
                                command.sessionId,
                                resize.cols,
                                resize.rows,
                            ).onFailure { error ->
                                CoLinkLog.w("Terminal", "terminal resize delivery failed", error)
                            }
                        }
                        pendingResize = null
                    }

                    is TerminalCommand.Input -> {
                        val session = activeSessionId
                        if (session != null && command.deviceId == activeDeviceId) {
                            sendInput(command, session)
                        } else {
                            pendingInput.addLast(command)
                        }
                    }

                    is TerminalCommand.Resize -> {
                        val session = activeSessionId
                        if (session != null && command.deviceId == activeDeviceId) {
                            connectionManager.resizeTerminal(
                                command.deviceId,
                                session,
                                command.cols,
                                command.rows,
                            ).onFailure { error ->
                                CoLinkLog.w("Terminal", "terminal resize delivery failed", error)
                            }
                        } else {
                            pendingResize = command
                        }
                    }

                    is TerminalCommand.Close -> {
                        pendingInput.clear()
                        pendingResize = null
                        activeSessionId?.takeIf { activeDeviceId == command.deviceId }?.let { session ->
                            connectionManager.closeTerminal(command.deviceId, session)
                                .onFailure { error ->
                                    CoLinkLog.w("Terminal", "terminal close delivery failed", error)
                                }
                        }
                        activeDeviceId = null
                        activeSessionId = null
                    }

                    is TerminalCommand.Ended -> {
                        if (command.sessionId == activeSessionId) {
                            pendingInput.clear()
                            pendingResize = null
                            activeDeviceId = null
                            activeSessionId = null
                        }
                    }
                }
            }
        }
    }

    fun connect(deviceId: String, cols: Int, rows: Int) = viewModelScope.launch(Dispatchers.IO) {
        if (connecting || sessionId != null) return@launch
        connecting = true
        try {
            connectionManager.openTerminal(deviceId, cols, rows).onSuccess {
                sessionId = it
                commands.trySend(TerminalCommand.Opened(deviceId, it))
            }
        } finally {
            connecting = false
        }
    }

    fun input(deviceId: String, data: String) {
        commands.trySend(TerminalCommand.Input(deviceId, data))
    }

    fun resize(deviceId: String, cols: Int, rows: Int) {
        commands.trySend(TerminalCommand.Resize(deviceId, cols, rows))
    }

    fun close(deviceId: String) {
        sessionId = null
        commands.trySend(TerminalCommand.Close(deviceId))
    }

    fun handleEvent(event: TerminalEvent) {
        val endedSessionId = when (event) {
            is TerminalEvent.Closed -> event.sessionId
            is TerminalEvent.Failed -> event.sessionId
            else -> return
        }
        if (sessionId == endedSessionId) {
            sessionId = null
        }
        commands.trySend(TerminalCommand.Ended(endedSessionId))
    }
    val events = connectionManager.terminalEvents
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(deviceId: String, onBack: () -> Unit, viewModel: TerminalViewModel) {
    val context = LocalContext.current
    val webView = remember { WebView(context) }
    var showExitDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        showExitDialog = true
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val currentSessionId = viewModel.sessionId
            viewModel.handleEvent(event)
            when (event) {
                is TerminalEvent.Output -> {
                    if (event.sessionId == currentSessionId) {
                        webView.post { webView.evaluateJavascript("window.queueTerminalOutput('${event.data}')", null) }
                    }
                }
                is TerminalEvent.Closed -> {
                    if (event.sessionId == currentSessionId) {
                        onBack()
                    }
                }
                else -> {}
            }
        }
    }
    DisposableEffect(deviceId) { onDispose { viewModel.close(deviceId) } }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(com.colink.android.R.string.device_control_terminal)) },
                navigationIcon = {
                    IconButton(onClick = { showExitDialog = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(com.colink.android.R.string.back_desc),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        AndroidView(factory = {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.isFocusableInTouchMode = true
            webView.addJavascriptInterface(object {
                @JavascriptInterface fun input(data: String) { viewModel.input(deviceId, data) }
                @JavascriptInterface fun resize(cols: Int, rows: Int) { viewModel.resize(deviceId, cols, rows) }
                @JavascriptInterface fun ready(cols: Int, rows: Int) { viewModel.connect(deviceId, cols, rows) }
                @JavascriptInterface fun requestKeyboard() {
                    webView.post {
                        webView.requestFocusFromTouch()
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }, "AndroidTerminal")
            webView.loadUrl("file:///android_asset/terminal/index.html")
            webView
        }, modifier = Modifier.fillMaxSize().padding(innerPadding))
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(com.colink.android.R.string.terminal_exit_confirm_title)) },
            text = { Text(stringResource(com.colink.android.R.string.terminal_exit_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        onBack()
                    },
                ) {
                    Text(stringResource(com.colink.android.R.string.terminal_exit_confirm_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(stringResource(com.colink.android.R.string.cancel_btn))
                }
            },
        )
    }
}

