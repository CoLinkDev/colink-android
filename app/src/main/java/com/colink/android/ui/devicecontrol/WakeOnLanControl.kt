package com.colink.android.ui.devicecontrol

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.domain.model.Device
import com.colink.android.network.ConnectionManager
import com.colink.android.network.SystemControlSupport
import com.colink.android.network.SystemControlUnsupportedException
import com.colink.android.network.message.SystemControlAction
import com.colink.android.network.message.isValidWakeOnLanMac
import com.colink.android.ui.components.StateMessage
import com.colink.android.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WakeOnLanUiState(
    val submitting: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class WakeOnLanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WakeOnLanUiState())

    val uiState: StateFlow<WakeOnLanUiState> = _uiState.asStateFlow()
    val recentMacs: StateFlow<List<String>> =
        settingsDataStore.recentWakeOnLanMacs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun resetState() {
        _uiState.update { WakeOnLanUiState() }
    }

    fun wakeOnLanSupport(deviceId: String): SystemControlSupport =
        connectionManager.wakeOnLanSupport(deviceId)

    fun normalizedMac(value: String): String = value.trim().replace('-', ':').uppercase(Locale.ROOT)

    fun send(targetDeviceId: String, targetMac: String) {
        val normalizedMac = normalizedMac(targetMac)
        if (_uiState.value.submitting || !isValidWakeOnLanMac(normalizedMac)) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(submitting = true, error = null) }
            val result = connectionManager.sendSystemControl(
                targetDeviceId = targetDeviceId,
                action = SystemControlAction.WakeOnLan,
                targetMac = normalizedMac,
            )
            if (result.isSuccess) {
                runCatching { settingsDataStore.rememberWakeOnLanMac(normalizedMac) }
                viewModelScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        localizedContext(),
                        localizedContext().getString(R.string.device_wake_on_lan_packet_sent),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                _uiState.update { it.copy(submitting = false) }
            } else {
                val error = result.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        submitting = false,
                        error = when (error) {
                            is SystemControlUnsupportedException -> localizedContext().getString(
                                R.string.device_control_unsupported,
                            )
                            else -> error?.message?.takeIf(String::isNotBlank)
                                ?: localizedContext().getString(R.string.message_route_unavailable)
                        },
                    )
                }
            }
        }
    }

    private fun localizedContext(): Context = LocaleHelper.localized(context)
}

@Composable
fun WakeOnLanControlCard(
    selectedDevice: Device?,
    modifier: Modifier = Modifier,
    viewModel: WakeOnLanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recentMacs by viewModel.recentMacs.collectAsStateWithLifecycle()
    val support = selectedDevice?.let { viewModel.wakeOnLanSupport(it.deviceId) }
        ?: SystemControlSupport.UNKNOWN
    var targetMac by remember { mutableStateOf("") }
    var pendingMac by remember { mutableStateOf<String?>(null) }
    val normalizedMac = viewModel.normalizedMac(targetMac)
    val validMac = isValidWakeOnLanMac(normalizedMac)

    LaunchedEffect(selectedDevice?.deviceId) {
        viewModel.resetState()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.device_wake_on_lan_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.device_wake_on_lan_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedDevice == null) {
                Text(
                    text = stringResource(R.string.device_wake_on_lan_no_proxies),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (support == SystemControlSupport.TOO_OLD) {
                    StateMessage(text = stringResource(R.string.device_control_unsupported))
                }
                StateMessage(text = state.error)
                OutlinedTextField(
                    value = targetMac,
                    onValueChange = { targetMac = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.device_wake_on_lan_mac_label)) },
                    placeholder = { Text(stringResource(R.string.device_wake_on_lan_mac_placeholder)) },
                    singleLine = true,
                    enabled = support != SystemControlSupport.TOO_OLD,
                    shape = RoundedCornerShape(16.dp),
                    isError = targetMac.isNotBlank() && !validMac,
                )
                if (recentMacs.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        recentMacs.forEach { mac ->
                            FilterChip(
                                selected = false,
                                onClick = { targetMac = mac },
                                label = { Text(mac) },
                                enabled = support != SystemControlSupport.TOO_OLD,
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = support != SystemControlSupport.TOO_OLD,
                                    selected = false,
                                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                                    selectedBorderColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }
                if (targetMac.isNotBlank() && !validMac) {
                    StateMessage(text = stringResource(R.string.device_wake_on_lan_invalid_mac))
                }
                Button(
                    enabled = validMac &&
                        !state.submitting &&
                        support != SystemControlSupport.TOO_OLD,
                    onClick = { pendingMac = normalizedMac },
                ) {
                    Text(stringResource(R.string.device_wake_on_lan_send))
                }
            }
        }
    }

    val mac = pendingMac
    if (mac != null && selectedDevice != null) {
        AlertDialog(
            onDismissRequest = { pendingMac = null },
            title = { Text(stringResource(R.string.device_wake_on_lan_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.device_wake_on_lan_confirm_body,
                        mac,
                        selectedDevice.name.ifBlank { selectedDevice.deviceId },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingMac = null
                        viewModel.send(selectedDevice.deviceId, mac)
                    },
                ) {
                    Text(stringResource(R.string.device_wake_on_lan_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMac = null }) {
                    Text(stringResource(R.string.cancel_btn))
                }
            },
        )
    }
}
