package com.colink.android.ui.notes.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InsertPhoto
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.colink.android.R
import com.colink.android.ui.components.ContentGroupItem
import com.colink.android.util.formatFileSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteAttachmentsBottomSheet(
    attachmentIds: List<String>,
    names: Map<String, String>,
    mediaTypes: Map<String, String>,
    sizes: Map<String, Long>,
    paths: Map<String, String>,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onInsert: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    onLoadImage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.notes_attachments), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(
                        R.string.notes_attachments_summary,
                        attachmentIds.size,
                        formatFileSize(attachmentIds.sumOf { sizes[it] ?: 0L }),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (attachmentIds.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                ) {
                    Text(stringResource(R.string.notes_no_attachments), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                ) {
                    itemsIndexed(attachmentIds, key = { _, id -> id }) { index, id ->
                        val isImage = mediaTypes[id].orEmpty().startsWith("image/")
                        val path = paths[id]
                        LaunchedEffect(id, isImage, path) {
                            if (isImage && path == null) onLoadImage(id)
                        }
                        val thumbnail = remember(path) { path?.let(BitmapFactory::decodeFile)?.asImageBitmap() }
                        ContentGroupItem(
                            isFirst = index == 0,
                            isLast = index == attachmentIds.lastIndex,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        enabled = enabled,
                                        onClickLabel = stringResource(R.string.notes_open_attachment),
                                        onClick = { onOpen(id) },
                                    )
                                    .padding(10.dp),
                            ) {
                                if (thumbnail != null) {
                                    Image(
                                        bitmap = thumbnail,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                    )
                                } else {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                                        Icon(
                                            if (isImage) Icons.Default.InsertPhoto else Icons.Default.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(
                                        names[id] ?: id.take(8),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    sizes[id]?.let {
                                        Text(
                                            formatFileSize(it),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(enabled = enabled, onClick = { onInsert(id) }) {
                                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.notes_insert_into_note))
                                }
                                IconButton(enabled = enabled, onClick = { onRemove(id) }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.notes_remove_attachment),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Button(
                enabled = enabled,
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Text(stringResource(R.string.notes_add_attachment), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
