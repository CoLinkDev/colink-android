package com.colink.android.ui.notes.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.colink.android.R

@Composable
fun NoteEditorAccessoryBar(
    enabled: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onHeading: () -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onStrike: () -> Unit,
    onInlineCode: () -> Unit,
    onBullet: () -> Unit,
    onOrdered: () -> Unit,
    onTodo: () -> Unit,
    onQuote: () -> Unit,
    onCodeBlock: () -> Unit,
    onImage: () -> Unit,
    onHideKeyboard: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccessoryButton(R.string.notes_undo, Icons.AutoMirrored.Filled.Undo, enabled, onUndo, Modifier.weight(1f))
                AccessoryButton(R.string.notes_redo, Icons.AutoMirrored.Filled.Redo, enabled, onRedo, Modifier.weight(1f))
                AccessoryButton(R.string.notes_format_heading_1, Icons.Default.Title, enabled, onHeading, Modifier.weight(1f))
                AccessoryButton(R.string.notes_format_bold, Icons.Default.FormatBold, enabled, onBold, Modifier.weight(1f))
                AccessoryButton(R.string.notes_format_italic, Icons.Default.FormatItalic, enabled, onItalic, Modifier.weight(1f))
                AccessoryButton(R.string.notes_format_strike, Icons.Default.StrikethroughS, enabled, onStrike, Modifier.weight(1f))
                AccessoryButton(R.string.notes_format_inline_code, Icons.Default.Code, enabled, onInlineCode, Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccessoryButton(R.string.notes_format_bullet_list, Icons.AutoMirrored.Filled.FormatListBulleted, enabled, onBullet, Modifier.weight(1f))
                AccessoryButton(R.string.notes_format_ordered_list, Icons.Default.FormatListNumbered, enabled, onOrdered, Modifier.weight(1f))
                AccessoryButton(R.string.notes_format_todo, Icons.Default.CheckBox, enabled, onTodo, Modifier.weight(1f))
                AccessoryButton(R.string.notes_format_quote, Icons.Default.FormatQuote, enabled, onQuote, Modifier.weight(1f))
                AccessoryButton(R.string.notes_format_code_block, Icons.Default.Terminal, enabled, onCodeBlock, Modifier.weight(1f))
                AccessoryButton(R.string.notes_add_image, Icons.Default.Image, enabled, onImage, Modifier.weight(1f))
                AccessoryButton(R.string.notes_hide_keyboard, Icons.Default.KeyboardArrowDown, enabled, onHideKeyboard, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AccessoryButton(
    description: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription = stringResource(description), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
