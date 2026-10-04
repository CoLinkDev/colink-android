package com.colink.android.ui.castboard

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.ui.components.CoLinkTextField
import com.colink.android.ui.components.ContentGroupHeader
import com.colink.android.ui.components.ContentGroupItem
import com.colink.android.ui.components.ContentGroupSpacing
import com.colink.android.ui.components.contentGroupShape
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

private const val PLUGIN_ID_ARG = "pluginId"

data class CastBoardPluginConfigUiState(
    val saving: Boolean = false,
)

sealed interface CastBoardPluginConfigEvent {
    data object Saved : CastBoardPluginConfigEvent
    data class Failed(val failure: CastBoardPluginFailure) : CastBoardPluginConfigEvent
}

@HiltViewModel
class CastBoardPluginConfigViewModel @Inject constructor(
    private val pluginManager: CastBoardPluginManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val pluginId: String = savedStateHandle.get<String>(PLUGIN_ID_ARG).orEmpty()
    val plugins = pluginManager.plugins

    private val _uiState = MutableStateFlow(CastBoardPluginConfigUiState())
    val uiState = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<CastBoardPluginConfigEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    fun save(overrides: JsonObject) {
        if (_uiState.value.saving) return
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true) }
            try {
                pluginManager.updatePluginConfig(pluginId, overrides)
                _events.emit(CastBoardPluginConfigEvent.Saved)
            } catch (error: CastBoardPluginException) {
                _events.emit(CastBoardPluginConfigEvent.Failed(error.failure))
            } catch (_: Exception) {
                _events.emit(CastBoardPluginConfigEvent.Failed(CastBoardPluginFailure.Storage))
            } finally {
                _uiState.update { it.copy(saving = false) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CastBoardPluginConfigScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CastBoardPluginConfigViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val plugin = plugins.firstOrNull { it.manifest.id == viewModel.pluginId }
    val schema = plugin?.manifest?.configSchema
    val locale = context.resources.configuration.locales[0].toLanguageTag()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CastBoardPluginConfigEvent.Saved -> {
                    Toast.makeText(context, R.string.castboard_plugin_config_saved, Toast.LENGTH_SHORT).show()
                    onBack()
                }
                is CastBoardPluginConfigEvent.Failed -> Toast.makeText(
                    context,
                    event.failure.messageResource(),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    if (plugin == null || schema == null) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.castboard_plugin_configure)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back_desc),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.castboard_plugin_error_invalid_config),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val defaults = remember(schema) { schema.properties.mapValues { it.value.default } }
    var values by remember(plugin.manifest.id, plugin.configOverrides) {
        mutableStateOf(defaults + plugin.configOverrides)
    }
    val fields = schema.properties.entries.toList()
    val pluginName = localizedPluginText(plugin.manifest.name, locale)
    val valuesValid = fields.all { (name, field) ->
        values[name]?.let { validConfigValue(it, field) } == true
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.castboard_plugin_config_title, pluginName),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(enabled = !uiState.saving, onClick = onBack) {
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
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.saving,
                        onClick = { values = defaults },
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null)
                        Text(
                            text = stringResource(R.string.castboard_plugin_restore_defaults),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = valuesValid && !uiState.saving,
                        onClick = {
                            viewModel.save(JsonObject(values.mapNotNull { (name, value) ->
                                val field = schema.properties.getValue(name)
                                if (value == field.default) null else name to value
                            }.toMap()))
                        },
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Text(
                            text = stringResource(
                                if (uiState.saving) R.string.castboard_plugin_config_saving else R.string.save_btn,
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(ContentGroupSpacing),
        ) {
            if (fields.isNotEmpty()) {
                item(contentType = "config-fields") {
                    Column {
                        ContentGroupHeader(title = stringResource(R.string.castboard_plugin_configure))
                        fields.forEachIndexed { index, (name, field) ->
                            ContentGroupItem(
                                isFirst = index == 0,
                                isLast = index == fields.lastIndex,
                            ) {
                                PluginConfigFieldItem(
                                    name = name,
                                    field = field,
                                    value = checkNotNull(values[name]),
                                    locale = locale,
                                    enabled = !uiState.saving,
                                    onChange = { value -> values = values + (name to value) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginConfigFieldItem(
    name: String,
    field: CastBoardPluginConfigField,
    value: JsonElement,
    locale: String,
    enabled: Boolean,
    onChange: (JsonElement) -> Unit,
) {
    val title = localizedPluginText(field.title, locale).ifBlank { name }
    val description = localizedPluginText(field.description, locale)

    if (field.type == "boolean") {
        val checked = (value as? JsonPrimitive)?.booleanOrNull == true
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { onChange(JsonPrimitive(it)) },
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConfigFieldHeading(
                title = title,
                description = description,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
            )
        }
        return
    }

    if (field.type == "string" && field.enumValues != null) {
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val selected = (value as? JsonPrimitive)?.content.orEmpty()
        val selectedLabel = localizedPluginText(field.enumTitles?.get(selected), locale).ifBlank { selected }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { showDialog = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConfigFieldHeading(
                title = title,
                description = description,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (showDialog) {
            EnumOptionPickerDialog(
                title = title,
                options = field.enumValues,
                enumTitles = field.enumTitles,
                currentValue = selected,
                locale = locale,
                onSelect = { newValue ->
                    onChange(JsonPrimitive(newValue))
                    showDialog = false
                },
                onDismiss = { showDialog = false },
            )
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ConfigFieldHeading(title = title, description = description)
        when (field.type) {
            "string" -> {
                CoLinkTextField(
                    value = (value as JsonPrimitive).content,
                    onValueChange = { onChange(JsonPrimitive(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    singleLine = true,
                    visualTransformation = if (field.format == "password") {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                )
            }
            "number", "integer" -> NumberConfigField(
                field = field,
                value = value as JsonPrimitive,
                enabled = enabled,
                onChange = onChange,
            )
        }
    }
}

@Composable
private fun EnumOptionPickerDialog(
    title: String,
    options: List<String>,
    enumTitles: Map<String, Map<String, String>>?,
    currentValue: String,
    locale: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedOption by rememberSaveable(currentValue) { mutableStateOf(currentValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    items = options,
                    key = { _, option -> option },
                ) { index, option ->
                    val isFirst = index == 0
                    val isLast = index == options.lastIndex
                    val isSelected = selectedOption == option
                    val itemShape = contentGroupShape(isFirst = isFirst, isLast = isLast)

                    val containerColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceBright
                        },
                        label = "enumOptionContainerColor",
                    )

                    val optionLabel = localizedPluginText(enumTitles?.get(option), locale).ifBlank { option }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(itemShape)
                            .clickable { selectedOption = option },
                        shape = itemShape,
                        color = containerColor,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = optionLabel,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedOption = option },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelect(selectedOption) },
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
private fun ConfigFieldHeading(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NumberConfigField(
    field: CastBoardPluginConfigField,
    value: JsonPrimitive,
    enabled: Boolean,
    onChange: (JsonElement) -> Unit,
) {
    val number = value.doubleOrNull ?: 0.0
    val sliderMinimum = field.minimum?.toFloat()?.takeIf(Float::isFinite)
    val sliderMaximum = field.maximum?.toFloat()?.takeIf(Float::isFinite)
    val sliderValue = number.toFloat()
    val supportsIntegerSlider = field.type != "integer" ||
        field.minimum != null && field.minimum >= Long.MIN_VALUE.toDouble() &&
        field.maximum != null && field.maximum <= Long.MAX_VALUE.toDouble()
    if (sliderMinimum != null && sliderMaximum != null && sliderMinimum < sliderMaximum &&
        sliderValue.isFinite() && supportsIntegerSlider
    ) {
        Slider(
            value = sliderValue.coerceIn(sliderMinimum, sliderMaximum),
            onValueChange = {
                onChange(if (field.type == "integer") JsonPrimitive(it.toLong()) else JsonPrimitive(it.toDouble()))
            },
            enabled = enabled,
            valueRange = sliderMinimum..sliderMaximum,
        )
    }
    CoLinkTextField(
        value = if (field.type == "integer") number.toLong().toString() else number.toString(),
        onValueChange = { input ->
            if (field.type == "integer") {
                input.toLongOrNull()?.let { onChange(JsonPrimitive(it)) }
            } else {
                input.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { onChange(JsonPrimitive(it)) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

private fun validConfigValue(value: JsonElement, field: CastBoardPluginConfigField): Boolean {
    val primitive = value as? JsonPrimitive ?: return false
    return when (field.type) {
        "boolean" -> !primitive.isString && primitive.booleanOrNull != null
        "string" -> primitive.isString && field.enumValues?.contains(primitive.content) != false
        "number", "integer" -> {
            if (primitive.isString) return false
            val number = primitive.doubleOrNull ?: return false
            number.isFinite() &&
                (field.type != "integer" || number % 1.0 == 0.0) &&
                (field.minimum == null || number >= field.minimum) &&
                (field.maximum == null || number <= field.maximum)
        }
        else -> false
    }
}
