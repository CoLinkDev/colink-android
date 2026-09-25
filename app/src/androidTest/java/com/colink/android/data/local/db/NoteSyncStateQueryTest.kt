package com.colink.android.data.local.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colink.android.data.local.db.entity.NoteEntity
import com.colink.android.data.local.db.entity.NoteTagEntity
import com.colink.android.domain.model.NoteSyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteSyncStateQueryTest {
    @Test
    fun persistedEnumStatesDrivePendingAndVisibleQueries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, CoLinkDatabase::class.java).build()
        val scope = "scope"

        try {
            database.noteDao().upsert(
                scope,
                note("synced", NoteSyncState.Synced, updatedAt = 1),
            )
            database.noteDao().upsert(
                scope,
                note("pending", NoteSyncState.Pending, updatedAt = 2),
            )
            database.noteDao().upsert(
                scope,
                note("pending-delete", NoteSyncState.PendingDelete, updatedAt = 3),
            )

            val pending = database.noteDao().listPending(
                scope = scope,
                pendingState = NoteSyncState.Pending.name,
                pendingDeleteState = NoteSyncState.PendingDelete.name,
            )
            assertEquals(listOf("pending", "pending-delete"), pending.map { it.noteId })

            val visible = database.noteDao().observeNotes(scope, NoteSyncState.PendingDelete.name).first()
            assertEquals(listOf("pending", "synced"), visible.map { it.noteId })
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingDeleteTagsAreHiddenFromVisibleQueries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, CoLinkDatabase::class.java).build()
        val scope = "scope"

        try {
            database.noteTagDao().upsert(scope, tag("synced", NoteSyncState.Synced, createdAt = 1))
            database.noteTagDao().upsert(scope, tag("pending-delete", NoteSyncState.PendingDelete, createdAt = 2))

            val visible = database.noteTagDao().observeTags(scope, NoteSyncState.PendingDelete.name).first()
            assertEquals(listOf("synced"), visible.map { it.tagId })
        } finally {
            database.close()
        }
    }

    private fun note(noteId: String, state: NoteSyncState, updatedAt: Long): NoteEntity =
        NoteEntity.newPending(
            noteId = noteId,
            title = noteId,
            markdown = "",
            tagIds = emptyList(),
            attachmentIds = emptyList(),
            now = updatedAt,
        ).copy(syncState = state.name)

    private fun tag(tagId: String, state: NoteSyncState, createdAt: Long): NoteTagEntity =
        NoteTagEntity(
            tagId = tagId,
            name = tagId,
            nameNormalized = tagId,
            revision = 1,
            baseRevision = 1,
            syncState = state.name,
            deleted = false,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
}
