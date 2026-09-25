package com.colink.android.ui.notes.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LabelOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.colink.android.R
import com.colink.android.domain.model.NoteTag
import com.colink.android.ui.components.CoLinkTextField

private val ChipSpacing = 8.dp
private val ChipElementPadding = 8.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteTagsBottomSheet(
    tags: List<NoteTag>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val trimmed = input.trim()
    val exactMatch = tags.any { it.name.equals(trimmed, ignoreCase = true) }
    val filtered = tags.filter { trimmed.isBlank() || it.name.contains(trimmed, ignoreCase = true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val chipLabelStyle = MaterialTheme.typography.labelLarge

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.notes_tags),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.notes_tags_summary, tags.size, selectedIds.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            CoLinkTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.notes_search_or_create_tag)) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.notes_clear_search))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(
                visible = trimmed.isNotEmpty() && !exactMatch,
                enter = fadeIn(tween(160)) + expandVertically(tween(160), expandFrom = Alignment.Top),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(120), shrinkTowards = Alignment.Top),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    CreateTagDashedChip(
                        label = stringResource(R.string.notes_create_tag_named, trimmed),
                        onClick = { onCreate(trimmed); input = "" },
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(tween(200))
                        .heightIn(max = 360.dp),
                ) {
                    val rowMaxWidth = maxWidth
                    val rowWidthPx = constraints.maxWidth
                    val chipSpacingPx = with(density) { ChipSpacing.roundToPx() }
                    AnimatedContent(
                        targetState = filtered,
                        transitionSpec = {
                            fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                        },
                        label = "note_tag_candidates",
                    ) { candidateTags ->
                        if (candidateTags.isEmpty()) {
                            EmptyTagsState(searching = trimmed.isNotEmpty())
                        } else {
                            val selectedWidths = remember(
                                candidateTags,
                                density.density,
                                density.fontScale,
                                chipLabelStyle,
                                textMeasurer,
                            ) {
                                candidateTags.associate { tag ->
                                    tag.id to estimateSelectedChipWidthPx(
                                        name = tag.name,
                                        textMeasurer = textMeasurer,
                                        textStyle = chipLabelStyle,
                                        density = density,
                                    )
                                }
                            }
                            val rows = remember(candidateTags, rowWidthPx, chipSpacingPx, selectedWidths) {
                                partitionTagsIntoRows(
                                    tags = candidateTags,
                                    maxRowWidth = rowWidthPx,
                                    selectedChipWidths = selectedWidths,
                                    spacing = chipSpacingPx,
                                )
                            }
                            Column(
                                verticalArrangement = Arrangement.spacedBy(ChipSpacing),
                                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            ) {
                                rows.forEach { rowTags ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        rowTags.forEach { tag ->
                                            val selected = tag.id in selectedIds
                                            FilterChip(
                                                selected = selected,
                                                onClick = { onToggle(tag.id) },
                                                label = {
                                                    Text(
                                                        text = tag.name,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                leadingIcon = if (selected) {
                                                    {
                                                        Icon(
                                                            Icons.Default.Check,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                                        )
                                                    }
                                                } else null,
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                ),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier
                                                    .animateContentSize(tween(120))
                                                    .widthIn(max = rowMaxWidth),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateTagDashedChip(
    label: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .wrapContentSize()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .dashedBorder(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 1.2.dp,
                cornerRadius = 8.dp,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyTagsState(searching: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (searching) Icons.Outlined.SearchOff else Icons.AutoMirrored.Outlined.LabelOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
            Text(
                stringResource(
                    if (searching) R.string.notes_no_matching_tags
                    else R.string.notes_no_tags_yet,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun estimateSelectedChipWidthPx(
    name: String,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    density: Density,
): Int {
    val textWidthPx = textMeasurer.measure(
        text = AnnotatedString(name),
        style = textStyle,
        maxLines = 1,
    ).size.width
    val chromeWidthPx = with(density) {
        // FilterChip has 8dp outer and label padding on both sides, plus its 18dp leading icon.
        4 * ChipElementPadding.roundToPx() + FilterChipDefaults.IconSize.roundToPx()
    }
    return textWidthPx + chromeWidthPx
}

private fun partitionTagsIntoRows(
    tags: List<NoteTag>,
    maxRowWidth: Int,
    selectedChipWidths: Map<String, Int>,
    spacing: Int,
): List<List<NoteTag>> {
    if (tags.isEmpty()) return emptyList()

    val availableWidth = maxRowWidth.coerceAtLeast(1)
    val rows = mutableListOf<List<NoteTag>>()
    var currentRow = mutableListOf<NoteTag>()
    var currentWidth = 0

    tags.forEach { tag ->
        val chipWidth = (selectedChipWidths[tag.id] ?: availableWidth).coerceAtMost(availableWidth)
        val requiredWidth = chipWidth + if (currentRow.isEmpty()) 0 else spacing
        if (currentRow.isNotEmpty() && currentWidth + requiredWidth > availableWidth) {
            rows += currentRow
            currentRow = mutableListOf()
            currentWidth = 0
        }
        currentWidth += chipWidth + if (currentRow.isEmpty()) 0 else spacing
        currentRow += tag
    }
    if (currentRow.isNotEmpty()) rows += currentRow
    return rows
}

private fun Modifier.dashedBorder(
    color: Color,
    strokeWidth: Dp,
    cornerRadius: Dp,
): Modifier = drawWithCache {
    val strokeWidthPx = strokeWidth.toPx()
    val inset = strokeWidthPx / 2f
    val pathEffect = PathEffect.dashPathEffect(
        intervals = floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
        phase = 0f,
    )
    val stroke = Stroke(width = strokeWidthPx, pathEffect = pathEffect)
    val radius = cornerRadius.toPx()
    onDrawWithContent {
        drawContent()
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(
                width = (size.width - strokeWidthPx).coerceAtLeast(0f),
                height = (size.height - strokeWidthPx).coerceAtLeast(0f),
            ),
            cornerRadius = CornerRadius(radius, radius),
            style = stroke,
        )
    }
}
