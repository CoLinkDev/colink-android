package com.colink.android.ui.devicecontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.ui.castboard.CastBoardControlCard
import com.colink.android.ui.castboard.CastBoardViewModel
import com.colink.android.ui.components.DevicePicker
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.ui.components.devicesWithoutLocalDevice
import com.colink.android.ui.components.isComputerDevice

@Composable
fun DeviceControlScreen(
    onStartCastBoard: (String) -> Unit,
    modifier: Modifier = Modifier,
    castBoardViewModel: CastBoardViewModel = hiltViewModel(),
    powerControlViewModel: DevicePowerControlViewModel = hiltViewModel(),
    mediaControlViewModel: DeviceMediaControlViewModel = hiltViewModel(),
) {
    val devices by powerControlViewModel.devices.collectAsStateWithLifecycle()
    val localDeviceId by powerControlViewModel.localDeviceId.collectAsStateWithLifecycle()
    val selectedDeviceId by powerControlViewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val availableDevices = remember(devices, localDeviceId) {
        devicesWithoutLocalDevice(devices, localDeviceId)
            .filter { it.online || it.lanAvailable }
    }
    val selectedDevice = remember(availableDevices, selectedDeviceId) {
        availableDevices.firstOrNull { it.deviceId == selectedDeviceId }
    }
    val selectedComputer = selectedDevice?.let(::isComputerDevice) == true

    LaunchedEffect(availableDevices, selectedDeviceId) {
        if (selectedDeviceId == null || availableDevices.none { it.deviceId == selectedDeviceId }) {
            availableDevices.firstOrNull()?.deviceId?.let { deviceId ->
                powerControlViewModel.selectDevice(deviceId)
                if (availableDevices.firstOrNull { it.deviceId == deviceId }?.let(::isComputerDevice) == true) {
                    castBoardViewModel.selectDevice(deviceId)
                }
            }
        }
    }

    LaunchedEffect(selectedDevice) {
        selectedDevice?.takeIf(::isComputerDevice)?.let { device ->
            val deviceId = device.deviceId
            mediaControlViewModel.selectDevice(deviceId)
            mediaControlViewModel.startSystemStatePolling()
        } ?: mediaControlViewModel.stopSystemStatePolling()
    }

    DisposableEffect(Unit) {
        onDispose(mediaControlViewModel::stopSystemStatePolling)
    }

    ScreenColumn(
        title = stringResource(R.string.nav_device_control),
        subtitle = stringResource(R.string.device_control_subtitle),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (availableDevices.isNotEmpty()) {
                DevicePicker(
                    devices = availableDevices,
                    selectedDeviceId = selectedDeviceId,
                    onSelectedDeviceChange = { deviceId ->
                        powerControlViewModel.selectDevice(deviceId)
                        if (availableDevices.firstOrNull { it.deviceId == deviceId }?.let(::isComputerDevice) == true) {
                            castBoardViewModel.selectDevice(deviceId)
                        }
                    },
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            key(selectedDeviceId) {
                if (selectedComputer) {
                    CastBoardControlCard(
                        onStartFullscreen = onStartCastBoard,
                        viewModel = castBoardViewModel,
                    )
                    DevicePowerControlCard(
                        viewModel = powerControlViewModel,
                    )
                }
                WakeOnLanControlCard(selectedDevice = selectedDevice)
                if (selectedComputer) {
                    DeviceMediaControlCard(
                        hasAvailableDevice = true,
                        viewModel = mediaControlViewModel,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
