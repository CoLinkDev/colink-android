package com.colink.android.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.colink.android.R
import com.colink.android.network.RemoteCameraSupport
import com.colink.android.ui.components.StateMessage

@Composable
fun CameraControlCard(
    deviceId: String,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    support: RemoteCameraSupport = RemoteCameraSupport.SUPPORTED,
) {
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
                text = stringResource(R.string.device_control_camera),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.camera_control_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (support == RemoteCameraSupport.TOO_OLD) {
                StateMessage(text = stringResource(R.string.device_control_unsupported))
            }
            Button(
                onClick = { onOpen(deviceId) },
                enabled = support != RemoteCameraSupport.TOO_OLD,
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Text(
                    text = stringResource(R.string.camera_open),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
