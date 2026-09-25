package com.colink.android.ui.notes.codec

import com.colink.android.ui.notes.model.NoteBlock

object NoteMarkdownCodec {
    private val headingPattern = Regex("^(#{1,3})[ \\t]+(.*)$")
    private val todoPattern = Regex("^[ \\t]*[-*+][ \\t]+\\[([ xX])]\\s*(.*)$")
    private val bulletPattern = Regex("^[ \\t]*[-*+][ \\t]+(.*)$")
    private val orderedPattern = Regex("^[ \\t]*(\\d+)[.)][ \\t]+(.*)$")
    private val quotePattern = Regex("^>[ \\t]?(.*)$")
    private val imagePattern = Regex("^!\\[([^]]*)]\\(colink-attachment://([^)]+)\\)[ \\t]*$")
    private val fencePattern = Regex("^```([^`]*)$")

    fun parse(markdown: String): List<NoteBlock> {
        if (markdown.isBlank()) return emptyList()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<NoteBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index += 1
                continue
            }
            val fence = fencePattern.matchEntire(line)
            if (fence != null) {
                val closingIndex = (index + 1 until lines.size).firstOrNull { lines[it] == "```" }
                if (closingIndex != null) {
                    blocks += NoteBlock.Code(
                        language = fence.groupValues[1].trim(),
                        text = lines.subList(index + 1, closingIndex).joinToString("\n"),
                    )
                    index = closingIndex + 1
                    continue
                }
            }
            val singleLineBlock = parseSingleLine(line)
            if (singleLineBlock != null) {
                blocks += singleLineBlock
                index += 1
                continue
            }
            val paragraph = mutableListOf(line)
            index += 1
            while (index < lines.size && lines[index].isNotBlank() && !startsBlock(lines[index])) {
                paragraph += lines[index]
                index += 1
            }
            blocks += NoteBlock.Paragraph(paragraph.joinToString("\n"))
        }
        return blocks
    }

    fun serialize(blocks: List<NoteBlock>): String = buildString {
        blocks.forEachIndexed { index, block ->
            if (index > 0) append(separator(blocks[index - 1], block))
            append(serializeBlock(block))
        }
    }

    fun textOffset(blocks: List<NoteBlock>, blockIndex: Int, localOffset: Int): Int {
        require(blockIndex in blocks.indices)
        var offset = 0
        for (index in 0 until blockIndex) {
            if (index > 0) offset += separator(blocks[index - 1], blocks[index]).length
            offset += serializeBlock(blocks[index]).length
        }
        if (blockIndex > 0) offset += separator(blocks[blockIndex - 1], blocks[blockIndex]).length
        return offset + textPrefixLength(blocks[blockIndex]) + localOffset.coerceIn(0, blocks[blockIndex].editableTextLength())
    }

    private fun parseSingleLine(line: String): NoteBlock? {
        imagePattern.matchEntire(line)?.let { return NoteBlock.Image(it.groupValues[1], it.groupValues[2]) }
        headingPattern.matchEntire(line)?.let { return NoteBlock.Heading(it.groupValues[1].length, it.groupValues[2]) }
        todoPattern.matchEntire(line)?.let {
            return NoteBlock.Todo(it.groupValues[1].equals("x", ignoreCase = true), it.groupValues[2])
        }
        bulletPattern.matchEntire(line)?.let { return NoteBlock.Bullet(it.groupValues[1]) }
        orderedPattern.matchEntire(line)?.let { return NoteBlock.Ordered(it.groupValues[1].toIntOrNull() ?: 1, it.groupValues[2]) }
        quotePattern.matchEntire(line)?.let { return NoteBlock.Quote(it.groupValues[1]) }
        return null
    }

    private fun startsBlock(line: String): Boolean =
        fencePattern.matches(line) || imagePattern.matches(line) || headingPattern.matches(line) ||
            todoPattern.matches(line) || bulletPattern.matches(line) || orderedPattern.matches(line) || quotePattern.matches(line)

    private fun serializeBlock(block: NoteBlock): String = when (block) {
        is NoteBlock.Heading -> "${"#".repeat(block.level.coerceIn(1, 3))} ${block.text}"
        is NoteBlock.Paragraph -> block.text
        is NoteBlock.Todo -> "- [${if (block.checked) "x" else " "}] ${block.text}"
        is NoteBlock.Bullet -> "- ${block.text}"
        is NoteBlock.Ordered -> "${block.number}. ${block.text}"
        is NoteBlock.Quote -> "> ${block.text}"
        is NoteBlock.Code -> "```${block.language}\n${block.text}\n```"
        is NoteBlock.Image -> "![${block.alt}](colink-attachment://${block.attachmentId})"
    }

    private fun separator(previous: NoteBlock, current: NoteBlock): String =
        if (continuesLineGroup(previous, current)) "\n" else "\n\n"

    private fun continuesLineGroup(previous: NoteBlock, current: NoteBlock): Boolean =
        ((previous is NoteBlock.Bullet || previous is NoteBlock.Todo) &&
            (current is NoteBlock.Bullet || current is NoteBlock.Todo)) ||
            (previous is NoteBlock.Ordered && current is NoteBlock.Ordered) ||
            (previous is NoteBlock.Quote && current is NoteBlock.Quote)

    private fun textPrefixLength(block: NoteBlock): Int = when (block) {
        is NoteBlock.Heading -> block.level.coerceIn(1, 3) + 1
        is NoteBlock.Paragraph -> 0
        is NoteBlock.Todo -> 6
        is NoteBlock.Bullet -> 2
        is NoteBlock.Ordered -> block.number.toString().length + 2
        is NoteBlock.Quote -> 2
        is NoteBlock.Code -> block.language.length + 4
        is NoteBlock.Image -> 0
    }

    private fun NoteBlock.editableTextLength(): Int = when (this) {
        is NoteBlock.Heading -> text.length
        is NoteBlock.Paragraph -> text.length
        is NoteBlock.Todo -> text.length
        is NoteBlock.Bullet -> text.length
        is NoteBlock.Ordered -> text.length
        is NoteBlock.Quote -> text.length
        is NoteBlock.Code -> text.length
        is NoteBlock.Image -> 0
    }
}
