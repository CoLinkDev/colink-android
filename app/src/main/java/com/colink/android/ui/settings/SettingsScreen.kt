package com.colink.android.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.domain.model.AppSettings
import com.colink.android.ui.components.ScreenColumn

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var autoStart by rememberSaveable { mutableStateOf(false) }
    var lanDiscovery by rememberSaveable { mutableStateOf(true) }
    var notifications by rememberSaveable { mutableStateOf(true) }
    var language by rememberSaveable { mutableStateOf("system") }

    LaunchedEffect(settings) {
        serverUrl = settings.serverUrl
        autoStart = settings.autoStartOnBoot
        lanDiscovery = settings.lanDiscovery
        notifications = settings.notifications
        language = settings.language
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearMessage()
    }

    ScreenColumn(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.settings_subtitle),
        modifier = modifier,
    ) {
        val systemLanguage = stringResource(R.string.lang_system)
        val languages = listOf(
            "system" to systemLanguage,
            "en" to stringResource(R.string.lang_english),
            "zh-CN" to stringResource(R.string.lang_simplified_chinese),
            "zh-TW" to stringResource(R.string.lang_traditional_chinese),
            "ja" to stringResource(R.string.lang_japanese),
            "ko" to stringResource(R.string.lang_korean),
            "es" to stringResource(R.string.lang_spanish),
            "de" to stringResource(R.string.lang_german),
            "ru" to stringResource(R.string.lang_russian),
        )
        val currentLanguageLabel = languages.find { it.first == language }?.second.orEmpty()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.server_url_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )

            // Language Dropdown
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = currentLanguageLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.language_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.clickable { expanded = !expanded }
                        )
                    }
                )
                // Transparent overlay to detect clicks on the text field
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { expanded = !expanded }
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    languages.forEach { (code, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                language = code
                                expanded = false
                            }
                        )
                    }
                }
            }

            SettingSwitchRow(
                icon = Icons.Default.Lan,
                label = stringResource(R.string.lan_discovery_label),
                body = stringResource(R.string.lan_discovery_body),
                checked = lanDiscovery,
                onCheckedChange = { lanDiscovery = it },
            )
            SettingSwitchRow(
                icon = Icons.Default.Notifications,
                label = stringResource(R.string.notifications_label),
                body = stringResource(R.string.notifications_body),
                checked = notifications,
                onCheckedChange = { notifications = it },
            )
            SettingSwitchRow(
                icon = Icons.Default.PowerSettingsNew,
                label = stringResource(R.string.auto_start_label),
                body = stringResource(R.string.auto_start_body),
                checked = autoStart,
                onCheckedChange = { autoStart = it },
            )

            Button(
                onClick = {
                    viewModel.save(
                        AppSettings(
                            serverUrl = serverUrl,
                            deviceName = settings.deviceName,
                            autoStartOnBoot = autoStart,
                            lanDiscovery = lanDiscovery,
                            notifications = notifications,
                            language = language,
                        ),
                    )
                },
                enabled = !uiState.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Text(
                    text = stringResource(R.string.save_btn),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    label: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
