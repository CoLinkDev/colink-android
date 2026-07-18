package com.colink.android.ui.filesystem

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.domain.repository.FileTransferRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.network.RemoteFilesystemDownload
import com.colink.android.network.RemoteFilesystemSupport
import com.colink.android.network.RemoteFilesystemUnsupportedException
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
    private val _uiState = MutableStateFlow(RemoteFilesystemUiState())
    val uiState: StateFlow<RemoteFilesystemUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                connectionManager.remoteFilesystemDownloads,
                fileTransferRepository.transfers,
            ) { downloads, transfers ->
                downloadsForDevice(downloads.values, transfers)
            }.collect { downloads ->
                _uiState.update { it.copy(downloads = downloads) }
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

    fun navigateUp() {
        val parent = _uiState.value.currentPath?.let(::remoteParent)
        if (parent == null) {
            viewModelScope.launch(Dispatchers.IO) { loadRoots() }
        } else {
            viewModelScope.launch(Dispatchers.IO) { loadDirectory(parent) }
        }
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

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() }
            ?: localizedContext().getString(R.string.remote_files_request_failed)

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
}

fun remoteChild(parent: String, name: String): String {
    val separator = if (parent.contains('\\') && !parent.contains('/')) "\\" else "/"
    return parent.trimEnd('/', '\\') + separator + name
}

private fun remoteParent(path: String): String? {
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
