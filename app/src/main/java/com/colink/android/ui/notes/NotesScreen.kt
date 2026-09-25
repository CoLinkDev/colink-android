package com.colink.android.ui.notes

import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.domain.model.Note
import com.colink.android.domain.model.NoteSyncState
import com.colink.android.domain.model.NoteTag
import com.colink.android.domain.model.NotesSyncOutcome
import com.colink.android.ui.components.BadgeChip
import com.colink.android.ui.components.CoLinkTextField
import com.colink.android.ui.components.ContentGroupItem
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.LocalAccountAction
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.ui.components.ScreenHeader
import com.colink.android.ui.components.ScreenHeaderHeight
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onNoteSelected: (String) -> Unit,
    onNoteCreated: (String) -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val tagMutating by viewModel.tagMutating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val accountAction = LocalAccountAction.current
    val hapticFeedback = LocalHapticFeedback.current

    var search by rememberSaveable { mutableStateOf("") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var tagActionTarget by remember { mutableStateOf<NoteTag?>(null) }
    var tagToRename by remember { mutableStateOf<NoteTag?>(null) }
    var tagToDelete by remember { mutableStateOf<NoteTag?>(null) }
    var renamedTag by remember { mutableStateOf("") }
    var showCreateTag by remember { mutableStateOf(false) }
    var newTagName by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val fabExpanded by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 32
        }
    }

    fun closeSearch() {
        search = ""
        isSearchActive = false
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is NotesViewModel.Message.Text ->
                    Toast.makeText(context, message.resourceId, Toast.LENGTH_SHORT).show()
                is NotesViewModel.Message.SyncCompleted ->
                    Toast.makeText(context, context.syncCompletedMessage(message.outcome), Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.syncNow()
    }

    LaunchedEffect(tags, tagFilter) {
        if (tagFilter != null && tags.none { it.id == tagFilter }) tagFilter = null
    }

    val query = search.trim()
    val filtered = remember(notes, query, tagFilter) {
        notes.filter { note ->
            (tagFilter == null || tagFilter in note.tagIds) &&
                (query.isEmpty() || note.title.contains(query, true) || note.markdown.contains(query, true))
        }
    }

    BackHandler(enabled = isSearchActive, onBack = ::closeSearch)

    ScreenColumn(
        title = stringResource(R.string.nav_notes),
        icon = Icons.Outlined.EditNote,
        headerOverride = {
            Box(
                modifier = Modifier.fillMaxWidth().height(ScreenHeaderHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                AnimatedVisibility(
                    visible = !isSearchActive,
                    enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)),
                    exit = fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)),
                ) {
                    ScreenHeader(
                        title = stringResource(R.string.nav_notes),
                        icon = Icons.Outlined.EditNote,
                        action = {
                            Row {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = stringResource(R.string.notes_search_hint),
                                    )
                                }
                                if (!isLandscape) {
                                    accountAction?.invoke()
                                }
                            }
                        },
                    )
                }
                AnimatedVisibility(
                    visible = isSearchActive,
                    enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                        scaleIn(
                            transformOrigin = TransformOrigin(0.88f, 0.5f),
                            initialScale = 0.7f,
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                        ),
                    exit = fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                        scaleOut(
                            transformOrigin = TransformOrigin(0.88f, 0.5f),
                            targetScale = 0.7f,
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                        ),
                ) {
                    NotesSearchHeader(
                        query = search,
                        onQueryChange = { search = it },
                        onClose = ::closeSearch,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
    ) {
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = viewModel::syncNow,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 4.dp),
                ) {
                    item {
                        val selected = tagFilter == null
                        FilterChip(
                            selected = tagFilter == null,
                            onClick = { tagFilter = null },
                            label = { Text(stringResource(R.string.notes_all_tags), maxLines = 1) },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                    items(tags, key = { it.id }) { tag ->
                        val selected = tagFilter == tag.id
                        FilterChip(
                            selected = selected,
                            onClick = { tagFilter = if (tagFilter == tag.id) null else tag.id },
                            label = { Text(tag.name, maxLines = 1) },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.observeLongPress(tag) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                tagActionTarget = tag
                            },
                        )
                    }
                    item {
                        FilterChip(
                            selected = false,
                            onClick = { showCreateTag = true },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = { Text(stringResource(R.string.notes_new_tag)) },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (filtered.isEmpty()) {
                        EmptyState(
                            icon = if (query.isEmpty() && tagFilter == null) Icons.Outlined.EditNote else Icons.Default.Search,
                            title = stringResource(
                                if (query.isEmpty() && tagFilter == null) R.string.notes_empty else R.string.notes_no_results,
                            ),
                            body = stringResource(
                                if (query.isEmpty() && tagFilter == null) R.string.notes_empty_hint else R.string.notes_no_results_hint,
                            ),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 88.dp),
                        ) {
                            itemsIndexed(filtered, key = { _, note -> note.id }) { index, note ->
                                ContentGroupItem(
                                    isFirst = index == 0,
                                    isLast = index == filtered.lastIndex,
                                    modifier = Modifier.animateItem(),
                                ) {
                                    NoteListItem(
                                        note = note,
                                        query = query,
                                        tagName = { id -> tags.find { it.id == id }?.name ?: id.take(8) },
                                        onClick = { onNoteSelected(note.id) },
                                    )
                                }
                            }
                        }
                    }

                    ExtendedFloatingActionButton(
                        onClick = { onNoteCreated("new") },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.notes_new_note)) },
                        expanded = fabExpanded,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                }
            }
        }
    }

    tagActionTarget?.let { tag ->
        TagActionsBottomSheet(
            tag = tag,
            enabled = !tagMutating,
            onDismiss = { tagActionTarget = null },
            onRename = {
                tagActionTarget = null
                renamedTag = tag.name
                tagToRename = tag
            },
            onDelete = {
                tagActionTarget = null
                tagToDelete = tag
            },
        )
    }

    if (showCreateTag) {
        AlertDialog(
            onDismissRequest = { if (!tagMutating) showCreateTag = false },
            title = { Text(stringResource(R.string.notes_new_tag)) },
            text = {
                CoLinkTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    enabled = !tagMutating,
                    singleLine = true,
                    label = { Text(stringResource(R.string.notes_tag_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = !tagMutating && newTagName.isNotBlank(),
                    onClick = {
                        viewModel.createTag(newTagName.trim()) { result ->
                            result.onSuccess {
                                newTagName = ""
                                showCreateTag = false
                            }.onFailure { Toast.makeText(context, R.string.status_failed, Toast.LENGTH_SHORT).show() }
                        }
                    },
                ) { Text(stringResource(R.string.notes_new_tag)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTag = false }, enabled = !tagMutating) {
                    Text(stringResource(R.string.cancel_btn))
                }
            },
        )
    }

    tagToRename?.let { tag ->
        AlertDialog(
            onDismissRequest = { if (!tagMutating) tagToRename = null },
            title = { Text(stringResource(R.string.notes_rename_tag)) },
            text = {
                CoLinkTextField(
                    value = renamedTag,
                    onValueChange = { renamedTag = it },
                    enabled = !tagMutating,
                    singleLine = true,
                    label = { Text(stringResource(R.string.notes_tag_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = !tagMutating && renamedTag.isNotBlank() && renamedTag.trim() != tag.name,
                    onClick = {
                        viewModel.renameTag(tag.id, renamedTag.trim()) { result ->
                            result.onSuccess { tagToRename = null }
                                .onFailure { Toast.makeText(context, R.string.status_failed, Toast.LENGTH_SHORT).show() }
                        }
                    },
                ) { Text(stringResource(R.string.save_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { tagToRename = null }, enabled = !tagMutating) {
                    Text(stringResource(R.string.cancel_btn))
                }
            },
        )
    }

    tagToDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { if (!tagMutating) tagToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.notes_delete_tag)) },
            text = { Text(stringResource(R.string.notes_delete_tag_confirm, tag.name)) },
            confirmButton = {
                Button(
                    enabled = !tagMutating,
                    onClick = {
                        viewModel.deleteTag(tag.id) { result ->
                            result.onSuccess {
                                tagFilter = null
                                tagToDelete = null
                            }.onFailure { Toast.makeText(context, R.string.status_failed, Toast.LENGTH_SHORT).show() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.delete_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { tagToDelete = null }, enabled = !tagMutating) {
                    Text(stringResource(R.string.cancel_btn))
                }
            },
        )
    }
}

@Composable
private fun NotesSearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.notes_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.notes_clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagActionsBottomSheet(
    tag: NoteTag,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.notes_tag_actions_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                ContentGroupItem(isFirst = true, isLast = false) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.notes_rename_tag)) },
                        leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { dismissThen(onRename) },
                    )
                }
                ContentGroupItem(isFirst = false, isLast = true) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.notes_delete_tag)) },
                        leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { dismissThen(onDelete) },
                    )
                }
            }
        }
    }
}

private fun Modifier.observeLongPress(
    key: Any?,
    onLongPress: () -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        var latestChange = down
        var cancelled = false
        val finishedBeforeTimeout = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (latestChange.pressed) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (
                    change == null ||
                    !change.pressed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                ) {
                    cancelled = true
                    break
                }
                latestChange = change
            }
            true
        }
        if (finishedBeforeTimeout == null && !cancelled) {
            onLongPress()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

private fun Context.syncCompletedMessage(outcome: NotesSyncOutcome): String {
    val details = buildList {
        if (outcome.pushedNotes > 0) {
            add(getString(R.string.notes_sync_notes_sent, outcome.pushedNotes))
        }
        if (outcome.pulledNotes > 0) {
            add(getString(R.string.notes_sync_notes_received, outcome.pulledNotes))
        }
        if (outcome.pushedTags > 0) {
            add(getString(R.string.notes_sync_tags_sent, outcome.pushedTags))
        }
        if (outcome.pulledTags > 0) {
            add(getString(R.string.notes_sync_tags_received, outcome.pulledTags))
        }
        if (outcome.pushedAttachments > 0) {
            add(getString(R.string.notes_sync_attachments, outcome.pushedAttachments))
        }
        if (outcome.conflicts > 0) {
            add(getString(R.string.notes_sync_conflicts, outcome.conflicts))
        }
        if (outcome.repairedReferences > 0) {
            add(getString(R.string.notes_sync_repairs, outcome.repairedReferences))
        }
    }
    return getString(R.string.notes_sync_complete, details.joinToString(" · "))
}

@Composable
private fun NoteListItem(
    note: Note,
    query: String,
    tagName: (String) -> String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = note.title.ifBlank { stringResource(R.string.notes_untitled) }.highlightMatches(
                    query,
                    MaterialTheme.colorScheme.primaryContainer,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StateBadge(note.syncState)
        }
        val summary = note.markdown.toPlainTextPreview()
        if (summary.isNotEmpty()) {
            Text(
                text = summary.highlightMatches(query, MaterialTheme.colorScheme.primaryContainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(modifier = Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(note.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (note.attachmentIds.isNotEmpty()) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp).size(15.dp),
                )
                Text(
                    text = note.attachmentIds.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
        }
        if (note.tagIds.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(note.tagIds, key = { it }) { tagId ->
                    BadgeChip(
                        text = tagName(tagId),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
fun StateBadge(state: NoteSyncState, modifier: Modifier = Modifier) {
    if (state == NoteSyncState.Synced) return
    val (labelRes, color) = when (state) {
        NoteSyncState.Pending -> R.string.notes_state_pending to MaterialTheme.colorScheme.tertiary
        NoteSyncState.PendingDelete -> R.string.notes_state_pending_delete to MaterialTheme.colorScheme.tertiary
        NoteSyncState.Conflict, NoteSyncState.ConflictDelete -> R.string.notes_state_conflict to MaterialTheme.colorScheme.error
        NoteSyncState.Synced -> return
    }
    BadgeChip(
        text = stringResource(labelRes),
        modifier = modifier,
        containerColor = color.copy(alpha = 0.14f),
        contentColor = color,
    )
}

private fun String.toPlainTextPreview(): String =
    replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("[#>*`_~\\-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.highlightMatches(query: String, background: Color) = buildAnnotatedString {
    append(this@highlightMatches)
    if (query.isBlank()) return@buildAnnotatedString
    var start = indexOf(query, ignoreCase = true)
    while (start >= 0) {
        addStyle(SpanStyle(background = background), start, start + query.length)
        start = indexOf(query, startIndex = start + query.length, ignoreCase = true)
    }
}
