package com.colink.android.ui.notes.components

import com.colink.android.ui.notes.model.NoteBlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteBlockEditorPlaceholderTest {
    @Test
    fun singleEmptyParagraph_showsPlaceholder() {
        assertTrue(shouldShowDocumentPlaceholder(listOf(NoteBlock.Paragraph(""))))
    }

    @Test
    fun multipleEmptyParagraphs_hidePlaceholder() {
        assertFalse(
            shouldShowDocumentPlaceholder(
                listOf(NoteBlock.Paragraph(""), NoteBlock.Paragraph("")),
            ),
        )
    }

    @Test
    fun contentOrDifferentBlockType_hidesPlaceholder() {
        assertFalse(shouldShowDocumentPlaceholder(listOf(NoteBlock.Paragraph("Text"))))
        assertFalse(shouldShowDocumentPlaceholder(listOf(NoteBlock.Heading(1, ""))))
        assertFalse(shouldShowDocumentPlaceholder(emptyList()))
    }
}
