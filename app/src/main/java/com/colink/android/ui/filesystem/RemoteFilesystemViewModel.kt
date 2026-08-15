package com.colink.android.ui.filesystem

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.domain.repository.FileTransferRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.network.RemoteFilesystemDownload
import com.colink.android.network.RemoteFilesystemErrorException
import com.colink.android.network.RemoteFilesystemSupport
import com.colink.android.network.RemoteFilesystemUpload
import com.colink.android.network.RemoteFilesystemUnsupportedException
import com.colink.android.network.transfer.readFileMetadata
import com.colink.android.network.message.FsEntry
import com.colink.android.network.message.FsRootEntry
import com.colink.android.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteFilesystemDownloadUi(
    val transfer: FileTransfer? = null,
    val error: String? = null,
)

data class RemoteFilesystemUploadUi(
    val transfer: FileTransfer? = null,
    val error: String? = null,
)

data class RemoteFilesystemUiState(
    val deviceName: String = "",
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val roots: List<FsRootEntry> = emptyList(),
    val currentPath: String? = null,
    val entries: List<FsEntry> = emptyList(),
    val total: Long = 0L,
    val hasMore: Boolean = false,
    val unsupported: Boolean = false,
    val error: String? = null,
    val downloads: Map<String, RemoteFilesystemDownloadUi> = emptyMap(),
    val uploads: Map<String, RemoteFilesystemUploadUi> = emptyMap(),
    val toastMessage: String? = null,
)

@HiltViewModel
class RemoteFilesystemViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val fileTransferRepository: FileTransferRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    private val deviceId = checkNotNull(savedStateHandle.get<String>("deviceId"))
    private val contentGeneration = AtomicLong(0L)
    private val refreshedUploadSessionIds = mutableSetOf<String>()
    private val _uiState = MutableStateFlow(RemoteFilesystemUiState())
    val uiState: StateFlow<RemoteFilesystemUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                connectionManager.remoteFilesystemDownloads,
                connectionManager.remoteFilesystemUploads,
                fileTransferRepository.transfers,
            ) { downloads, uploads, transfers ->
                FilesystemTransfersUi(
                    downloads = downloadsForDevice(downloads.values, transfers),
                    uploads = uploadsForDevice(uploads.values, transfers),
                    completedUploadPaths = uploads.values
                        .filter { it.deviceId == deviceId }
                        .mapNotNull { upload ->
                            upload.sessionId
                                ?.let { sessionId -> transfers.firstOrNull { it.sessionId == sessionId } }
                                ?.takeIf { it.status == "completed" }
                                ?.let { upload.remotePath to it.sessionId }
                        },
                )
            }.collect { transfers ->
                _uiState.update { it.copy(downloads = transfers.downloads, uploads = transfers.uploads) }
                refreshCompletedUploads(transfers.completedUploadPaths)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val deviceName = deviceRepository.getDevice(deviceId)?.name?.ifBlank { deviceId } ?: deviceId
            _uiState.update { it.copy(deviceName = deviceName) }
            if (connectionManager.remoteFilesystemSupport(deviceId) == RemoteFilesystemSupport.TOO_OLD) {
                _uiState.update { it.copy(loading = false, unsupported = true) }
            } else {
                loadRoots()
            }
        }
    }

    fun refresh() {
        if (_uiState.value.unsupported) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val path = _uiState.value.currentPath
            if (path == null) {
                loadRoots()
            } else {
                loadDirectory(path)
            }
        }
    }

    fun openRoot(path: String) {
        viewModelScope.launch(Dispatchers.IO) { loadDirectory(path) }
    }

    fun openDirectory(entry: FsEntry) {
        if (entry.kind != "directory") {
            return
        }
        val path = _uiState.value.currentPath ?: return
        viewModelScope.launch(Dispatchers.IO) { loadDirectory(remoteChild(path, entry.name)) }
    }

    fun jumpToPath(rawPath: String) {
        val trimmed = rawPath.trim()
        viewModelScope.launch(Dispatchers.IO) {
            if (trimmed.isBlank() || trimmed == "/" || trimmed == "\\") {
                loadRoots()
            } else {
                loadDirectory(trimmed)
            }
        }
    }

    fun navigateUp() {
        val parent = _uiState.value.currentPath?.let(::remoteParent)
        viewModelScope.launch(Dispatchers.IO) {
            if (parent == null) {
                loadRoots()
            } else {
                loadDirectory(parent)
            }
        }
    }

    fun copyPathToClipboard(path: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboard != null) {
                val clip = android.content.ClipData.newPlainText("path", path)
                clipboard.setPrimaryClip(clip)
                _uiState.update { it.copy(toastMessage = localizedContext().getString(R.string.remote_files_path_copied)) }
            }
        } catch (_: Exception) {
        }
    }

    fun clearToastMessage() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun loadMore() {
        val state = _uiState.value
        val path = state.currentPath ?: return
        if (!state.hasMore || state.loading || state.loadingMore) {
            return
        }
        val generation = contentGeneration.get()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(loadingMore = true, error = null) }
            connectionManager.listRemoteFilesystem(deviceId, path, state.entries.size.toLong())
                .onSuccess { result ->
                    _uiState.update {
                        if (contentGeneration.get() != generation || it.currentPath != path) it
                        else {
                            it.copy(
                                loadingMore = false,
                                entries = it.entries + result.entries,
                                total = result.total,
                                hasMore = result.hasMore,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        if (contentGeneration.get() != generation || it.currentPath != path) {
                            it
                        } else if (error is RemoteFilesystemUnsupportedException) {
                            it.copy(loadingMore = false, unsupported = true, error = null)
                        } else {
                            it.copy(loadingMore = false, error = error.userMessage())
                        }
                    }
                }
        }
    }

    fun download(entry: FsEntry) {
        if (entry.kind != "file") {
            return
        }
        val path = _uiState.value.currentPath ?: return
        val targetPath = remoteChild(path, entry.name)
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(error = null) }
            connectionManager.downloadRemoteFilesystemFile(deviceId, targetPath)
                .onFailure { error ->
                    _uiState.update {
                        it.copy(error = error.userMessage())
                    }
                }
        }
    }

    fun upload(contentResolver: ContentResolver, uris: List<Uri>) {
        val directory = _uiState.value.currentPath ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(error = null) }
            for (uri in uris) {
                val name = runCatching { contentResolver.readFileMetadata(uri).name }
                    .getOrElse { error ->
                        _uiState.update { it.copy(error = error.userMessage()) }
                        return@launch
                    }
                connectionManager.uploadRemoteFilesystemFile(
                    contentResolver,
                    deviceId,
                    remoteChild(directory, name),
                    uri,
                ).onFailure { error ->
                    _uiState.update { it.copy(error = error.userMessage()) }
                    return@launch
                }
            }
        }
    }

    fun cancelUpload(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.cancelTransfer(sessionId)
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.userMessage()) }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun loadRoots() {
        val generation = contentGeneration.incrementAndGet()
        _uiState.update {
            it.copy(
                loading = true,
                loadingMore = false,
                currentPath = null,
                roots = emptyList(),
                entries = emptyList(),
                total = 0L,
                hasMore = false,
                unsupported = false,
                error = null,
            )
        }
        connectionManager.listRemoteFilesystemRoots(deviceId)
            .onSuccess { result ->
                _uiState.update {
                    if (contentGeneration.get() != generation) it
                    else it.copy(loading = false, roots = result.roots)
                }
            }
            .onFailure { error ->
                _uiState.update {
                    if (contentGeneration.get() != generation) it
                    else if (error is RemoteFilesystemUnsupportedException) {
                        it.copy(loading = false, unsupported = true, error = null)
                    }
                    else it.copy(loading = false, error = error.userMessage())
                }
            }
    }

    private suspend fun loadDirectory(path: String) {
        val generation = contentGeneration.incrementAndGet()
        _uiState.update {
            it.copy(
                loading = true,
                loadingMore = false,
                currentPath = path,
                entries = emptyList(),
                total = 0L,
                hasMore = false,
                unsupported = false,
                error = null,
            )
        }
        connectionManager.listRemoteFilesystem(deviceId, path)
            .onSuccess { result ->
                _uiState.update {
                    if (contentGeneration.get() != generation) it
                    else {
                        it.copy(
                            loading = false,
                            currentPath = result.path,
                            entries = result.entries,
                            total = result.total,
                            hasMore = result.hasMore,
                        )
                    }
                }
            }
            .onFailure { error ->
                _uiState.update {
                    if (contentGeneration.get() != generation) it
                    else if (error is RemoteFilesystemUnsupportedException) {
                        it.copy(loading = false, unsupported = true, error = null)
                    }
                    else it.copy(loading = false, error = error.userMessage())
                }
            }
    }

    private fun localizedContext(): Context = LocaleHelper.localized(context)

    private fun refreshCompletedUploads(completedUploads: List<Pair<String, String>>) {
        val currentPath = _uiState.value.currentPath ?: return
        if (completedUploads.any { (path, sessionId) ->
                remoteParent(path) == currentPath && refreshedUploadSessionIds.add(sessionId)
            }
        ) {
            refresh()
        }
    }

    private fun Throwable.userMessage(): String {
        val resources = localizedContext()
        return when (this) {
            is RemoteFilesystemErrorException -> when (reason) {
                "already_exists" -> resources.getString(R.string.remote_files_error_already_exists)
                "invalid_path" -> resources.getString(R.string.remote_files_error_invalid_path)
                "io_error" -> resources.getString(R.string.remote_files_error_io)
                "not_directory" -> resources.getString(R.string.remote_files_error_not_directory)
                "not_file" -> resources.getString(R.string.remote_files_error_not_file)
                "not_found" -> resources.getString(R.string.remote_files_error_not_found)
                "permission_denied" -> resources.getString(R.string.remote_files_error_permission_denied)
                else -> resources.getString(R.string.remote_files_request_failed)
            }
            else -> message?.takeIf { it.isNotBlank() }
                ?: resources.getString(R.string.remote_files_request_failed)
        }
    }

    private fun downloadsForDevice(
        downloads: Collection<RemoteFilesystemDownload>,
        transfers: List<FileTransfer>,
    ): Map<String, RemoteFilesystemDownloadUi> =
        downloads.asSequence()
            .filter { it.deviceId == deviceId }
            .groupBy { it.remotePath }
            .mapValues { (_, attempts) ->
                val attempt = attempts.maxBy { it.requestedAt }
                RemoteFilesystemDownloadUi(
                    transfer = attempt.sessionId?.let { sessionId ->
                        transfers.firstOrNull { it.sessionId == sessionId }
                    },
                    error = attempt.error,
                )
            }

    private fun uploadsForDevice(
        uploads: Collection<RemoteFilesystemUpload>,
        transfers: List<FileTransfer>,
    ): Map<String, RemoteFilesystemUploadUi> =
        uploads.asSequence()
            .filter { it.deviceId == deviceId }
            .groupBy { it.remotePath }
            .mapValues { (_, attempts) ->
                val attempt = attempts.maxBy { it.requestedAt }
                RemoteFilesystemUploadUi(
                    transfer = attempt.sessionId?.let { sessionId ->
                        transfers.firstOrNull { it.sessionId == sessionId }
                    },
                    error = attempt.error,
                )
            }
}

private data class FilesystemTransfersUi(
    val downloads: Map<String, RemoteFilesystemDownloadUi>,
    val uploads: Map<String, RemoteFilesystemUploadUi>,
    val completedUploadPaths: List<Pair<String, String>>,
)

fun remoteChild(parent: String, name: String): String {
    val separator = if (parent.contains('\\') && !parent.contains('/')) "\\" else "/"
    return parent.trimEnd('/', '\\') + separator + name
}

internal fun remoteParent(path: String): String? {
    val trimmed = path.trimEnd('/', '\\')
    if (trimmed.isEmpty()) {
        return null
    }
    val index = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    if (index <= 0) {
        return null
    }
    if (index == 2 && trimmed.length >= 2 && trimmed[1] == ':') {
        return "${trimmed.substring(0, 2)}\\"
    }
    return trimmed.substring(0, index)
}
