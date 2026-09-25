package com.colink.android.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colink.android.data.local.db.CoLinkDatabase
import com.colink.android.data.local.db.entity.NoteAttachmentEntity
import com.colink.android.data.local.db.entity.NoteEntity
import com.colink.android.data.local.db.entity.NoteTagEntity
import com.colink.android.domain.model.NoteSyncState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotesScopeManagerTest {
    @Test
    fun accountNotesRemainLocalAfterLogoutAndCanBeClaimedByAnotherAccount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, CoLinkDatabase::class.java).build()
        val manager = NotesScopeManager(
            context = context,
            database = database,
            noteDao = database.noteDao(),
            noteTagDao = database.noteTagDao(),
            noteAttachmentDao = database.noteAttachmentDao(),
            noteSyncKvDao = database.noteSyncKvDao(),
        )
        val sourceScope = noteAccountScope("https://one.example/", "user-a")
        val targetScope = noteAccountScope("https://two.example", "user-b")
        val sourceFile = File(manager.attachmentCacheDirectory(sourceScope), "attachment-a")
        val localFile = File(manager.attachmentCacheDirectory(LOCAL_ACCOUNT_SCOPE), "attachment-a")
        val targetFile = File(manager.attachmentCacheDirectory(targetScope), "attachment-a")

        try {
            database.noteTagDao().upsert(
                sourceScope,
                NoteTagEntity(
                    tagId = "tag-a",
                    name = "Work",
                    nameNormalized = "work",
                    revision = 4,
                    baseRevision = 4,
                    syncState = NoteSyncState.Synced.name,
                    deleted = false,
                    createdAt = 1,
                    updatedAt = 2,
                ),
            )
            database.noteAttachmentDao().upsert(
                sourceScope,
                NoteAttachmentEntity(
                    attachmentId = "attachment-a",
                    kind = "file",
                    fileName = "draft.txt",
                    mediaType = "text/plain",
                    size = 5,
                    sha256 = "digest",
                    syncState = NoteSyncState.Synced.name,
                    deleted = false,
                    createdAt = 1,
                ),
            )
            database.noteDao().upsert(
                sourceScope,
                NoteEntity.newPending(
                    noteId = "note-a",
                    title = "Draft",
                    markdown = "Body",
                    tagIds = listOf("tag-a"),
                    attachmentIds = listOf("attachment-a"),
                    now = 2,
                ).copy(
                    revision = 7,
                    baseRevision = 7,
                    syncState = NoteSyncState.Synced.name,
                ),
            )
            sourceFile.writeText("draft")

            manager.releaseAccount("https://one.example/", "user-a")

            assertTrue(database.noteDao().listAllRows(sourceScope).isEmpty())
            val localNote = database.noteDao().find(LOCAL_ACCOUNT_SCOPE, "note-a")
            assertEquals(0L, localNote?.revision)
            assertEquals(NoteSyncState.Pending.name, localNote?.syncState)
            assertTrue(localFile.isFile)
            assertFalse(sourceFile.exists())

            manager.prepare(targetScope)

            assertTrue(database.noteDao().listAllRows(LOCAL_ACCOUNT_SCOPE).isEmpty())
            val claimedNote = database.noteDao().find(targetScope, "note-a")
            assertEquals("Draft", claimedNote?.title)
            assertEquals(listOf("tag-a"), claimedNote?.let { com.colink.android.data.local.db.entity.decodeIds(it.tagIds) })
            assertEquals(NoteSyncState.Pending.name, claimedNote?.syncState)
            assertTrue(targetFile.isFile)
            assertFalse(localFile.exists())
        } finally {
            sourceFile.delete()
            localFile.delete()
            targetFile.delete()
            database.close()
        }
    }
}
