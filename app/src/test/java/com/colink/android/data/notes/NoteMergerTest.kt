package com.colink.android.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMergerTest {

    @Test
    fun `markdown merge applies disjoint edits from both sides`() {
        val ancestor = "line1\nline2\nline3\nline4"
        val local = "local1\nline2\nline3\nline4"
        val cloud = "line1\nline2\nline3\ncloud4"

        val merged = NoteMerger.mergeMarkdown(ancestor, local, cloud)

        assertEquals("local1\nline2\nline3\ncloud4", merged)
    }

    @Test
    fun `markdown merge collapses identical edits`() {
        assertEquals("a\nb!", NoteMerger.mergeMarkdown("a\nb", "a\nb!", "a\nb!"))
    }

    @Test
    fun `markdown merge conflicts on overlapping edits`() {
        assertNull(NoteMerger.mergeMarkdown("a\nb\nc", "a\nlocal\nc", "a\ncloud\nc"))
    }

    @Test
    fun `markdown merge conflicts on different insertions at the same position`() {
        assertNull(NoteMerger.mergeMarkdown("a", "x\na", "y\na"))
    }

    @Test
    fun `markdown merge prefers the changed side`() {
        assertEquals("b", NoteMerger.mergeMarkdown("a", "a", "b"))
        assertEquals("b", NoteMerger.mergeMarkdown("a", "b", "a"))
    }

    @Test
    fun `markdown merge keeps unchanged side when other side reverts`() {
        // local reverted to ancestor, cloud changed -> cloud wins.
        assertEquals("cloud", NoteMerger.mergeMarkdown("a", "a", "cloud"))
    }

    @Test
    fun `field merge resolves single-sided changes`() {
        assertTrue(NoteMerger.mergeField("old", "old", "new").isResolved)
        assertTrue(NoteMerger.mergeField("old", "new", "old").isResolved)
        assertTrue(NoteMerger.mergeField("old", "same", "same").isResolved)
        assertFalse(NoteMerger.mergeField("old", "local", "cloud").isResolved)
    }

    @Test
    fun `set merge honors removals and additions`() {
        val ancestor = listOf("a", "b", "c")
        val local = listOf("a", "b", "d")
        val cloud = listOf("a", "c")

        val merged = NoteMerger.mergeSet(ancestor, local, cloud)

        assertEquals(listOf("a", "d"), merged)
    }

    @Test
    fun `set merge keeps additions from either side`() {
        assertEquals(
            listOf("a", "x", "y"),
            NoteMerger.mergeSet(listOf("a"), listOf("a", "x"), listOf("a", "y")),
        )
    }

    @Test
    fun `note merge combines all fields`() {
        val merge = NoteMerger.mergeNote(
            ancestorTitle = "Title",
            ancestorMarkdown = "first\nsecond",
            ancestorTagIds = listOf("t1"),
            ancestorAttachmentIds = listOf("a1"),
            localTitle = "Title (local)",
            localMarkdown = "local first\nsecond",
            localTagIds = listOf("t1", "t2"),
            localAttachmentIds = listOf("a1"),
            cloudTitle = "Title (cloud)",
            cloudMarkdown = "first\ncloud second",
            cloudTagIds = listOf("t1"),
            cloudAttachmentIds = listOf("a1", "a2"),
        )

        // Title changed differently on both sides -> conflict.
        assertFalse(merge.title.isResolved)
        // Disjoint markdown edits -> clean merge.
        assertEquals("local first\ncloud second", merge.markdown.resolved)
        // Tags: local added t2 -> kept.
        assertEquals(listOf("t1", "t2"), merge.mergedTagIds)
        // Attachments: cloud added a2 -> kept.
        assertEquals(listOf("a1", "a2"), merge.mergedAttachmentIds)
    }

    @Test
    fun `oversized markdown is never auto-merged`() {
        val big = (1..5000).joinToString("\n") { "line $it" }
        val local = big + "\nlocal tail"
        val cloud = big + "\ncloud tail"

        assertNull(NoteMerger.mergeMarkdown(big, local, cloud))
    }
}
