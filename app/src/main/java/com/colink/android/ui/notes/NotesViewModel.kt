package com.colink.android.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.domain.model.Note
import com.colink.android.domain.model.NoteTag
import com.colink.android.domain.model.NotesSyncOutcome
import com.colink.android.domain.repository.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
) : ViewModel() {

    val notes: StateFlow<List<Note>> = notesRepository.notes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<NoteTag>> = notesRepository.tags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _tagMutating = MutableStateFlow(false)
    val tagMutating: StateFlow<Boolean> = _tagMutating.asStateFlow()

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 1)
    val messages: SharedFlow<Message> = _messages

    fun syncNow() {
        if (_syncing.value) {
            return
        }
        _syncing.value = true
        viewModelScope.launch {
            try {
                notesRepository.sync().fold(
                    onSuccess = { outcome ->
                        when {
                            outcome.status == "offline" -> _messages.emit(Message.Text(R.string.notes_sync_offline))
                            outcome.hasReportableChanges() -> _messages.emit(Message.SyncCompleted(outcome))
                        }
                    },
                    onFailure = { _messages.emit(Message.Text(R.string.status_failed)) },
                )
            } finally {
                _syncing.value = false
            }
        }
    }

    fun deleteNote(noteId: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(notesRepository.deleteNote(noteId))
        }
    }

    fun deleteTag(tagId: String, onResult: (Result<Unit>) -> Unit) {
        if (_tagMutating.value) return
        _tagMutating.value = true
        viewModelScope.launch {
            try {
                onResult(notesRepository.deleteTag(tagId))
            } finally {
                _tagMutating.value = false
            }
        }
    }

    fun renameTag(tagId: String, name: String, onResult: (Result<NoteTag>) -> Unit) {
        if (_tagMutating.value) return
        _tagMutating.value = true
        viewModelScope.launch {
            try {
                onResult(notesRepository.renameTag(tagId, name))
            } finally {
                _tagMutating.value = false
            }
        }
    }

    fun createTag(name: String, onResult: (Result<NoteTag>) -> Unit = {}) {
        if (_tagMutating.value) return
        _tagMutating.value = true
        viewModelScope.launch {
            try {
                onResult(notesRepository.createTag(name))
            } finally {
                _tagMutating.value = false
            }
        }
    }

    sealed interface Message {
        data class Text(val resourceId: Int) : Message

        data class SyncCompleted(val outcome: NotesSyncOutcome) : Message
    }

}

private fun NotesSyncOutcome.hasReportableChanges(): Boolean =
    pushedNotes > 0 ||
        pushedTags > 0 ||
        pushedAttachments > 0 ||
        pulledNotes > 0 ||
        pulledTags > 0 ||
        conflicts > 0 ||
        repairedReferences > 0
