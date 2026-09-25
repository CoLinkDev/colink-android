package com.colink.android.ui.notes.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkdownVisualTransformationTest {
    private val transformation = InlineMarkdownVisualTransformation(
        accent = Color.Blue,
        codeBackground = Color.LightGray,
    )

    @Test
    fun filter_hidesFormattingAndLinkDestination() {
        val source = "**bold** and *italic* [file](colink-attachment://id)"

        val result = transformation.filter(AnnotatedString(source))

        assertEquals("bold and italic file", result.text.text)
    }

    @Test
    fun offsets_areBoundedAndMonotonic() {
        val source = "**bold** and `code`"
        val result = transformation.filter(AnnotatedString(source))
        var previous = 0

        for (offset in 0..source.length) {
            val transformed = result.offsetMapping.originalToTransformed(offset)
            assertTrue(transformed in 0..result.text.length)
            assertTrue(transformed >= previous)
            previous = transformed
        }
        previous = 0
        for (offset in 0..result.text.length) {
            val original = result.offsetMapping.transformedToOriginal(offset)
            assertTrue(original in 0..source.length)
            assertTrue(original >= previous)
            previous = original
        }
    }

    @Test
    fun emptyMarkerPair_keepsCaretBetweenMarkers() {
        val result = transformation.filter(AnnotatedString("****"))

        assertEquals("", result.text.text)
        assertEquals(2, result.offsetMapping.transformedToOriginal(0))
    }
}
