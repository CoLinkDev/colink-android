package com.colink.android.ui.devicecontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.network.SystemControlSupport
import com.colink.android.network.message.SystemControlAction
import com.colink.android.ui.components.StateMessage
import com.colink.android.ui.components.devicesWithoutLocalDevice
import com.colink.android.ui.components.isComputerDevice

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DevicePowerControlCard(
    modifier: Modifier = Modifier,
    support: SystemControlSupport? = null,
    viewModel: DevicePowerControlViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val localDeviceId by viewModel.localDeviceId.collectAsStateWithLifecycle()
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val availableDevices = remember(devices, localDeviceId) {
        devicesWithoutLocalDevice(devices, localDeviceId)
            .filter { (it.online || it.lanAvailable) && isComputerDevice(it) }
    }
    val selectedDevice = remember(availableDevices, selectedDeviceId) {
        availableDevices.firstOrNull { it.deviceId == selectedDeviceId }
    }
    val activeSupport = support ?: viewModel.systemControlSupport(selectedDeviceId)
    var pendingAction by remember { mutableStateOf<SystemControlAction?>(null) }
    var delaySeconds by remember(pendingAction) { mutableStateOf("") }
    val delayedPowerSupport = viewModel.delayedPowerControlSupport(selectedDeviceId)
    val delay = delaySeconds.toIntOrNull()
    val delayInputValid = delaySeconds.isBlank() || (delay != null && delay >= 0)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (activeSupport == SystemControlSupport.TOO_OLD) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.device_power_control_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.device_power_control_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.pendingPower?.let { pendingPower ->
                val remainingMs = state.pendingPowerRemainingMs ?: pendingPower.remainingMs
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Column(
                            modifier = Modifier.padding(start = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.device_power_scheduled_action,
                                    pendingPowerActionLabel(pendingPower.action),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(
                                    R.string.device_power_scheduled_remaining,
                                    (remainingMs + 999) / 1_000,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (availableDevices.isEmpty()) {
                Text(
                    text = stringResource(R.string.device_control_no_devices_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {

                if (activeSupport == SystemControlSupport.TOO_OLD) {
                    StateMessage(
                        text = stringResource(R.string.device_control_unsupported),
                    )
                }
                StateMessage(text = state.error)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PowerActionButton(
                        label = stringResource(R.string.device_power_sleep),
                        icon = Icons.Default.Bedtime,
                        enabled = selectedDevice != null && !state.submitting && activeSupport != SystemControlSupport.TOO_OLD,
                        onClick = { pendingAction = SystemControlAction.Sleep },
                    )
                    PowerActionButton(
                        label = stringResource(R.string.device_power_lock),
                        icon = Icons.Default.Lock,
                        enabled = selectedDevice != null && !state.submitting && activeSupport != SystemControlSupport.TOO_OLD,
                        onClick = { pendingAction = SystemControlAction.Lock },
                    )
                    PowerActionButton(
                        label = stringResource(R.string.device_power_shutdown),
                        icon = Icons.Default.PowerSettingsNew,
                        enabled = selectedDevice != null && !state.submitting && activeSupport != SystemControlSupport.TOO_OLD,
                        destructive = true,
                        onClick = { pendingAction = SystemControlAction.Shutdown },
                    )
                    PowerActionButton(
                        label = stringResource(R.string.device_power_cancel_scheduled),
                        icon = Icons.Default.PowerSettingsNew,
                        enabled = selectedDevice != null &&
                            !state.submitting &&
                            delayedPowerSupport == SystemControlSupport.SUPPORTED,
                        onClick = { pendingAction = SystemControlAction.CancelPower },
                    )
                }
            }
        }
    }

    val action = pendingAction
    if (action != null && selectedDevice != null) {
        val showDelayInput = action != SystemControlAction.CancelPower && delayedPowerSupport == SystemControlSupport.SUPPORTED
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                Text(stringResource(R.string.device_power_confirm_title, action.label()))
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.device_power_confirm_body,
                            action.label(),
                            selectedDevice.name.ifBlank { selectedDevice.deviceId },
                        ),
                    )
                    if (showDelayInput) {
                        OutlinedTextField(
                            value = delaySeconds,
                            onValueChange = { delaySeconds = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.device_power_delay_seconds)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(16.dp),
                            isError = !delayInputValid,
                            enabled = !state.submitting,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !showDelayInput || delayInputValid,
                    onClick = {
                        val currentAction = action
                        val currentDelay = if (showDelayInput) delay else null
                        pendingAction = null
                        viewModel.send(currentAction, currentDelay)
                    },
                ) {
                    Text(action.label())
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.cancel_btn))
                }
            },
        )
    }
}

@Composable
private fun PowerActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SystemControlAction.label(): String =
    stringResource(
        when (this) {
            SystemControlAction.Sleep -> R.string.device_power_sleep
            SystemControlAction.Shutdown -> R.string.device_power_shutdown
            SystemControlAction.Lock -> R.string.device_power_lock
            SystemControlAction.CancelPower -> R.string.device_power_cancel_scheduled
            else -> error("Not a power control action")
        },
    )

@Composable
private fun pendingPowerActionLabel(action: String): String =
    SystemControlAction.fromWireValue(action)?.label() ?: action
