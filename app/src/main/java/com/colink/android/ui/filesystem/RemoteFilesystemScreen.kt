package com.colink.android.ui.filesystem

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.text.DateFormat
import java.util.Date
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.network.message.FsEntry
import com.colink.android.network.message.FsRootEntry
import com.colink.android.ui.components.CoLinkTextField
import com.colink.android.ui.components.WarningCard
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
    val currentPath = state.currentPath
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.upload(context.contentResolver, uris)
    }

    var showPathActionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.toastMessage) {
        val msg = state.toastMessage
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToastMessage()
        }
    }

    BackHandler(enabled = true) {
        if (currentPath != null) {
            viewModel.navigateUp()
        } else {
            onBack()
        }
    }

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
                        enabled = currentPath != null && !state.loading && !state.loadingMore && !state.unsupported,
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = stringResource(R.string.send_file_desc),
                        )
                    }
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
            if (currentPath != null) {
                item {
                    CurrentFolderHeader(
                        path = currentPath,
                        total = state.total,
                        onNavigateUp = viewModel::navigateUp,
                        onOpenPathActionDialog = { showPathActionDialog = true },
                        onSegmentClick = { path -> viewModel.jumpToPath(path) },
                    )
                }
            }

            state.error?.let { error ->
                item {
                    WarningCard(
                        title = stringResource(R.string.remote_files_error_title),
                        body = error,
                        icon = Icons.Default.ErrorOutline,
                        actionLabel = stringResource(R.string.remote_files_retry),
                        onAction = viewModel::refresh,
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
                item {
                    WarningCard(
                        title = stringResource(R.string.remote_files_unsupported_title),
                        body = stringResource(R.string.remote_files_unsupported_body),
                        icon = Icons.Default.Info,
                    )
                }
            } else if (currentPath == null) {
                if (state.roots.isEmpty() && state.error == null) {
                    item { FilesEmpty(stringResource(R.string.remote_files_locations_empty)) }
                } else {
                    items(state.roots, key = { it.path }) { root ->
                        RootRow(
                            root = root,
                            onClick = { viewModel.openRoot(root.path) }
                        )
                    }
                }
            } else {
                val uploads = state.uploads.filterKeys { remoteParent(it) == currentPath }
                val pendingUploads = uploads.filterValues { it.transfer?.status != "completed" }
                if (state.entries.isEmpty() && pendingUploads.isEmpty() && state.error == null) {
                    item { FilesEmpty(stringResource(R.string.remote_files_directory_empty)) }
                } else {
                    items(state.entries, key = { entry -> "${entry.kind}:${entry.name}" }) { entry ->
                        val download = state.downloads[remoteChild(currentPath, entry.name)]
                        val upload = uploads[remoteChild(currentPath, entry.name)]
                        FileEntryRow(
                            entry = entry,
                            download = download,
                            upload = upload,
                            onOpenDirectory = { viewModel.openDirectory(entry) },
                            onDownload = { viewModel.download(entry) },
                            onCancelUpload = viewModel::cancelUpload,
                            onOpenDownload = {
                                download?.transfer?.let { transfer -> openTransferFile(context, transfer) }
                            },
                        )
                    }
                    items(
                        pendingUploads.filterKeys { path ->
                            state.entries.none { entry -> remoteChild(currentPath, entry.name) == path }
                        }.toList(),
                        key = { (path, _) -> "upload:$path" },
                    ) { (path, upload) ->
                        FileEntryRow(
                            entry = FsEntry(
                                name = path.substringAfterLast('/', path.substringAfterLast('\\')),
                                kind = "file",
                                readonly = false,
                                hidden = false,
                            ),
                            download = null,
                            upload = upload,
                            onOpenDirectory = {},
                            onDownload = {},
                            onCancelUpload = viewModel::cancelUpload,
                            onOpenDownload = {},
                        )
                    }
                }
                if (state.hasMore) {
                    item {
                        LaunchedEffect(Unit) {
                            if (!state.loadingMore) {
                                viewModel.loadMore()
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPathActionDialog) {
        PathActionDialog(
            currentPath = currentPath ?: "",
            onDismiss = { showPathActionDialog = false },
            onCopy = { path -> viewModel.copyPathToClipboard(path) },
            onJump = { path ->
                showPathActionDialog = false
                viewModel.jumpToPath(path)
            }
        )
    }
}

@Composable
private fun PathActionDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onJump: (String) -> Unit,
) {
    var pathInput by remember { mutableStateOf(currentPath) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_files_path)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CoLinkTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text(stringResource(R.string.remote_files_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 复制与粘贴按钮聚合在左侧
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { onCopy(pathInput) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.remote_files_copy_path))
                        }
                        TextButton(
                            onClick = {
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val item = clipboard?.primaryClip?.getItemAt(0)
                                    val text = item?.text?.toString()
                                    if (!text.isNullOrBlank()) {
                                        pathInput = text.trim()
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.remote_files_paste))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onJump(pathInput) }) {
                Text(stringResource(R.string.remote_files_jump))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_btn))
            }
        }
    )
}

@Composable
private fun CurrentFolderHeader(
    path: String,
    total: Long,
    onNavigateUp: () -> Unit,
    onOpenPathActionDialog: () -> Unit,
    onSegmentClick: (String) -> Unit,
) {
    val segments = remember(path) { parsePathSegments(path) }
    val lazyListState = rememberLazyListState()

    LaunchedEffect(path) {
        if (segments.isNotEmpty()) {
            lazyListState.scrollToItem(segments.lastIndex)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 最上方放大显示且带动态渐隐特效的面包屑Segment链
            LazyRow(
                state = lazyListState,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalFadingEdge(lazyListState, length = 24.dp)
            ) {
                items(segments) { (name, fullPath) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSegmentClick(fullPath) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // “共X项”与首个面包屑文字左对齐
            Text(
                text = stringResource(R.string.remote_files_item_count, total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )

            // 下方药丸形按钮组：向上 & 地址
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalButton(
                    onClick = onNavigateUp,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.remote_files_up_desc))
                }

                FilledTonalButton(
                    onClick = onOpenPathActionDialog,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.AltRoute,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.remote_files_path))
                }
            }
        }
    }
}

private fun Modifier.horizontalFadingEdge(
    state: LazyListState,
    length: Dp = 24.dp,
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()

        val canScrollLeft = state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0
        val canScrollRight = state.canScrollForward

        if (canScrollLeft) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = length.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }

        if (canScrollRight) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = size.width - length.toPx(),
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

private data class PathSegment(val name: String, val fullPath: String)

private fun parsePathSegments(path: String): List<PathSegment> {
    val isWin = path.contains('\\') && !path.contains('/')
    val sep = if (isWin) "\\" else "/"
    val parts = path.split('/', '\\').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return emptyList()

    val result = mutableListOf<PathSegment>()
    var current = ""
    for (i in parts.indices) {
        val part = parts[i]
        current = if (i == 0 && isWin && part.endsWith(":")) {
            "$part\\"
        } else if (i == 0 && !isWin) {
            "/$part"
        } else {
            if (current.endsWith(sep) || current.endsWith("/")) "$current$part" else "$current$sep$part"
        }
        result.add(PathSegment(name = part, fullPath = current))
    }
    return result
}

private fun getFileIcon(fileName: String): ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg" -> Icons.Default.Image
        "mp4", "mkv", "avi", "mov", "webm", "flv", "3gp" -> Icons.Default.VideoFile
        "mp3", "wav", "ogg", "flac", "m4a", "aac", "wma" -> Icons.Default.MusicNote
        "pdf", "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "csv", "ods", "ppt", "pptx", "odp" -> Icons.Default.Description
        "zip", "rar", "7z", "tar", "gz", "bz2" -> Icons.Default.FolderZip
        "kt", "java", "py", "js", "ts", "html", "css", "json", "xml", "cpp", "c", "sh", "bat" -> Icons.Default.Code
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

@Composable
private fun RootRow(
    root: FsRootEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = root.label?.ifBlank { null } ?: root.path,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    entry: FsEntry,
    download: RemoteFilesystemDownloadUi?,
    upload: RemoteFilesystemUploadUi?,
    onOpenDirectory: () -> Unit,
    onDownload: () -> Unit,
    onCancelUpload: (String) -> Unit,
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
    val uploadTransfer = upload?.transfer
    val uploading = upload != null && upload.error == null &&
        uploadTransfer?.status !in setOf("completed", "failed", "rejected", "cancelled")
    val uploadFailed = upload?.error != null || uploadTransfer?.status in setOf("failed", "rejected", "cancelled")
    val uploadCancellable = uploadTransfer?.status in setOf("offered", "accepted", "sending")
    val uploadProgress = uploadTransfer
        ?.takeIf { it.fileSize > 0L }
        ?.let { transfer ->
            (transfer.transferredBytes.toFloat() / transfer.fileSize.toFloat()).coerceIn(0f, 1f)
        }
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
                imageVector = if (isDirectory) Icons.Default.Folder else getFileIcon(entry.name),
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
            remoteUploadStatus(upload)?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uploadFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isFile) {
            when {
                upload != null && uploading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (uploadProgress == null) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            CircularProgressIndicator(
                                progress = { uploadProgress },
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    if (uploadCancellable) {
                        IconButton(onClick = { onCancelUpload(checkNotNull(uploadTransfer).sessionId) }) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = stringResource(R.string.cancel_btn),
                            )
                        }
                    }
                }
                upload != null -> Unit
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
}

@Composable
private fun remoteUploadStatus(upload: RemoteFilesystemUploadUi?): String? {
    if (upload == null) {
        return null
    }
    if (upload.error != null) {
        return stringResource(R.string.status_failed)
    }
    val transfer = upload.transfer
    return when (transfer?.status) {
        null, "connecting" -> stringResource(R.string.status_connecting)
        "computing" -> stringResource(R.string.status_computing)
        "offered", "accepted" -> stringResource(R.string.status_offered)
        "sending" -> "${stringResource(R.string.status_sending)} ${formatBytes(transfer.transferredBytes.coerceIn(0L, transfer.fileSize))} / ${formatBytes(transfer.fileSize)}"
        "failed" -> stringResource(R.string.status_failed)
        "rejected" -> stringResource(R.string.status_rejected)
        "cancelled" -> stringResource(R.string.status_cancelled)
        else -> null
    }
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
