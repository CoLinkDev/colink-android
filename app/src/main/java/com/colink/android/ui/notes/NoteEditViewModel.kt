package com.colink.android.ui.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.domain.model.NoteSyncState
import com.colink.android.domain.model.NoteTag
import com.colink.android.domain.repository.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

@HiltViewModel
class NoteEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val notesRepository: NotesRepository,
) : ViewModel() {

    private val noteIdFromRoute: String = checkNotNull(savedStateHandle["noteId"])

    data class EditState(
        val id: String? = null,
        val title: String = "",
        val markdown: String = "",
        val tagIds: List<String> = emptyList(),
        val attachmentIds: List<String> = emptyList(),
        val syncState: NoteSyncState = NoteSyncState.Pending,
        val conflictKind: String? = null,
        val conflictTitle: String? = null,
        val conflictMarkdown: String? = null,
    )

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()

    private val _tagOptions = MutableStateFlow<List<NoteTag>>(emptyList())
    val tagOptions: StateFlow<List<NoteTag>> = _tagOptions.asStateFlow()

    private val _attachmentNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val attachmentNames: StateFlow<Map<String, String>> = _attachmentNames.asStateFlow()

    private val _attachmentMediaTypes = MutableStateFlow<Map<String, String>>(emptyMap())
    val attachmentMediaTypes: StateFlow<Map<String, String>> = _attachmentMediaTypes.asStateFlow()

    private val _attachmentSizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val attachmentSizes: StateFlow<Map<String, Long>> = _attachmentSizes.asStateFlow()

    private val _attachmentPaths = MutableStateFlow<Map<String, String>>(emptyMap())
    val attachmentPaths: StateFlow<Map<String, String>> = _attachmentPaths.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages: SharedFlow<Int> = _messages

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private var loaded = false
    private var saveJob: Job? = null
    private var selectionOffset = 0
    private val stagedAttachmentIds = linkedSetOf<String>()
    private val attachmentsBeingRemoved = mutableSetOf<String>()
    private val attachmentsBeingLoaded = mutableSetOf<String>()
    private var baseline = EditState().editableSnapshot()

    init {
        viewModelScope.launch {
            notesRepository.tags.collect { tags ->
                _tagOptions.value = tags
            }
        }
    }

    fun load(noteId: String) {
        if (loaded) {
            refreshTagOptions()
            return
        }
        loaded = true
        viewModelScope.launch {
            refreshTagOptions()
            refreshAttachmentNames()
            val existing = if (noteId == "new") null else notesRepository.getNote(noteId)
            val nextState = if (existing != null) {
                EditState(
                    id = existing.id,
                    title = existing.title,
                    markdown = existing.markdown,
                    tagIds = existing.tagIds,
                    attachmentIds = existing.attachmentIds,
                    syncState = existing.syncState,
                    conflictKind = existing.conflictKind,
                    conflictTitle = existing.conflictTitle,
                    conflictMarkdown = existing.conflictMarkdown,
                )
            } else {
                EditState()
            }
            baseline = nextState.editableSnapshot()
            _state.value = nextState
            updateDirty()
        }
    }

    private fun refreshTagOptions() {
        // The single collector started in init keeps this list current.
    }

    private suspend fun refreshAttachmentNames() {
        runCatching { notesRepository.listAttachments() }
            .onSuccess { attachments ->
                _attachmentNames.value = attachments.associate { it.id to it.fileName }
                _attachmentMediaTypes.value = attachments.associate { it.id to it.mediaType }
                _attachmentSizes.value = attachments.associate { it.id to it.size }
            }
            .onFailure { _messages.emit(R.string.status_failed) }
    }

    fun updateTitle(title: String) {
        updateState(_state.value.copy(title = title))
    }

    fun updateMarkdown(markdown: String) {
        updateState(_state.value.copy(markdown = markdown))
    }

    fun updateSelection(offset: Int) {
        selectionOffset = offset.coerceIn(0, _state.value.markdown.length)
    }

    fun addTag(name: String) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                notesRepository.createTag(name).onSuccess { tag ->
                    refreshTagOptions()
                    val current = _state.value
                    if (!current.tagIds.contains(tag.id)) {
                        updateState(current.copy(tagIds = current.tagIds + tag.id))
                    }
                }.onFailure { _messages.emit(R.string.status_failed) }
            } finally {
                _saving.value = false
            }
        }
    }

    fun toggleTag(tagId: String) {
        val current = _state.value
        updateState(current.copy(
            tagIds = if (current.tagIds.contains(tagId)) {
                current.tagIds - tagId
            } else {
                current.tagIds + tagId
            },
        ))
    }

    fun stageAttachment(uri: android.net.Uri) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val fileName = queryFileName(uri) ?: "attachment"
                val kind = if (appContext.contentResolver.getType(uri)?.startsWith("image/") == true) "image" else "file"
                notesRepository.stageAttachment(uri.toString(), kind, fileName)
                    .onSuccess { attachment ->
                        stagedAttachmentIds += attachment.id
                        val current = _state.value
                        val nextMarkdown = insertReference(
                            current.markdown,
                            attachment.id,
                            attachment.fileName,
                            kind == "image",
                            selectionOffset,
                        )
                        val insertedLength = nextMarkdown.length - current.markdown.length
                        selectionOffset = (selectionOffset + insertedLength).coerceIn(0, nextMarkdown.length)
                        updateState(current.copy(
                            attachmentIds = _state.value.attachmentIds + attachment.id,
                            markdown = nextMarkdown,
                        ))
                        _attachmentNames.value = _attachmentNames.value + (attachment.id to attachment.fileName)
                        _attachmentMediaTypes.value = _attachmentMediaTypes.value + (attachment.id to attachment.mediaType)
                        _attachmentSizes.value = _attachmentSizes.value + (attachment.id to attachment.size)
                    }
                    .onFailure { _messages.emit(R.string.status_failed) }
            } finally {
                _saving.value = false
            }
        }
    }

    fun removeAttachmentReference(attachmentId: String) {
        if (!stagedAttachmentIds.contains(attachmentId)) {
            removeAttachmentFromState(attachmentId)
            return
        }
        if (_saving.value || !attachmentsBeingRemoved.add(attachmentId)) return
        _saving.value = true
        viewModelScope.launch {
            try {
                notesRepository.removeAttachment(attachmentId)
                    .onSuccess {
                        stagedAttachmentIds.remove(attachmentId)
                        _attachmentNames.value = _attachmentNames.value - attachmentId
                        _attachmentMediaTypes.value = _attachmentMediaTypes.value - attachmentId
                        _attachmentSizes.value = _attachmentSizes.value - attachmentId
                        _attachmentPaths.value = _attachmentPaths.value - attachmentId
                        removeAttachmentFromState(attachmentId)
                    }
                    .onFailure { _messages.emit(R.string.status_failed) }
            } finally {
                attachmentsBeingRemoved.remove(attachmentId)
                _saving.value = false
            }
        }
    }

    fun openAttachment(attachmentId: String, context: android.content.Context) {
        viewModelScope.launch {
            notesRepository.ensureAttachmentCached(attachmentId)
                .onSuccess { file ->
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        val mediaType = _attachmentMediaTypes.value[attachmentId].orEmpty()
                            .ifBlank { guessMimeType(_attachmentNames.value[attachmentId].orEmpty()) }
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mediaType)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    }.onFailure { _messages.emit(R.string.toast_open_file_failed) }
                }
                .onFailure { _messages.emit(R.string.toast_open_file_failed) }
        }
    }

    fun save(onDone: () -> Unit = {}) {
        saveJob?.cancel()
        viewModelScope.launch {
            awaitIdle()
            if (!_dirty.value || saveNow()) onDone()
        }
    }

    /** Flushes pending edits before the editor leaves composition or the activity pauses. */
    fun flush(onComplete: (() -> Unit)? = null) {
        saveJob?.cancel()
        viewModelScope.launch {
            awaitIdle()
            val succeeded = !_dirty.value || saveNow()
            if (succeeded) onComplete?.invoke()
        }
    }

    fun deleteNote(onDone: () -> Unit) {
        val id = _state.value.id ?: return
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                notesRepository.deleteNote(id).onSuccess {
                    if (!removeStagedAttachments()) _messages.emit(R.string.status_failed)
                    onDone()
                }.onFailure { _messages.emit(R.string.status_failed) }
            } finally {
                _saving.value = false
            }
        }
    }

    fun discard(onDone: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                if (removeStagedAttachments()) {
                    baseline = _state.value.editableSnapshot()
                    updateDirty()
                    onDone()
                } else {
                    _messages.emit(R.string.status_failed)
                }
            } finally {
                _saving.value = false
            }
        }
    }

    fun insertAttachmentReference(
        attachmentId: String,
        at: Int = selectionOffset,
    ) {
        val current = _state.value
        if (current.markdown.contains("colink-attachment://$attachmentId")) return
        val fileName = _attachmentNames.value[attachmentId] ?: attachmentId.take(8)
        val isImage = _attachmentMediaTypes.value[attachmentId].orEmpty().startsWith("image/")
        val offset = at.coerceIn(0, current.markdown.length)
        val nextMarkdown = insertReference(current.markdown, attachmentId, fileName, isImage, offset)
        updateState(current.copy(markdown = nextMarkdown))
        selectionOffset = offset + (nextMarkdown.length - current.markdown.length)
    }

    fun loadAttachmentImage(attachmentId: String) {
        if (_attachmentPaths.value.containsKey(attachmentId) || !attachmentsBeingLoaded.add(attachmentId)) return
        viewModelScope.launch {
            try {
                notesRepository.ensureAttachmentCached(attachmentId)
                    .onSuccess { file ->
                        _attachmentPaths.value = _attachmentPaths.value + (attachmentId to file.absolutePath)
                    }
                    .onFailure { _messages.emit(R.string.notes_attachment_unavailable) }
            } finally {
                attachmentsBeingLoaded.remove(attachmentId)
            }
        }
    }

    fun resolveConflict(resolution: String, mergedTitle: String?, mergedMarkdown: String?, onDone: () -> Unit) {
        val id = _state.value.id ?: return
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                notesRepository.resolveConflict(
                    noteId = id,
                    resolution = resolution,
                    mergedTitle = mergedTitle,
                    mergedMarkdown = mergedMarkdown,
                ).onSuccess { note ->
                    val nextState = _state.value.copy(
                        id = note.id,
                        title = note.title,
                        markdown = note.markdown,
                        tagIds = note.tagIds,
                        attachmentIds = note.attachmentIds,
                        syncState = note.syncState,
                        conflictKind = note.conflictKind,
                        conflictTitle = note.conflictTitle,
                        conflictMarkdown = note.conflictMarkdown,
                    )
                    baseline = nextState.editableSnapshot()
                    _state.value = nextState
                    updateDirty()
                    if (resolution == "cloud" || resolution == "confirm_delete" || resolution == "cancel_delete") {
                        onDone()
                    }
                }.onFailure { _messages.emit(R.string.status_failed) }
            } finally {
                _saving.value = false
            }
        }
    }

    private data class EditableSnapshot(
        val title: String,
        val markdown: String,
        val tagIds: Set<String>,
        val attachmentIds: Set<String>,
    )

    private fun EditState.editableSnapshot() = EditableSnapshot(
        title = title,
        markdown = markdown,
        tagIds = tagIds.toSet(),
        attachmentIds = attachmentIds.toSet(),
    )

    private fun updateState(nextState: EditState) {
        _state.value = nextState
        updateDirty()
        scheduleAutoSave()
    }

    private fun scheduleAutoSave() {
        if (!loaded || !_dirty.value || _state.value.syncState == NoteSyncState.Conflict || _state.value.syncState == NoteSyncState.ConflictDelete) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            saveJob = null
            saveNow()
        }
    }

    private suspend fun saveNow(): Boolean {
        if (_saving.value || !_dirty.value || _state.value.syncState == NoteSyncState.Conflict || _state.value.syncState == NoteSyncState.ConflictDelete) return false
        _saving.value = true
        val current = _state.value
        return try {
            notesRepository.upsertNote(
                noteId = current.id,
                title = current.title,
                markdown = current.markdown,
                tagIds = current.tagIds,
                attachmentIds = current.attachmentIds,
            ).fold(
                onSuccess = { note ->
                    val latest = _state.value
                    val nextState = latest.copy(id = note.id, syncState = note.syncState)
                    _state.value = nextState
                    baseline = current.editableSnapshot()
                    stagedAttachmentIds.clear()
                    updateDirty()
                    true
                },
                onFailure = {
                    _messages.emit(R.string.status_failed)
                    false
                },
            )
        } finally {
            _saving.value = false
            if (_state.value.editableSnapshot() != current.editableSnapshot()) scheduleAutoSave()
        }
    }

    private suspend fun awaitIdle() {
        while (_saving.value) delay(16)
    }

    private fun insertReference(markdown: String, id: String, name: String, image: Boolean, at: Int): String {
        val uri = "colink-attachment://$id"
        val reference = if (image) "![$name]($uri)" else "[$name]($uri)"
        val offset = at.coerceIn(0, markdown.length)
        if (!image) return markdown.replaceRange(offset, offset, reference)
        val leading = if (offset > 0 && !markdown.substring(0, offset).endsWith("\n\n")) "\n\n" else ""
        val trailing = if (offset < markdown.length && !markdown.substring(offset).startsWith("\n\n")) "\n\n" else ""
        return markdown.replaceRange(offset, offset, leading + reference + trailing)
    }

    private fun updateDirty() {
        _dirty.value = _state.value.editableSnapshot() != baseline
    }

    private fun removeAttachmentFromState(attachmentId: String) {
        val current = _state.value
        val escaped = Regex.escape(attachmentId)
        val reference = Regex("!?\\[[^]]*]\\(colink-attachment://$escaped\\)")
        updateState(
            current.copy(
                attachmentIds = current.attachmentIds - attachmentId,
                markdown = current.markdown.replace(reference, "").replace(Regex("\\n{3,}"), "\n\n"),
            ),
        )
    }

    private suspend fun removeStagedAttachments(): Boolean {
        var succeeded = true
        stagedAttachmentIds.toList().forEach { attachmentId ->
            notesRepository.removeAttachment(attachmentId)
                .onSuccess {
                    stagedAttachmentIds.remove(attachmentId)
                    _attachmentNames.value = _attachmentNames.value - attachmentId
                    _attachmentMediaTypes.value = _attachmentMediaTypes.value - attachmentId
                    _attachmentSizes.value = _attachmentSizes.value - attachmentId
                    _attachmentPaths.value = _attachmentPaths.value - attachmentId
                    removeAttachmentFromState(attachmentId)
                }
                .onFailure { succeeded = false }
        }
        return succeeded
    }

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            else -> "application/octet-stream"
        }
    }

    private fun queryFileName(uri: android.net.Uri): String? =
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        }.getOrNull()
}
