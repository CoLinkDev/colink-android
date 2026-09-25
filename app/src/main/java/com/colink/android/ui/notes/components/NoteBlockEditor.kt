package com.colink.android.ui.notes.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.colink.android.R
import com.colink.android.ui.notes.codec.NoteMarkdownCodec
import com.colink.android.ui.notes.model.NoteBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class EditorCommand {
    Undo, Redo, CycleHeading, Bold, Italic, Strike, InlineCode,
    Bullet, Ordered, Todo, Quote, CodeBlock,
}

@Stable
class NoteBlockEditorState internal constructor() {
    internal var commandHandler: ((EditorCommand) -> Unit)? = null

    fun undo() = dispatch(EditorCommand.Undo)
    fun redo() = dispatch(EditorCommand.Redo)
    fun cycleHeading() = dispatch(EditorCommand.CycleHeading)
    fun bold() = dispatch(EditorCommand.Bold)
    fun italic() = dispatch(EditorCommand.Italic)
    fun strike() = dispatch(EditorCommand.Strike)
    fun inlineCode() = dispatch(EditorCommand.InlineCode)
    fun bullet() = dispatch(EditorCommand.Bullet)
    fun ordered() = dispatch(EditorCommand.Ordered)
    fun todo() = dispatch(EditorCommand.Todo)
    fun quote() = dispatch(EditorCommand.Quote)
    fun codeBlock() = dispatch(EditorCommand.CodeBlock)

    private fun dispatch(command: EditorCommand) {
        commandHandler?.invoke(command)
    }
}

@Composable
fun rememberNoteBlockEditorState(): NoteBlockEditorState = remember { NoteBlockEditorState() }

@Composable
fun NoteBlockEditor(
    state: NoteBlockEditorState,
    markdown: String,
    enabled: Boolean,
    attachmentPaths: Map<String, String>,
    onMarkdownChange: (String) -> Unit,
    onSelectionOffsetChange: (Int) -> Unit,
    onLoadAttachment: (String) -> Unit,
    onPreviewAttachment: (path: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun parse(value: String): List<NoteBlock> =
        NoteMarkdownCodec.parse(value).let { parsed ->
            when {
                parsed.isEmpty() -> listOf(NoteBlock.Paragraph(""))
                parsed.last() is NoteBlock.Image -> parsed + NoteBlock.Paragraph("")
                else -> parsed
            }
        }

    var blocks by remember { mutableStateOf(parse(markdown)) }
    var values by remember { mutableStateOf(blocks.map { TextFieldValue(it.editableText()) }) }
    var lastEmittedMarkdown by remember { mutableStateOf(markdown) }
    var activeIndex by remember { mutableIntStateOf(0) }
    var pendingFocus by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val undoStack = remember { ArrayDeque<String>() }
    val redoStack = remember { ArrayDeque<String>() }
    val focusRequesters = remember(blocks.size) { List(blocks.size) { FocusRequester() } }

    fun replaceDocument(nextMarkdown: String, emit: Boolean) {
        val nextBlocks = parse(nextMarkdown)
        blocks = nextBlocks
        values = nextBlocks.map { TextFieldValue(it.editableText()) }
        activeIndex = activeIndex.coerceIn(0, nextBlocks.lastIndex)
        lastEmittedMarkdown = nextMarkdown
        if (emit) onMarkdownChange(nextMarkdown)
    }

    fun commit(nextBlocks: List<NoteBlock>, nextValues: List<TextFieldValue>, recordHistory: Boolean = true) {
        val serialized = NoteMarkdownCodec.serialize(nextBlocks)
        if (recordHistory && serialized != lastEmittedMarkdown) {
            undoStack.addLast(lastEmittedMarkdown)
            while (undoStack.size > 50) undoStack.removeFirst()
            redoStack.clear()
        }
        blocks = nextBlocks
        values = nextValues
        lastEmittedMarkdown = serialized
        onMarkdownChange(serialized)
    }

    fun updateSelection(index: Int, value: TextFieldValue) {
        values = values.toMutableList().also { it[index] = value }
        onSelectionOffsetChange(NoteMarkdownCodec.textOffset(blocks, index, value.selection.end))
    }

    fun setBlockType(replacement: NoteBlock) {
        val nextBlocks = blocks.toMutableList().also { it[activeIndex] = replacement }
        commit(nextBlocks, values)
    }

    fun toggleInline(marker: String) {
        val block = blocks.getOrNull(activeIndex) ?: return
        if (block is NoteBlock.Image || block is NoteBlock.Code) return
        val value = values[activeIndex]
        val start = value.selection.min
        val end = value.selection.max
        val wrapped = start >= marker.length && end + marker.length <= value.text.length &&
            value.text.substring(start - marker.length, start) == marker &&
            value.text.substring(end, end + marker.length) == marker
        val next = if (wrapped) {
            val text = value.text.removeRange(end, end + marker.length).removeRange(start - marker.length, start)
            TextFieldValue(text, TextRange(start - marker.length, end - marker.length))
        } else {
            val text = value.text.substring(0, start) + marker + value.text.substring(start, end) + marker + value.text.substring(end)
            TextFieldValue(text, TextRange(start + marker.length, end + marker.length))
        }
        val nextValues = values.toMutableList().also { it[activeIndex] = next }
        val nextBlocks = blocks.toMutableList().also { it[activeIndex] = block.withText(next.text) }
        commit(nextBlocks, nextValues)
    }

    fun updateText(index: Int, next: TextFieldValue) {
        val current = values[index]
        val block = blocks[index]
        if (block !is NoteBlock.Code && next.composition == null && next.text.length > current.text.length) {
            val prefix = current.text.commonPrefixWith(next.text).length
            val insertedLength = next.text.length - current.text.length
            val inserted = next.text.substring(prefix, (prefix + insertedLength).coerceAtMost(next.text.length))
            val lineBreak = inserted.indexOf('\n')
            if (lineBreak >= 0) {
                val splitAt = prefix + lineBreak
                if (current.text.isBlank() && block.isListBlock()) {
                    val paragraph = NoteBlock.Paragraph("")
                    val nextBlocks = blocks.toMutableList().also { it[index] = paragraph }
                    val nextValues = values.toMutableList().also { it[index] = TextFieldValue("") }
                    pendingFocus = index to 0
                    commit(nextBlocks, nextValues)
                    return
                }
                val before = next.text.substring(0, splitAt)
                val after = next.text.substring(splitAt + 1)
                val continuation = block.continuation(after)
                val nextBlocks = blocks.toMutableList().also {
                    it[index] = block.withText(before)
                    it.add(index + 1, continuation)
                }
                val cursor = (next.selection.end - splitAt - 1).coerceIn(0, after.length)
                val nextValues = values.toMutableList().also {
                    it[index] = TextFieldValue(before, TextRange(before.length))
                    it.add(index + 1, TextFieldValue(after, TextRange(cursor)))
                }
                activeIndex = index + 1
                pendingFocus = index + 1 to cursor
                commit(nextBlocks, nextValues)
                return
            }
        }
        updateSelection(index, next)
        if (current.text != next.text) {
            val nextBlocks = blocks.toMutableList().also { it[index] = block.withText(next.text) }
            commit(nextBlocks, values)
        }
    }

    fun mergeBackward(index: Int): Boolean {
        if (index <= 0) return false
        val value = values[index]
        if (!value.selection.collapsed || value.selection.start != 0) return false
        val previous = blocks[index - 1]
        val current = blocks[index]
        if (previous is NoteBlock.Image || current is NoteBlock.Image) return false
        val previousText = previous.editableText()
        val mergedText = previousText + current.editableText()
        val nextBlocks = blocks.toMutableList().also {
            it[index - 1] = previous.withText(mergedText)
            it.removeAt(index)
        }
        val nextValues = values.toMutableList().also {
            it[index - 1] = TextFieldValue(mergedText, TextRange(previousText.length))
            it.removeAt(index)
        }
        activeIndex = index - 1
        pendingFocus = index - 1 to previousText.length
        commit(nextBlocks, nextValues)
        return true
    }

    fun execute(command: EditorCommand) {
        if (!enabled) return
        val block = blocks.getOrNull(activeIndex) ?: return
        val text = block.editableText()
        when (command) {
            EditorCommand.Undo -> undoStack.removeLastOrNull()?.let { previous ->
                redoStack.addLast(lastEmittedMarkdown)
                replaceDocument(previous, emit = true)
            }
            EditorCommand.Redo -> redoStack.removeLastOrNull()?.let { next ->
                undoStack.addLast(lastEmittedMarkdown)
                replaceDocument(next, emit = true)
            }
            EditorCommand.CycleHeading -> setBlockType(
                when (block) {
                    is NoteBlock.Heading -> if (block.level < 3) block.copy(level = block.level + 1) else NoteBlock.Paragraph(text)
                    else -> NoteBlock.Heading(1, text)
                },
            )
            EditorCommand.Bold -> toggleInline("**")
            EditorCommand.Italic -> toggleInline("_")
            EditorCommand.Strike -> toggleInline("~~")
            EditorCommand.InlineCode -> toggleInline("`")
            EditorCommand.Bullet -> setBlockType(NoteBlock.Bullet(text))
            EditorCommand.Ordered -> setBlockType(NoteBlock.Ordered(1, text))
            EditorCommand.Todo -> setBlockType(NoteBlock.Todo((block as? NoteBlock.Todo)?.checked == true, text))
            EditorCommand.Quote -> setBlockType(NoteBlock.Quote(text))
            EditorCommand.CodeBlock -> setBlockType(NoteBlock.Code("", text))
        }
    }

    LaunchedEffect(markdown) {
        if (markdown != lastEmittedMarkdown) replaceDocument(markdown, emit = false)
    }
    LaunchedEffect(focusRequesters, pendingFocus) {
        pendingFocus?.let { (index, cursor) ->
            values.getOrNull(index)?.let { value ->
                values = values.toMutableList().also { it[index] = value.copy(selection = TextRange(cursor.coerceIn(0, value.text.length))) }
            }
            focusRequesters.getOrNull(index)?.requestFocus()
            pendingFocus = null
        }
    }
    SideEffect { state.commandHandler = ::execute }
    DisposableEffect(state) {
        onDispose { state.commandHandler = null }
    }

    val showDocumentPlaceholder = shouldShowDocumentPlaceholder(blocks)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        blocks.forEachIndexed { index, block ->
            if (block is NoteBlock.Image) {
                AttachmentImageBlock(
                    block = block,
                    path = attachmentPaths[block.attachmentId],
                    enabled = enabled,
                    onLoad = { onLoadAttachment(block.attachmentId) },
                    onPreview = {
                        attachmentPaths[block.attachmentId]?.let { path ->
                            onPreviewAttachment(path, block.alt)
                        }
                    },
                    onRemove = {
                        val nextBlocks = blocks.toMutableList().also { it.removeAt(index) }
                            .ifEmpty { mutableListOf(NoteBlock.Paragraph("")) }
                        val nextValues = nextBlocks.map { TextFieldValue(it.editableText()) }
                        activeIndex = activeIndex.coerceIn(0, nextBlocks.lastIndex)
                        commit(nextBlocks, nextValues)
                    },
                )
            } else {
                EditableBlock(
                    block = block,
                    value = values[index],
                    enabled = enabled,
                    showPlaceholder = showDocumentPlaceholder && index == 0,
                    focusRequester = focusRequesters[index],
                    onFocus = {
                        activeIndex = index
                        onSelectionOffsetChange(NoteMarkdownCodec.textOffset(blocks, index, values[index].selection.end))
                    },
                    onValueChange = { updateText(index, it) },
                    onBackspaceAtStart = { mergeBackward(index) },
                    onToggleTodo = {
                        (block as? NoteBlock.Todo)?.let { todo ->
                            val nextBlocks = blocks.toMutableList().also { it[index] = todo.copy(checked = !todo.checked) }
                            commit(nextBlocks, values)
                        }
                    },
                )
            }
        }
    }

}

@Composable
private fun EditableBlock(
    block: NoteBlock,
    value: TextFieldValue,
    enabled: Boolean,
    showPlaceholder: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    onBackspaceAtStart: () -> Boolean,
    onToggleTodo: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val transformation = InlineMarkdownVisualTransformation(
        accent = colors.primary,
        codeBackground = colors.surfaceContainerHighest,
    )
    val textStyle = when (block) {
        is NoteBlock.Heading -> when (block.level) {
            1 -> MaterialTheme.typography.headlineLarge
            2 -> MaterialTheme.typography.headlineMedium
            else -> MaterialTheme.typography.titleLarge
        }.copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface)
        is NoteBlock.Code -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = colors.onSurface)
        is NoteBlock.Todo -> MaterialTheme.typography.bodyLarge.copy(
            color = colors.onSurface.copy(alpha = if (block.checked) 0.55f else 1f),
            textDecoration = if (block.checked) TextDecoration.LineThrough else null,
        )
        else -> MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
    }

    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = textStyle,
            visualTransformation = if (block is NoteBlock.Code) VisualTransformation.None else transformation,
            modifier = fieldModifier
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocus() }
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown && event.key == Key.Backspace && onBackspaceAtStart()
            },
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (showPlaceholder && value.text.isEmpty()) {
                        Text(
                            stringResource(R.string.notes_markdown_placeholder),
                            style = textStyle,
                            color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                        )
                    }
                    inner()
                }
            },
        )
    }

    when (block) {
        is NoteBlock.Todo -> Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = block.checked, onCheckedChange = { onToggleTodo() }, enabled = enabled)
            field(Modifier.weight(1f))
        }
        is NoteBlock.Bullet -> Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Text("•", style = textStyle, modifier = Modifier.width(28.dp))
            field(Modifier.weight(1f))
        }
        is NoteBlock.Ordered -> Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Text("${block.number}.", style = textStyle, modifier = Modifier.width(32.dp))
            field(Modifier.weight(1f))
        }
        is NoteBlock.Quote -> Surface(
            color = colors.surfaceContainerLow,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(end = 10.dp)) {
                Box(Modifier.width(3.dp).heightIn(min = 44.dp).background(colors.primary))
                field(Modifier.weight(1f).padding(start = 12.dp))
            }
        }
        is NoteBlock.Code -> Surface(
            color = colors.surfaceContainerLow,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            field(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
        }
        else -> field(Modifier.fillMaxWidth())
    }
}

@Composable
private fun AttachmentImageBlock(
    block: NoteBlock.Image,
    path: String?,
    enabled: Boolean,
    onLoad: () -> Unit,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    LaunchedEffect(block.attachmentId, path) { if (path == null) onLoad() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = path?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    }
    Surface(
        onClick = onPreview,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = block.alt,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                )
            } else {
                Text(
                    block.alt.ifBlank { stringResource(R.string.notes_attachment_unavailable) },
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enabled) {
                IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.notes_remove_from_body))
                }
            }
        }
    }
}

internal class InlineMarkdownVisualTransformation(
    private val accent: Color,
    private val codeBackground: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.text.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val source = text.text
        val hidden = BooleanArray(source.length)
        val spans = mutableListOf<StyledRange>()
        val caretAnchors = mutableListOf<Int>()

        Regex("\\[([^]\\n]+)]\\(([^)\\n]+)\\)").findAll(source).forEach { match ->
            val label = match.groups[1] ?: return@forEach
            hide(hidden, match.range.first, label.range.first)
            hide(hidden, label.range.last + 1, match.range.last + 1)
            spans += StyledRange(label.range.first, label.range.last + 1, SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
        }
        addDelimited(source, hidden, spans, caretAnchors, "**", SpanStyle(fontWeight = FontWeight.Bold))
        addDelimited(source, hidden, spans, caretAnchors, "~~", SpanStyle(textDecoration = TextDecoration.LineThrough))
        addDelimited(
            source,
            hidden,
            spans,
            caretAnchors,
            "`",
            SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
        )
        addDelimited(source, hidden, spans, caretAnchors, "_", SpanStyle(fontStyle = FontStyle.Italic))
        addDelimited(source, hidden, spans, caretAnchors, "*", SpanStyle(fontStyle = FontStyle.Italic))

        if (hidden.none { it }) return TransformedText(text, OffsetMapping.Identity)
        val originalToTransformed = IntArray(source.length + 1)
        for (index in source.indices) {
            originalToTransformed[index + 1] = originalToTransformed[index] + if (hidden[index]) 0 else 1
        }
        val transformedLength = originalToTransformed.last()
        val transformedToOriginal = IntArray(transformedLength + 1)
        var original = 0
        for (transformed in 0..transformedLength) {
            while (original < source.length && originalToTransformed[original + 1] <= transformed) original += 1
            transformedToOriginal[transformed] = original
        }
        caretAnchors.forEach { anchor ->
            transformedToOriginal[originalToTransformed[anchor]] = anchor
        }
        val styled = buildAnnotatedString {
            source.forEachIndexed { index, char -> if (!hidden[index]) append(char) }
            spans.forEach { span ->
                val start = originalToTransformed[span.start]
                val end = originalToTransformed[span.end]
                if (start < end) addStyle(span.style, start, end)
            }
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = originalToTransformed[offset.coerceIn(0, source.length)]
            override fun transformedToOriginal(offset: Int): Int = transformedToOriginal[offset.coerceIn(0, transformedLength)]
        }
        return TransformedText(styled, mapping)
    }

    private data class StyledRange(val start: Int, val end: Int, val style: SpanStyle)

    private fun addDelimited(
        source: String,
        hidden: BooleanArray,
        spans: MutableList<StyledRange>,
        caretAnchors: MutableList<Int>,
        marker: String,
        style: SpanStyle,
    ) {
        var start = source.indexOf(marker)
        while (start >= 0) {
            if (marker == "*" && (source.getOrNull(start - 1) == '*' || source.getOrNull(start + 1) == '*')) {
                start = source.indexOf(marker, start + 1)
                continue
            }
            val contentStart = start + marker.length
            val end = source.indexOf(marker, contentStart)
            if (end < 0) break
            val occupied = (start until end + marker.length).any { hidden[it] }
            if (!occupied && end >= contentStart) {
                hide(hidden, start, contentStart)
                hide(hidden, end, end + marker.length)
                spans += StyledRange(contentStart, end, style)
                if (end == contentStart) caretAnchors += contentStart
            }
            start = source.indexOf(marker, end + marker.length)
        }
    }

    private fun hide(hidden: BooleanArray, start: Int, end: Int) {
        for (index in start.coerceAtLeast(0) until end.coerceAtMost(hidden.size)) hidden[index] = true
    }
}

private fun NoteBlock.editableText(): String = when (this) {
    is NoteBlock.Heading -> text
    is NoteBlock.Paragraph -> text
    is NoteBlock.Todo -> text
    is NoteBlock.Bullet -> text
    is NoteBlock.Ordered -> text
    is NoteBlock.Quote -> text
    is NoteBlock.Code -> text
    is NoteBlock.Image -> ""
}

private fun NoteBlock.withText(value: String): NoteBlock = when (this) {
    is NoteBlock.Heading -> copy(text = value)
    is NoteBlock.Paragraph -> copy(text = value)
    is NoteBlock.Todo -> copy(text = value)
    is NoteBlock.Bullet -> copy(text = value)
    is NoteBlock.Ordered -> copy(text = value)
    is NoteBlock.Quote -> copy(text = value)
    is NoteBlock.Code -> copy(text = value)
    is NoteBlock.Image -> this
}

private fun NoteBlock.continuation(value: String): NoteBlock = when (this) {
    is NoteBlock.Todo -> NoteBlock.Todo(false, value)
    is NoteBlock.Bullet -> NoteBlock.Bullet(value)
    is NoteBlock.Ordered -> NoteBlock.Ordered(number + 1, value)
    is NoteBlock.Quote -> NoteBlock.Quote(value)
    else -> NoteBlock.Paragraph(value)
}

private fun NoteBlock.isListBlock(): Boolean =
    this is NoteBlock.Todo || this is NoteBlock.Bullet || this is NoteBlock.Ordered || this is NoteBlock.Quote

internal fun shouldShowDocumentPlaceholder(blocks: List<NoteBlock>): Boolean =
    blocks.size == 1 && blocks.first() == NoteBlock.Paragraph("")
