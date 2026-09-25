package com.colink.android.ui.notes.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.colink.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolutionBottomSheet(
    conflictKind: String?,
    localTitle: String,
    localMarkdown: String,
    cloudTitle: String?,
    cloudMarkdown: String?,
    onResolve: (String) -> Unit,
    onMerge: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp),
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                stringResource(
                    when (conflictKind) {
                        "cloudDeleted" -> R.string.notes_cloud_deleted_title
                        "delete" -> R.string.notes_delete_conflict_title
                        else -> R.string.notes_conflict_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            VersionPreview(stringResource(R.string.notes_local_version, localTitle), localMarkdown)
            VersionPreview(
                stringResource(R.string.notes_cloud_version, cloudTitle ?: stringResource(R.string.notes_untitled)),
                cloudMarkdown.orEmpty(),
            )
            when (conflictKind) {
                "cloudDeleted" -> {
                    Button(onClick = { onResolve("local") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.notes_save_as_new))
                    }
                    OutlinedButton(onClick = { onResolve("cloud") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.notes_discard_local))
                    }
                }
                "delete" -> {
                    Button(onClick = { onResolve("cancel_delete") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.notes_keep_cloud_version))
                    }
                    Button(
                        onClick = { onResolve("confirm_delete") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.notes_delete_anyway)) }
                }
                else -> {
                    Button(onClick = { onResolve("local") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.notes_keep_local))
                    }
                    OutlinedButton(onClick = { onResolve("cloud") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.notes_keep_cloud))
                    }
                    TextButton(onClick = onMerge, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.notes_merge_manually))
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(stringResource(R.string.cancel_btn))
            }
        }
    }
}

@Composable
private fun VersionPreview(title: String, markdown: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            markdown.take(180),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
        )
    }
}
