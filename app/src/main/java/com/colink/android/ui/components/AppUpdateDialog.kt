package com.colink.android.ui.components

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.colink.android.BuildConfig
import com.colink.android.R
import com.colink.android.domain.model.AppUpdate
import com.colink.android.domain.model.UpdateDownloadState
import com.colink.android.util.isBreakingVersionUpdate
import io.noties.markwon.Markwon

@Composable
fun AppUpdateDialog(
    update: AppUpdate?,
    downloadState: UpdateDownloadState,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    val current = update ?: return
    val asset = current.assets.singleOrNull()
    val required = asset != null && !BuildConfig.DEBUG && isBreakingVersionUpdate(BuildConfig.VERSION_NAME, current.version)
    val downloading = downloadState as? UpdateDownloadState.Downloading
    val downloadingInProgress = downloading != null
    val installing = downloadState is UpdateDownloadState.Installing
    val releaseNotes = current.releaseNotes.trim().ifBlank {
        stringResource(R.string.update_available_body, current.version)
    }

    Dialog(
        onDismissRequest = {
            if (!required && !downloadingInProgress) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !downloadingInProgress,
            dismissOnClickOutside = !downloadingInProgress,
        ),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .height(560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.update_available_body, current.version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownReleaseNotes(releaseNotes)
                }
                when (downloadState) {
                    is UpdateDownloadState.Downloading -> {
                        val progress = downloadState.totalBytes
                            ?.takeIf { it > 0 }
                            ?.let { total -> (downloadState.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) }
                        Text(
                            text = progress?.let {
                                stringResource(R.string.update_downloading_progress, (it * 100).toInt())
                            } ?: stringResource(R.string.update_downloading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (progress == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    UpdateDownloadState.Installing -> Text(
                        text = stringResource(R.string.update_installing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    UpdateDownloadState.Failed -> Text(
                        text = stringResource(R.string.update_download_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    UpdateDownloadState.Idle -> Unit
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!required && !downloadingInProgress) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_later_btn))
                        }
                    }
                    if (asset != null && !downloadingInProgress && !installing) {
                        TextButton(
                            onClick = onUpdate,
                        ) {
                            Text(stringResource(R.string.update_download_btn))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownReleaseNotes(markdown: String) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val markwon = remember(context) { Markwon.create(context) }

    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = {
            TextView(it).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(0f, 1.2f)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            markwon.setMarkdown(textView, markdown)
        },
    )
}
