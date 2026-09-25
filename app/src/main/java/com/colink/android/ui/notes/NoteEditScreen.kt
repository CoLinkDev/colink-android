package com.colink.android.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.domain.model.NoteSyncState
import com.colink.android.ui.components.CoLinkTextField
import com.colink.android.ui.notes.components.ConflictResolutionBottomSheet
import com.colink.android.ui.notes.components.NoteAttachmentsBottomSheet
import com.colink.android.ui.notes.components.NoteBlockEditor
import com.colink.android.ui.notes.components.NoteEditorAccessoryBar
import com.colink.android.ui.notes.components.NoteTagsBottomSheet
import com.colink.android.ui.notes.components.rememberNoteBlockEditorState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteEditScreen(
    noteId: String,
    onDone: () -> Unit,
    onPreviewImage: (path: String, name: String) -> Unit,
    viewModel: NoteEditViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tags by viewModel.tagOptions.collectAsStateWithLifecycle()
    val attachmentNames by viewModel.attachmentNames.collectAsStateWithLifecycle()
    val attachmentMediaTypes by viewModel.attachmentMediaTypes.collectAsStateWithLifecycle()
    val attachmentSizes by viewModel.attachmentSizes.collectAsStateWithLifecycle()
    val attachmentPaths by viewModel.attachmentPaths.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val conflict = state.syncState == NoteSyncState.Conflict || state.syncState == NoteSyncState.ConflictDelete
    val imeVisible = WindowInsets.isImeVisible
    val editorState = rememberNoteBlockEditorState()

    var showTags by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    var showConflict by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showMerge by remember { mutableStateOf(false) }
    var mergedTitle by remember { mutableStateOf("") }
    var mergedMarkdown by remember { mutableStateOf("") }

    LaunchedEffect(noteId) { viewModel.load(noteId) }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.flush()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flush()
        }
    }

    fun close() = viewModel.flush(onDone)
    BackHandler(onBack = ::close)

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.stageAttachment(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.stageAttachment(uri)
    }

    fun shareNote() {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_SUBJECT, state.title)
                    putExtra(Intent.EXTRA_TEXT, state.markdown)
                },
                null,
            ),
        )
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = ::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.notes_close))
                    }
                },
                actions = {
                    IconButton(onClick = { showTags = true }) {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = stringResource(R.string.notes_tags))
                    }
                    IconButton(onClick = { showAttachments = true }) {
                        Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.notes_attachments))
                    }
                    IconButton(onClick = { showMore = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.notes_more_actions))
                    }
                    DropdownMenu(
                        expanded = showMore,
                        onDismissRequest = { showMore = false },
                        modifier = Modifier.widthIn(min = 200.dp),
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                        shadowElevation = 3.dp,
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_export)) },
                            leadingIcon = {
                                Icon(Icons.Default.Share, contentDescription = null)
                            },
                            onClick = {
                                showMore = false
                                shareNote()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_copy_text)) },
                            leadingIcon = {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                            },
                            onClick = {
                                showMore = false
                                clipboardManager.setPrimaryClip(ClipData.newPlainText(state.title, state.markdown))
                                Toast.makeText(context, R.string.notes_copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                        if (state.id != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.notes_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = { showMore = false; showDelete = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = imeVisible,
                enter = fadeIn(tween(150)) + expandVertically(tween(150), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150), shrinkTowards = Alignment.Bottom),
            ) {
                NoteEditorAccessoryBar(
                    enabled = !conflict,
                    onUndo = editorState::undo,
                    onRedo = editorState::redo,
                    onHeading = editorState::cycleHeading,
                    onBold = editorState::bold,
                    onItalic = editorState::italic,
                    onStrike = editorState::strike,
                    onInlineCode = editorState::inlineCode,
                    onBullet = editorState::bullet,
                    onOrdered = editorState::ordered,
                    onTodo = editorState::todo,
                    onQuote = editorState::quote,
                    onCodeBlock = editorState::codeBlock,
                    onImage = { imagePicker.launch(arrayOf("image/*")) },
                    onHideKeyboard = focusManager::clearFocus,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (conflict) {
                Surface(
                    onClick = { showConflict = true },
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                        Text(stringResource(R.string.notes_conflict_desc))
                    }
                }
            }
            BasicTextField(
                value = state.title,
                onValueChange = viewModel::updateTitle,
                enabled = !conflict,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                ),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        if (state.title.isEmpty()) {
                            Text(
                                stringResource(R.string.notes_title_placeholder),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    when {
                        saving -> R.string.notes_saving
                        dirty -> R.string.notes_unsaved
                        else -> R.string.notes_saved
                    },
                ) + " · " + stringResource(R.string.notes_character_count, state.markdown.length),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            HorizontalDivider(
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.8.dp,
            )
            NoteBlockEditor(
                state = editorState,
                markdown = state.markdown,
                enabled = !conflict,
                attachmentPaths = attachmentPaths,
                onMarkdownChange = viewModel::updateMarkdown,
                onSelectionOffsetChange = viewModel::updateSelection,
                onLoadAttachment = viewModel::loadAttachmentImage,
                onPreviewAttachment = onPreviewImage,
                modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
            )
        }
    }

    if (showTags) {
        NoteTagsBottomSheet(
            tags = tags,
            selectedIds = state.tagIds.toSet(),
            onToggle = viewModel::toggleTag,
            onCreate = viewModel::addTag,
            onDismiss = { showTags = false },
        )
    }
    if (showAttachments) {
        NoteAttachmentsBottomSheet(
            attachmentIds = state.attachmentIds,
            names = attachmentNames,
            mediaTypes = attachmentMediaTypes,
            sizes = attachmentSizes,
            paths = attachmentPaths,
            enabled = !conflict && !saving,
            onOpen = { viewModel.openAttachment(it, context) },
            onInsert = viewModel::insertAttachmentReference,
            onRemove = viewModel::removeAttachmentReference,
            onAdd = { filePicker.launch(arrayOf("*/*")) },
            onLoadImage = viewModel::loadAttachmentImage,
            onDismiss = { showAttachments = false },
        )
    }
    if (showConflict) {
        ConflictResolutionBottomSheet(
            conflictKind = state.conflictKind,
            localTitle = state.title,
            localMarkdown = state.markdown,
            cloudTitle = state.conflictTitle,
            cloudMarkdown = state.conflictMarkdown,
            onResolve = { viewModel.resolveConflict(it, null, null, onDone) },
            onMerge = {
                mergedTitle = state.title
                mergedMarkdown = state.markdown
                showConflict = false
                showMerge = true
            },
            onDismiss = { showConflict = false },
        )
    }
    if (showMerge) {
        AlertDialog(
            onDismissRequest = { showMerge = false },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text(stringResource(R.string.notes_merge_manually)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CoLinkTextField(
                        value = mergedTitle,
                        onValueChange = { mergedTitle = it },
                        label = { Text(stringResource(R.string.notes_title_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CoLinkTextField(
                        value = mergedMarkdown,
                        onValueChange = { mergedMarkdown = it },
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.resolveConflict("merged", mergedTitle, mergedMarkdown, onDone)
                    showMerge = false
                }) { Text(stringResource(R.string.save_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showMerge = false }) { Text(stringResource(R.string.cancel_btn)) }
            },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.notes_delete)) },
            text = { Text(stringResource(R.string.notes_delete_confirm)) },
            confirmButton = {
                Button(
                    onClick = { showDelete = false; viewModel.deleteNote(onDone) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.delete_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel_btn)) }
            },
        )
    }
}
