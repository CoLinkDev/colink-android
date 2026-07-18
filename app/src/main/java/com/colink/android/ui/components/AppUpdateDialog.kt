package com.colink.android.ui.components

import android.content.Intent
import android.net.Uri
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
import com.colink.android.util.CoLinkLog
import com.colink.android.util.isBreakingVersionUpdate
import io.noties.markwon.Markwon

@Composable
fun AppUpdateDialog(
    update: AppUpdate?,
    onDismiss: () -> Unit,
) {
    val current = update ?: return
    val context = LocalContext.current
    val asset = current.assets.firstOrNull()
    val required = asset != null && !BuildConfig.DEBUG && isBreakingVersionUpdate(BuildConfig.VERSION_NAME, current.version)
    val releaseNotes = current.releaseNotes.trim().ifBlank {
        stringResource(R.string.update_available_body, current.version)
    }

    Dialog(
        onDismissRequest = {
            if (!required) {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!required) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_later_btn))
                        }
                    }
                    if (asset != null) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(asset.downloadUrl)))
                                }.onFailure { error ->
                                    CoLinkLog.w("Update", "open update download failed", error)
                                }
                                if (!required) {
                                    onDismiss()
                                }
                            },
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
