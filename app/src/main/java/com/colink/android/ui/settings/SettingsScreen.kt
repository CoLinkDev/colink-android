package com.colink.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.BuildConfig
import com.colink.android.R
import com.colink.android.ui.components.AppUpdateDialog
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.util.CoLinkLog
import com.colink.android.util.normalizeServerUrl

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var language by rememberSaveable { mutableStateOf("system") }
    var showServerUrlDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings.language) {
        language = settings.language
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearMessage()
    }

    AppUpdateDialog(
        update = uiState.availableUpdate,
        onDismiss = viewModel::dismissUpdate,
    )
    if (showServerUrlDialog) {
        ServerUrlDialog(
            initialServerUrl = settings.serverUrl,
            onDismiss = { showServerUrlDialog = false },
            onSave = {
                viewModel.saveServerUrl(it)
                showServerUrlDialog = false
            },
        )
    }

    ScreenColumn(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.settings_subtitle),
        modifier = modifier,
    ) {
        val languages = listOf(
            "system" to "System Default",
            "en" to "English (English)",
            "zh-CN" to "简体中文 (Simplified Chinese)",
            "zh-TW" to "繁體中文 (Traditional Chinese)",
            "ja" to "日本語 (Japanese)",
            "ko" to "한국어 (Korean)",
            "es" to "Español (Spanish)",
            "de" to "Deutsch (German)",
            "ru" to "Русский (Russian)",
        )
        val currentLanguageLabel = languages.find { it.first == language }?.second.orEmpty()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { showServerUrlDialog = true },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.server_url_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = settings.serverUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Language Dropdown
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = currentLanguageLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.language_label)) },
                    leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    }
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    languages.forEach { (code, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                language = code
                                expanded = false
                                viewModel.updateLanguage(code)
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_clipboard_sync_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_clipboard_sync_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = settings.enableClipboardSync,
                    onCheckedChange = viewModel::updateClipboardSync
                )
            }

            AboutCard(
                checkingUpdate = uiState.checkingUpdate,
                onCheckForUpdate = viewModel::checkForUpdate,
                onProjectClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
                    }.onFailure { error ->
                        CoLinkLog.w("Settings", "open project url failed", error)
                    }
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
private const val PROJECT_URL = "https://github.com/CoLinkDev/colink-android"

@Composable
private fun ServerUrlDialog(
    initialServerUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var serverUrl by rememberSaveable(initialServerUrl) { mutableStateOf(initialServerUrl) }
    var invalidUrl by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_server_url_title)) },
        text = {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    invalidUrl = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.server_url_label)) },
                leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = false,
                maxLines = 3,
                shape = RoundedCornerShape(16.dp),
                isError = invalidUrl,
                supportingText = if (invalidUrl) {
                    { Text(stringResource(R.string.err_server_url_invalid)) }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalizedServerUrl = normalizeServerUrl(serverUrl)
                    if (normalizedServerUrl == null) {
                        invalidUrl = true
                    } else {
                        onSave(normalizedServerUrl)
                    }
                },
            ) {
                Text(stringResource(R.string.save_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_btn))
            }
        },
    )
}

@Composable
private fun AboutCard(
    checkingUpdate: Boolean,
    onCheckForUpdate: () -> Unit,
    onProjectClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = stringResource(R.string.settings_about_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            InfoRow(label = stringResource(R.string.settings_project_url), value = PROJECT_URL)
            InfoRow(label = stringResource(R.string.settings_version), value = BuildConfig.VERSION_NAME)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onCheckForUpdate,
                    enabled = !checkingUpdate,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (checkingUpdate) {
                            stringResource(R.string.update_checking_btn)
                        } else {
                            stringResource(R.string.update_check_btn)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onProjectClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_open_project),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
