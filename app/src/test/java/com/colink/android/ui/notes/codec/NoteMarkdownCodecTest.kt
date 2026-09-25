package com.colink.android.ui.notes.codec

import com.colink.android.ui.notes.model.NoteBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMarkdownCodecTest {
    @Test
    fun parseAndSerialize_supportedBlocksRoundTrip() {
        val markdown = """# Heading

Paragraph with **bold** text.

- first
- [x] finished

> quote

```kotlin
val answer = 42
```

![photo](colink-attachment://image-id)"""

        val blocks = NoteMarkdownCodec.parse(markdown)

        assertEquals(NoteBlock.Heading(1, "Heading"), blocks[0])
        assertEquals(NoteBlock.Bullet("first"), blocks[2])
        assertEquals(NoteBlock.Todo(true, "finished"), blocks[3])
        assertEquals(NoteBlock.Code("kotlin", "val answer = 42"), blocks[5])
        assertEquals(markdown, NoteMarkdownCodec.serialize(blocks))
    }

    @Test
    fun textOffset_pointsInsideSerializedBlockContent() {
        val blocks = listOf(NoteBlock.Heading(2, "Title"), NoteBlock.Todo(false, "task"))
        val markdown = NoteMarkdownCodec.serialize(blocks)

        assertEquals(markdown.indexOf("task") + 2, NoteMarkdownCodec.textOffset(blocks, 1, 2))
    }
}
