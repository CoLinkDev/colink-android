package com.colink.android.ui.filesystem

import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.network.message.FsEntry
import com.colink.android.network.message.FsRootEntry
import com.colink.android.ui.transfers.openTransferFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteFilesystemScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RemoteFilesystemViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.remote_files_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = state.deviceName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_desc),
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = !state.loading && !state.loadingMore && !state.unsupported,
                        onClick = viewModel::refresh,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.remote_files_refresh_desc),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val currentPath = state.currentPath
            if (currentPath == null) {
                item {
                    FilesIntro(
                        title = stringResource(R.string.remote_files_locations_title),
                        body = stringResource(R.string.remote_files_locations_body),
                    )
                }
            } else {
                item {
                    CurrentFolderHeader(
                        path = currentPath,
                        total = state.total,
                        onNavigateUp = viewModel::navigateUp,
                    )
                }
            }

            state.error?.let { error ->
                item {
                    FilesError(
                        message = error,
                        onRetry = viewModel::refresh,
                        onDismiss = viewModel::clearError,
                    )
                }
            }

            if (state.loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.unsupported) {
                item { FilesUnsupported() }
            } else if (currentPath == null) {
                if (state.roots.isEmpty() && state.error == null) {
                    item { FilesEmpty(stringResource(R.string.remote_files_locations_empty)) }
                } else {
                    items(state.roots, key = { it.path }) { root ->
                        RootCard(root = root, onClick = { viewModel.openRoot(root.path) })
                    }
                }
            } else {
                if (state.entries.isEmpty() && state.error == null) {
                    item { FilesEmpty(stringResource(R.string.remote_files_directory_empty)) }
                } else {
                    items(state.entries, key = { entry -> "${entry.kind}:${entry.name}" }) { entry ->
                        val download = currentPath?.let { path ->
                            state.downloads[remoteChild(path, entry.name)]
                        }
                        FileEntryRow(
                            entry = entry,
                            download = download,
                            onOpenDirectory = { viewModel.openDirectory(entry) },
                            onDownload = { viewModel.download(entry) },
                            onOpenDownload = {
                                download?.transfer?.let { transfer -> openTransferFile(context, transfer) }
                            },
                        )
                    }
                }
                if (state.hasMore) {
                    item {
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            onClick = viewModel::loadMore,
                            enabled = !state.loadingMore,
                        ) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(stringResource(R.string.remote_files_load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesIntro(title: String, body: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CurrentFolderHeader(
    path: String,
    total: Long,
    onNavigateUp: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onNavigateUp,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.remote_files_up_desc),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.remote_files_item_count, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RootCard(root: FsRootEntry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = root.label?.ifBlank { null } ?: root.path,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = root.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (root.totalBytes != null && root.freeBytes != null) {
                    Text(
                        text = stringResource(
                            R.string.remote_files_storage_available,
                            formatBytes(root.freeBytes),
                            formatBytes(root.totalBytes),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    entry: FsEntry,
    download: RemoteFilesystemDownloadUi?,
    onOpenDirectory: () -> Unit,
    onDownload: () -> Unit,
    onOpenDownload: () -> Unit,
) {
    val isDirectory = entry.kind == "directory"
    val isFile = entry.kind == "file"
    val transfer = download?.transfer
    val completed = transfer?.status == "completed" && !transfer.localUri.isNullOrBlank()
    val downloading = download != null && download.error == null && (
        transfer == null || transfer.status in setOf("offered", "receiving", "verifying")
    )
    val failed = download?.error != null || transfer?.status in setOf("failed", "rejected", "cancelled")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (isDirectory) {
                    Modifier.clickable(onClick = onOpenDirectory)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    if (isDirectory) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (isDirectory) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val metadata = entryMetadata(entry)
            if (metadata != null) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            remoteDownloadStatus(download)?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isFile) {
            when {
                completed -> IconButton(onClick = onOpenDownload) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.remote_files_open_download),
                    )
                }
                downloading -> Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                else -> IconButton(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource(R.string.remote_files_download_desc),
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}

@Composable
private fun remoteDownloadStatus(download: RemoteFilesystemDownloadUi?): String? {
    if (download == null) {
        return null
    }
    if (download.error != null) {
        return stringResource(R.string.status_failed)
    }
    return when (download.transfer?.status) {
        null -> stringResource(R.string.remote_files_download_waiting)
        "completed" -> stringResource(R.string.status_completed)
        "receiving" -> stringResource(R.string.status_receiving)
        "verifying" -> stringResource(R.string.status_verifying)
        "offered" -> stringResource(R.string.status_offered)
        "failed" -> stringResource(R.string.status_failed)
        "rejected" -> stringResource(R.string.status_rejected)
        "cancelled" -> stringResource(R.string.status_cancelled)
        else -> null
    }
}

@Composable
private fun FilesEmpty(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilesError(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.remote_files_error_title), fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.remote_files_retry)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.lan_pairing_close)) }
                }
            }
        }
    }
}

@Composable
private fun FilesUnsupported() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.remote_files_unsupported_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.remote_files_unsupported_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun entryMetadata(entry: FsEntry): String? {
    val parts = buildList {
        entry.size?.let { add(formatBytes(it)) }
        (entry.modified ?: entry.created)
            ?.takeIf { it > 0L }
            ?.let { add(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) }
        if (entry.readonly) {
            add(stringResource(R.string.remote_files_read_only))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

internal fun formatBytes(value: Long): String {
    if (value < 1024L) {
        return "$value B"
    }
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var unit = 0
    while (amount >= 1024.0 && unit < units.lastIndex) {
        amount /= 1024.0
        unit += 1
    }
    return if (amount >= 100.0) "%.0f %s".format(amount, units[unit]) else "%.1f %s".format(amount, units[unit])
}
