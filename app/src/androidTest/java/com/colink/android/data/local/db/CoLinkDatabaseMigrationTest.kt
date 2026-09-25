package com.colink.android.data.local.db

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colink.android.data.local.db.entity.NoteEntity
import com.colink.android.domain.model.NoteSyncState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoLinkDatabaseMigrationTest {
    @Test
    fun notesMigration11To16PreservesRowsAndClearsCursor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-notes-11-13-test.db"
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase

        CoLinkDatabase.MIGRATION_11_12.migrate(db)
        db.execSQL(
            "INSERT INTO notes (noteId, title, markdown, tagIds, attachmentIds, revision, baseRevision, ancestorRevision, syncState, ancestorTitle, ancestorMarkdown, ancestorTagIds, ancestorAttachmentIds, deleted, createdAt, updatedAt) VALUES ('note-a', 'Title', 'Body', '[]', '[]', 1, 1, 1, 'synced', 'Title', 'Body', '[]', '[]', 0, 1, 2)",
        )
        db.execSQL(
            "INSERT INTO note_sync_kv (`key`, value) VALUES ('notes_sync_cursor', 'cursor-a')",
        )

        CoLinkDatabase.MIGRATION_12_13.migrate(db)
        CoLinkDatabase.MIGRATION_13_14.migrate(db)
        CoLinkDatabase.MIGRATION_14_15.migrate(db)
        CoLinkDatabase.MIGRATION_15_16.migrate(db)

        db.query("SELECT accountScope, title FROM notes WHERE noteId = 'note-a'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("__local__", cursor.getString(0))
            assertEquals("Title", cursor.getString(1))
        }
        db.query("PRAGMA index_list(notes)").use { cursor ->
            val names = buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertTrue("index_notes_accountScope_updatedAt_noteId" in names)
            assertFalse("index_notes_updatedAt" in names)
        }
        db.query("SELECT COUNT(*) FROM note_sync_kv WHERE `key` = 'notes_sync_cursor'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'note_active_scope'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        helper.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun notesWithTheSameIdAreIsolatedByAccountScope() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, CoLinkDatabase::class.java).build()
        try {
            val noteA = NoteEntity.newPending("shared-note-id", "Account A", "a", emptyList(), emptyList(), 1)
            val noteB = NoteEntity.newPending("shared-note-id", "Account B", "b", emptyList(), emptyList(), 2)

            database.noteDao().upsert("scope-a", noteA)
            database.noteDao().upsert("scope-b", noteB)

            assertEquals("Account A", database.noteDao().find("scope-a", "shared-note-id")?.title)
            assertEquals("Account B", database.noteDao().find("scope-b", "shared-note-id")?.title)
            assertEquals(1, database.noteDao().listNotes("scope-a", NoteSyncState.PendingDelete.name).size)
            assertEquals(1, database.noteDao().listNotes("scope-b", NoteSyncState.PendingDelete.name).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun migration8To9RetainsCatalogFieldsAndRemovesRuntimeFields() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("migration-8-9-test.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration-8-9-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase

        db.execSQL(
            """
            CREATE TABLE devices (
                deviceId TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                online INTEGER NOT NULL,
                lastSeen TEXT,
                publicKey TEXT NOT NULL,
                publicKeyUpdatedAt INTEGER,
                cloudAvailable INTEGER NOT NULL,
                activeRoute TEXT,
                deviceSources TEXT NOT NULL,
                trustedByLan INTEGER NOT NULL,
                trustedByCloud INTEGER NOT NULL,
                securityState TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO devices VALUES (
                'device-a', 'Desktop', 'windows', 1, '2026-07-29T12:00:00Z',
                'public-key', 1234, 1, 'cloud', 'cloud,trusted_peer_key', 1, 1, 'verified'
            )
            """.trimIndent(),
        )

        CoLinkDatabase.MIGRATION_8_9.migrate(db)

        val columns = buildSet {
            db.query("PRAGMA table_info(devices)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    add(cursor.getString(nameIndex))
                }
            }
        }
        assertFalse("online" in columns)
        assertFalse("cloudAvailable" in columns)
        assertFalse("activeRoute" in columns)
        assertTrue(setOf(
            "deviceId",
            "name",
            "type",
            "lastSeen",
            "publicKey",
            "publicKeyUpdatedAt",
            "deviceSources",
            "trustedByLan",
            "trustedByCloud",
            "securityState",
        ).all(columns::contains))

        db.query("SELECT * FROM devices WHERE deviceId = 'device-a'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Desktop", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertEquals("windows", cursor.getString(cursor.getColumnIndexOrThrow("type")))
            assertEquals("cloud,trusted_peer_key", cursor.getString(cursor.getColumnIndexOrThrow("deviceSources")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("trustedByLan")))
            assertEquals("verified", cursor.getString(cursor.getColumnIndexOrThrow("securityState")))
        }
        helper.close()
        context.deleteDatabase("migration-8-9-test.db")
    }
}
