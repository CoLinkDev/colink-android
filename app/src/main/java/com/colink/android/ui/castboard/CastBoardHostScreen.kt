package com.colink.android.ui.castboard

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.ui.components.ContentGroupHeader
import com.colink.android.ui.components.ContentGroupItem
import com.colink.android.ui.components.ContentGroupSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEVICE_ID_ARG = "deviceId"

data class CastBoardHostUiState(
    val importing: Boolean = false,
    val actingPluginId: String? = null,
)

sealed interface CastBoardHostEvent {
    data class Imported(val plugin: CastBoardPluginItem) : CastBoardHostEvent
    data object Deleted : CastBoardHostEvent
    data class Failed(val failure: CastBoardPluginFailure) : CastBoardHostEvent
}

@HiltViewModel
class CastBoardHostViewModel @Inject constructor(
    private val pluginManager: CastBoardPluginManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val deviceId: String = savedStateHandle.get<String>(DEVICE_ID_ARG).orEmpty()
    val plugins = pluginManager.plugins

    private val _uiState = MutableStateFlow(CastBoardHostUiState())
    val uiState = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<CastBoardHostEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    fun importPlugin(uri: Uri) {
        if (_uiState.value.importing || _uiState.value.actingPluginId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(importing = true) }
            try {
                _events.emit(CastBoardHostEvent.Imported(pluginManager.importPlugin(uri)))
            } catch (error: CastBoardPluginException) {
                _events.emit(CastBoardHostEvent.Failed(error.failure))
            } catch (_: Exception) {
                _events.emit(CastBoardHostEvent.Failed(CastBoardPluginFailure.Storage))
            } finally {
                _uiState.update { it.copy(importing = false) }
            }
        }
    }

    fun setPluginEnabled(plugin: CastBoardPluginItem, enabled: Boolean) {
        if (_uiState.value.importing || _uiState.value.actingPluginId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(actingPluginId = plugin.manifest.id) }
            try {
                pluginManager.setEnabled(plugin.manifest.id, enabled)
            } catch (error: CastBoardPluginException) {
                _events.emit(CastBoardHostEvent.Failed(error.failure))
            } catch (_: Exception) {
                _events.emit(CastBoardHostEvent.Failed(CastBoardPluginFailure.Storage))
            } finally {
                _uiState.update { it.copy(actingPluginId = null) }
            }
        }
    }

    fun deletePlugin(plugin: CastBoardPluginItem) {
        if (_uiState.value.importing || _uiState.value.actingPluginId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(actingPluginId = plugin.manifest.id) }
            try {
                pluginManager.delete(plugin.manifest.id)
                _events.emit(CastBoardHostEvent.Deleted)
            } catch (error: CastBoardPluginException) {
                _events.emit(CastBoardHostEvent.Failed(error.failure))
            } catch (_: Exception) {
                _events.emit(CastBoardHostEvent.Failed(CastBoardPluginFailure.Storage))
            } finally {
                _uiState.update { it.copy(actingPluginId = null) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CastBoardHostScreen(
    onBack: () -> Unit,
    onLaunch: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CastBoardHostViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::importPlugin)
    }
    var pendingDelete by remember { mutableStateOf<CastBoardPluginItem?>(null) }
    val locale = context.resources.configuration.locales[0].toLanguageTag()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CastBoardHostEvent.Imported -> Toast.makeText(
                    context,
                    context.getString(
                        R.string.castboard_plugin_imported,
                        localizedPluginText(event.plugin.manifest.name, locale),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                CastBoardHostEvent.Deleted -> Toast.makeText(
                    context,
                    R.string.castboard_plugin_deleted,
                    Toast.LENGTH_SHORT,
                ).show()
                is CastBoardHostEvent.Failed -> Toast.makeText(
                    context,
                    event.failure.messageResource(),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_castboard)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_desc),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(ContentGroupSpacing),
        ) {
            item(contentType = "launch-group") {
                Column {
                    ContentGroupHeader(title = stringResource(R.string.castboard_section_launch))
                    ContentGroupItem(isFirst = true, isLast = true) {
                        LaunchItem(
                            fallbackDeviceId = viewModel.deviceId,
                            onLaunch = { onLaunch(viewModel.deviceId) },
                        )
                    }
                }
            }

            item(contentType = "plugin-group") {
                Column {
                    ContentGroupHeader(title = stringResource(R.string.castboard_section_plugins))
                    ContentGroupItem(isFirst = true, isLast = plugins.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = !uiState.importing && uiState.actingPluginId == null,
                                ) { launcher.launch("application/zip") }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (uiState.importing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Default.UploadFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (uiState.importing) {
                                        stringResource(R.string.castboard_plugin_importing)
                                    } else {
                                        stringResource(R.string.castboard_plugin_import)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.castboard_plugin_import_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    plugins.forEachIndexed { index, plugin ->
                        ContentGroupItem(isFirst = false, isLast = index == plugins.lastIndex) {
                            PluginItem(
                                plugin = plugin,
                                locale = locale,
                                enabled = !uiState.importing && uiState.actingPluginId == null,
                                onEnabledChange = { enabled -> viewModel.setPluginEnabled(plugin, enabled) },
                                onDelete = { pendingDelete = plugin },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { plugin ->
        val name = localizedPluginText(plugin.manifest.name, locale)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.castboard_plugin_delete_title)) },
            text = { Text(stringResource(R.string.castboard_plugin_delete_description, name)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePlugin(plugin)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.delete_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel_btn))
                }
            },
        )
    }
}

@Composable
private fun LaunchItem(
    fallbackDeviceId: String,
    onLaunch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = fallbackDeviceId.isNotBlank(), onClick = onLaunch)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.castboard_start),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.castboard_launch_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PluginItem(
    plugin: CastBoardPluginItem,
    locale: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val name = localizedPluginText(plugin.manifest.name, locale)
    val description = localizedPluginText(plugin.manifest.description, locale)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(stringResource(
                        if (plugin.manifest.type == "navigable") {
                            R.string.castboard_plugin_type_navigable
                        } else {
                            R.string.castboard_plugin_type_transient
                        },
                    ))
                    append(" · v")
                    append(plugin.manifest.version)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = plugin.enabled,
            enabled = enabled,
            onCheckedChange = onEnabledChange,
        )
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete_btn),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun localizedPluginText(values: Map<String, String>?, locale: String): String {
    if (values.isNullOrEmpty()) return ""
    val normalized = locale.lowercase(Locale.ROOT)
    values.entries.firstOrNull { it.key.lowercase(Locale.ROOT) == normalized }?.value?.let { return it }
    val base = normalized.substringBefore('-')
    values.entries.firstOrNull { it.key.lowercase(Locale.ROOT) == base }?.value?.let { return it }
    return values["en"] ?: values.values.first()
}

private fun CastBoardPluginFailure.messageResource(): Int = when (this) {
    CastBoardPluginFailure.InvalidArchive -> R.string.castboard_plugin_error_invalid_archive
    CastBoardPluginFailure.InvalidManifest -> R.string.castboard_plugin_error_invalid_manifest
    CastBoardPluginFailure.Incompatible -> R.string.castboard_plugin_error_incompatible
    CastBoardPluginFailure.Storage -> R.string.castboard_plugin_error_storage
    CastBoardPluginFailure.NotFound -> R.string.castboard_plugin_error_not_found
}
