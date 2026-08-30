package com.colink.android.ui.devicecontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
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
fun DeviceMediaControlCard(
    hasAvailableDevice: Boolean,
    modifier: Modifier = Modifier,
    support: SystemControlSupport? = null,
    querySupport: SystemControlSupport? = null,
    viewModel: DeviceMediaControlViewModel = hiltViewModel(),
    shape: Shape = MaterialTheme.shapes.large,
) {
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeSupport = support ?: viewModel.mediaControlSupport(selectedDeviceId)
    val activeQuerySupport = querySupport ?: viewModel.systemControlQuerySupport(selectedDeviceId)
    val stateQueryEnabled = activeQuerySupport == SystemControlSupport.SUPPORTED
    val enabled = hasAvailableDevice &&
        selectedDeviceId != null &&
        !state.submitting &&
        !state.querying &&
        activeSupport == SystemControlSupport.SUPPORTED
    val playing = state.playback == "playing"

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (activeSupport == SystemControlSupport.TOO_OLD) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        shape = shape,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column {
                    Text(
                        text = stringResource(R.string.device_media_control_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.device_media_control_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(100),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PillIconButton(
                                icon = Icons.Default.SkipPrevious,
                                contentDescription = stringResource(R.string.device_media_previous),
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.send(SystemControlAction.Previous) },
                            )
                            PillIconButton(
                                icon = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(
                                    if (playing) R.string.device_media_pause else R.string.device_media_play,
                                ),
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    viewModel.send(
                                        if (playing) SystemControlAction.Pause else SystemControlAction.Play,
                                    )
                                },
                            )
                            PillIconButton(
                                icon = Icons.Default.SkipNext,
                                contentDescription = stringResource(R.string.device_media_next),
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.send(SystemControlAction.Next) },
                            )
                            PillIconButton(
                                icon = Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = stringResource(R.string.device_media_mute),
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.send(SystemControlAction.Mute) },
                            )
                        }
                    }
                }
                if (stateQueryEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.device_media_volume, state.volume),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
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
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
