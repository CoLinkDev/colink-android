package com.colink.android.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.BuildConfig
import com.colink.android.R
import com.colink.android.ui.components.AppUpdateDialog
import com.colink.android.ui.components.CoLinkTextField
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.util.CoLinkLog
import com.colink.android.util.normalizeServerUrl

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var language by rememberSaveable { mutableStateOf("system") }
    var showServerUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguagePickerDialog by rememberSaveable { mutableStateOf(false) }
    var showDiagnosticExportDialog by rememberSaveable { mutableStateOf(false) }
    var diagnosticExportFromMillis by remember { mutableStateOf<Long?>(null) }
    val languages = listOf(
        "system" to stringResource(R.string.language_system_default),
        "en" to "English (English)",
        "zh-CN" to "简体中文 (Simplified Chinese)",
        "zh-TW" to "繁體中文 (Traditional Chinese)",
        "ja" to "日本語 (Japanese)",
        "ko" to "한국어 (Korean)",
        "es" to "Español (Spanish)",
        "de" to "Deutsch (German)",
        "ru" to "Русский (Russian)",
    )
    val diagnosticExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val fromMillis = diagnosticExportFromMillis
        diagnosticExportFromMillis = null
        if (uri != null && fromMillis != null) {
            viewModel.exportDiagnostics(uri, fromMillis)
        }
    }

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
        downloadState = uiState.updateDownloadState,
        onDismiss = viewModel::dismissUpdate,
        onUpdate = viewModel::startUpdate,
        onInstallerReturned = viewModel::onInstallerReturned,
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
    if (showLanguagePickerDialog) {
        LanguagePickerDialog(
            languages = languages,
            currentCode = language,
            onSelect = { code ->
                language = code
                viewModel.updateLanguage(code)
                showLanguagePickerDialog = false
            },
            onDismiss = { showLanguagePickerDialog = false },
        )
    }
    if (showDiagnosticExportDialog) {
        DiagnosticExportDialog(
            onDismiss = { showDiagnosticExportDialog = false },
            onExport = { fromMillis ->
                showDiagnosticExportDialog = false
                diagnosticExportFromMillis = fromMillis
                diagnosticExportLauncher.launch("colink-diagnostics-${System.currentTimeMillis()}.log")
            },
        )
    }

    ScreenColumn(
        title = stringResource(R.string.settings_title),
        icon = Icons.Default.Settings,
        modifier = modifier,
        showLandscapeAccountAction = false,
    ) {
        val currentLanguageLabel = languages.find { it.first == language }?.second.orEmpty()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsGroup(title = stringResource(R.string.settings_section_connection)) {
                SettingsItem(
                    icon = Icons.Default.Dns,
                    title = stringResource(R.string.server_url_label),
                    subtitle = settings.serverUrl,
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { showServerUrlDialog = true },
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_section_behavior)) {
                SettingsSwitchItem(
                    icon = Icons.Default.ContentPaste,
                    title = stringResource(R.string.settings_clipboard_sync_title),
                    subtitle = stringResource(R.string.settings_clipboard_sync_desc),
                    checked = settings.enableClipboardSync,
                    onCheckedChange = viewModel::updateClipboardSync,
                )
                SettingsGroupSeparator()
                SettingsSwitchItem(
                    icon = Icons.Default.DownloadForOffline,
                    title = stringResource(R.string.settings_auto_accept_file_offers_title),
                    subtitle = stringResource(R.string.settings_auto_accept_file_offers_desc),
                    checked = settings.autoAcceptFileOffers,
                    onCheckedChange = viewModel::updateAutoAcceptFileOffers,
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_section_app)) {
                SettingsItem(
                    icon = Icons.Default.Translate,
                    title = stringResource(R.string.language_label),
                    subtitle = currentLanguageLabel,
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { showLanguagePickerDialog = true },
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_section_about)) {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_version),
                    subtitle = BuildConfig.VERSION_NAME,
                )
                SettingsGroupSeparator()
                SettingsItem(
                    icon = Icons.Default.Refresh,
                    title = if (uiState.checkingUpdate) {
                        stringResource(R.string.update_checking_btn)
                    } else {
                        stringResource(R.string.update_check_btn)
                    },
                    subtitle = stringResource(R.string.update_check_description),
                    onClick = if (uiState.checkingUpdate) null else viewModel::checkForUpdate,
                )
                SettingsGroupSeparator()
                SettingsItem(
                    icon = Icons.Default.FileDownload,
                    title = stringResource(R.string.diagnostics_export_title),
                    subtitle = stringResource(R.string.diagnostics_export_description),
                    onClick = { showDiagnosticExportDialog = true },
                )
                SettingsGroupSeparator()
                SettingsItem(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    title = stringResource(R.string.settings_open_project),
                    subtitle = PROJECT_URL,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
                        }.onFailure { error ->
                            CoLinkLog.w("Settings", "open project url failed", error)
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DiagnosticExportDialog(
    onDismiss: () -> Unit,
    onExport: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.diagnostics_export_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_export_range_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    DiagnosticRangeItem(
                        icon = Icons.Default.Schedule,
                        title = stringResource(R.string.diagnostics_export_last_day),
                        onClick = { onExport(System.currentTimeMillis() - 24 * 60 * 60 * 1000L) },
                    )
                    SettingsGroupSeparator(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    DiagnosticRangeItem(
                        icon = Icons.Default.DateRange,
                        title = stringResource(R.string.diagnostics_export_last_week),
                        onClick = { onExport(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L) },
                    )
                    SettingsGroupSeparator(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    DiagnosticRangeItem(
                        icon = Icons.Default.History,
                        title = stringResource(R.string.diagnostics_export_all),
                        onClick = { onExport(0L) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_btn))
            }
        },
    )
}

@Composable
private fun DiagnosticRangeItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CoLinkTextField(
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
                    isError = invalidUrl,
                    supportingText = if (invalidUrl) {
                        { Text(stringResource(R.string.err_server_url_invalid)) }
                    } else {
                        null
                    },
                )
                TextButton(
                    onClick = {
                        serverUrl = BuildConfig.SERVER_BASE_URL
                        invalidUrl = false
                    },
                    modifier = Modifier
                        .align(Alignment.Start)
                        .heightIn(min = 0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.use_official_server),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
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
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsGroupSeparator(
    color: Color = MaterialTheme.colorScheme.surface,
) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(color),
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val interactionModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .then(interactionModifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsItem(
        icon = icon,
        title = title,
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        subtitle = subtitle,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
    )
}

@Composable
private fun LanguagePickerDialog(
    languages: List<Pair<String, String>>,
    currentCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_label)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .selectableGroup(),
            ) {
                items(
                    items = languages,
                    key = { (code, _) -> code },
                ) { (code, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = code == currentCode,
                                role = Role.RadioButton,
                                onClick = { onSelect(code) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = code == currentCode,
                            onClick = null,
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_btn))
            }
        },
    )
}
