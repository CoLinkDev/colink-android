package com.colink.android.ui.devicecontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.network.SystemControlSupport
import com.colink.android.network.message.SystemControlAction
import com.colink.android.ui.components.DevicePicker
import com.colink.android.ui.components.StateMessage
import com.colink.android.ui.components.devicesWithoutLocalDevice
import com.colink.android.ui.components.isComputerDevice

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DevicePowerControlCard(
    modifier: Modifier = Modifier,
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
    val support = viewModel.systemControlSupport(selectedDeviceId)
    var pendingAction by remember { mutableStateOf<SystemControlAction?>(null) }



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
                text = stringResource(R.string.device_power_control_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.device_power_control_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (availableDevices.isEmpty()) {
                Text(
                    text = stringResource(R.string.device_control_no_devices_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {

                if (support == SystemControlSupport.TOO_OLD) {
                    StateMessage(
                        text = stringResource(R.string.device_power_unsupported),
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
                        enabled = selectedDevice != null && !state.submitting && support != SystemControlSupport.TOO_OLD,
                        onClick = { pendingAction = SystemControlAction.Sleep },
                    )
                    PowerActionButton(
                        label = stringResource(R.string.device_power_lock),
                        icon = Icons.Default.Lock,
                        enabled = selectedDevice != null && !state.submitting && support != SystemControlSupport.TOO_OLD,
                        onClick = { pendingAction = SystemControlAction.Lock },
                    )
                    PowerActionButton(
                        label = stringResource(R.string.device_power_shutdown),
                        icon = Icons.Default.PowerSettingsNew,
                        enabled = selectedDevice != null && !state.submitting && support != SystemControlSupport.TOO_OLD,
                        destructive = true,
                        onClick = { pendingAction = SystemControlAction.Shutdown },
                    )
                }
            }
        }
    }

    val action = pendingAction
    if (action != null && selectedDevice != null) {
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                Text(stringResource(R.string.device_power_confirm_title, action.label()))
            },
            text = {
                Text(
                    stringResource(
                        R.string.device_power_confirm_body,
                        action.label(),
                        selectedDevice.name.ifBlank { selectedDevice.deviceId },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        viewModel.send(action)
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
            else -> error("Not a power control action")
        },
    )
