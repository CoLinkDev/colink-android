package com.colink.android.ui.notes.model

sealed interface NoteBlock {
    data class Heading(val level: Int, val text: String) : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
    data class Todo(val checked: Boolean, val text: String) : NoteBlock
    data class Bullet(val text: String) : NoteBlock
    data class Ordered(val number: Int, val text: String) : NoteBlock
    data class Quote(val text: String) : NoteBlock
    data class Code(val language: String, val text: String) : NoteBlock
    data class Image(val alt: String, val attachmentId: String) : NoteBlock
}
