package com.colink.android.ui.devicecontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.network.SystemControlSupport
import com.colink.android.network.message.SystemControlAction
import com.colink.android.ui.components.StateMessage
import kotlin.math.roundToInt

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DeviceMediaControlCard(
    hasAvailableDevice: Boolean,
    modifier: Modifier = Modifier,
    viewModel: DeviceMediaControlViewModel = hiltViewModel(),
) {
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val support = viewModel.mediaControlSupport(selectedDeviceId)
    val enabled = hasAvailableDevice &&
        selectedDeviceId != null &&
        !state.submitting &&
        support == SystemControlSupport.SUPPORTED
    val playing = state.playback == "playing"

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
                text = stringResource(R.string.device_media_control_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.device_media_control_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!hasAvailableDevice) {
                Text(
                    text = stringResource(R.string.device_control_no_devices_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (support == SystemControlSupport.TOO_OLD) {
                    StateMessage(text = stringResource(R.string.device_control_unsupported))
                }
                StateMessage(text = state.error)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MediaActionButton(
                        label = stringResource(R.string.device_media_previous),
                        icon = Icons.Default.SkipPrevious,
                        enabled = enabled,
                        onClick = { viewModel.send(SystemControlAction.Previous) },
                    )
                    MediaActionButton(
                        label = stringResource(R.string.device_media_next),
                        icon = Icons.Default.SkipNext,
                        enabled = enabled,
                        onClick = { viewModel.send(SystemControlAction.Next) },
                    )
                    MediaActionButton(
                        label = stringResource(
                            if (playing) R.string.device_media_pause else R.string.device_media_play,
                        ),
                        icon = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        enabled = enabled,
                        onClick = {
                            viewModel.send(
                                if (playing) SystemControlAction.Pause else SystemControlAction.Play,
                            )
                        },
                    )
                    MediaActionButton(
                        label = stringResource(R.string.device_media_mute),
                        icon = Icons.AutoMirrored.Filled.VolumeMute,
                        enabled = enabled,
                        onClick = { viewModel.send(SystemControlAction.Mute) },
                    )
                }
                Text(
                    text = stringResource(R.string.device_media_volume, state.volume),
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = state.volume.toFloat(),
                    onValueChange = { viewModel.updateVolume(it.roundToInt()) },
                    onValueChangeFinished = {
                        viewModel.send(SystemControlAction.SetVolume, state.volume)
                    },
                    valueRange = 0f..100f,
                    steps = 99,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun MediaActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        enabled = enabled,
        onClick = onClick,
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
