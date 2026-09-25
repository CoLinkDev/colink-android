package com.colink.android.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.colink.android.data.local.db.dao.DeviceDao
import com.colink.android.data.local.db.dao.DiagnosticLogDao
import com.colink.android.data.local.db.dao.FileTransferDao
import com.colink.android.data.local.db.dao.MessageDao
import com.colink.android.data.local.db.dao.NoteAttachmentDao
import com.colink.android.data.local.db.dao.NoteDao
import com.colink.android.data.local.db.dao.NoteSyncKvDao
import com.colink.android.data.local.db.dao.NoteTagDao
import com.colink.android.data.local.db.dao.TrustedPeerKeyDao
import com.colink.android.data.local.db.entity.DeviceEntity
import com.colink.android.data.local.db.entity.DiagnosticLogEntity
import com.colink.android.data.local.db.entity.FileTransferEntity
import com.colink.android.data.local.db.entity.MessageEntity
import com.colink.android.data.local.db.entity.NoteAttachmentEntity
import com.colink.android.data.local.db.entity.NoteEntity
import com.colink.android.data.local.db.entity.NoteSyncKvEntity
import com.colink.android.data.local.db.entity.NoteTagEntity
import com.colink.android.data.local.db.entity.TrustedPeerKeyEntity

@Database(
    entities = [
        DeviceEntity::class,
        DiagnosticLogEntity::class,
        MessageEntity::class,
        FileTransferEntity::class,
        TrustedPeerKeyEntity::class,
        NoteEntity::class,
        NoteTagEntity::class,
        NoteAttachmentEntity::class,
        NoteSyncKvEntity::class,
    ],
    version = 16,
    exportSchema = false,
)
abstract class CoLinkDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    abstract fun diagnosticLogDao(): DiagnosticLogDao

    abstract fun messageDao(): MessageDao

    abstract fun fileTransferDao(): FileTransferDao

    abstract fun trustedPeerKeyDao(): TrustedPeerKeyDao

    abstract fun noteDao(): NoteDao

    abstract fun noteTagDao(): NoteTagDao

    abstract fun noteAttachmentDao(): NoteAttachmentDao

    abstract fun noteSyncKvDao(): NoteSyncKvDao

    companion object {
        val MIGRATION_15_16: Migration =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("UPDATE notes SET accountScope = '__local__' WHERE accountScope = '__legacy__'")
                    db.execSQL("UPDATE note_tags SET accountScope = '__local__' WHERE accountScope = '__legacy__'")
                    db.execSQL("UPDATE note_attachments SET accountScope = '__local__' WHERE accountScope = '__legacy__'")
                    db.execSQL("DELETE FROM note_sync_kv WHERE accountScope IN ('__legacy__', '__local__')")
                }
            }

        val MIGRATION_14_15: Migration =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Rebuild the snapshot once so existing installations gain
                    // attachment metadata and authoritative cloud timestamps.
                    db.execSQL("DELETE FROM note_sync_kv WHERE `key` = 'notes_sync_cursor'")
                }
            }

        val MIGRATION_11_12: Migration =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS notes (
                            noteId TEXT NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            markdown TEXT NOT NULL,
                            tagIds TEXT NOT NULL,
                            attachmentIds TEXT NOT NULL,
                            revision INTEGER NOT NULL,
                            baseRevision INTEGER NOT NULL,
                            ancestorRevision INTEGER NOT NULL,
                            syncState TEXT NOT NULL,
                            conflictKind TEXT,
                            conflictTitle TEXT,
                            conflictMarkdown TEXT,
                            conflictTagIds TEXT,
                            conflictAttachmentIds TEXT,
                            conflictRevision INTEGER,
                            ancestorTitle TEXT NOT NULL,
                            ancestorMarkdown TEXT NOT NULL,
                            ancestorTagIds TEXT NOT NULL,
                            ancestorAttachmentIds TEXT NOT NULL,
                            deleted INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes (updatedAt DESC, noteId ASC)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS note_tags (
                            tagId TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            nameNormalized TEXT NOT NULL,
                            revision INTEGER NOT NULL,
                            baseRevision INTEGER NOT NULL,
                            syncState TEXT NOT NULL,
                            deleted INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_note_tags_nameNormalized ON note_tags (nameNormalized) WHERE deleted = 0",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS note_attachments (
                            attachmentId TEXT NOT NULL PRIMARY KEY,
                            kind TEXT NOT NULL,
                            fileName TEXT NOT NULL,
                            mediaType TEXT NOT NULL,
                            size INTEGER NOT NULL,
                            sha256 TEXT NOT NULL,
                            syncState TEXT NOT NULL,
                            deleted INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS note_sync_kv (
                            `key` TEXT NOT NULL PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
            }
        val MIGRATION_12_13: Migration =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP INDEX IF EXISTS index_notes_updatedAt")
                    db.execSQL("DROP INDEX IF EXISTS index_note_tags_nameNormalized")
                    db.execSQL("ALTER TABLE notes RENAME TO notes_v12")
                    db.execSQL("ALTER TABLE note_tags RENAME TO note_tags_v12")
                    db.execSQL("ALTER TABLE note_attachments RENAME TO note_attachments_v12")
                    db.execSQL("ALTER TABLE note_sync_kv RENAME TO note_sync_kv_v12")
                    db.execSQL(
                        """
                        CREATE TABLE notes (
                            accountScope TEXT NOT NULL, noteId TEXT NOT NULL, title TEXT NOT NULL,
                            markdown TEXT NOT NULL, tagIds TEXT NOT NULL, attachmentIds TEXT NOT NULL,
                            revision INTEGER NOT NULL, baseRevision INTEGER NOT NULL,
                            ancestorRevision INTEGER NOT NULL, syncState TEXT NOT NULL,
                            conflictKind TEXT, conflictTitle TEXT, conflictMarkdown TEXT,
                            conflictTagIds TEXT, conflictAttachmentIds TEXT, conflictRevision INTEGER,
                            ancestorTitle TEXT NOT NULL, ancestorMarkdown TEXT NOT NULL,
                            ancestorTagIds TEXT NOT NULL, ancestorAttachmentIds TEXT NOT NULL,
                            deleted INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                            PRIMARY KEY(accountScope, noteId)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "INSERT INTO notes SELECT '__legacy__', noteId, title, markdown, tagIds, attachmentIds, revision, baseRevision, ancestorRevision, syncState, conflictKind, conflictTitle, conflictMarkdown, conflictTagIds, conflictAttachmentIds, conflictRevision, ancestorTitle, ancestorMarkdown, ancestorTagIds, ancestorAttachmentIds, deleted, createdAt, updatedAt FROM notes_v12",
                    )
                    db.execSQL("DROP TABLE notes_v12")
                    db.execSQL("CREATE INDEX index_notes_accountScope_updatedAt_noteId ON notes (accountScope ASC, updatedAt DESC, noteId ASC)")
                    db.execSQL(
                        "CREATE TABLE note_tags (accountScope TEXT NOT NULL, tagId TEXT NOT NULL, name TEXT NOT NULL, nameNormalized TEXT NOT NULL, revision INTEGER NOT NULL, baseRevision INTEGER NOT NULL, syncState TEXT NOT NULL, deleted INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(accountScope, tagId))",
                    )
                    db.execSQL("INSERT INTO note_tags SELECT '__legacy__', tagId, name, nameNormalized, revision, baseRevision, syncState, deleted, createdAt, updatedAt FROM note_tags_v12")
                    db.execSQL("DROP TABLE note_tags_v12")
                    db.execSQL("CREATE INDEX index_note_tags_accountScope_nameNormalized ON note_tags (accountScope, nameNormalized)")
                    db.execSQL(
                        "CREATE TABLE note_attachments (accountScope TEXT NOT NULL, attachmentId TEXT NOT NULL, kind TEXT NOT NULL, fileName TEXT NOT NULL, mediaType TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, syncState TEXT NOT NULL, deleted INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(accountScope, attachmentId))",
                    )
                    db.execSQL("INSERT INTO note_attachments SELECT '__legacy__', attachmentId, kind, fileName, mediaType, size, sha256, syncState, deleted, createdAt FROM note_attachments_v12")
                    db.execSQL("DROP TABLE note_attachments_v12")
                    db.execSQL("CREATE TABLE note_sync_kv (accountScope TEXT NOT NULL, `key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(accountScope, `key`))")
                    db.execSQL("INSERT INTO note_sync_kv SELECT '__legacy__', `key`, value FROM note_sync_kv_v12")
                    db.execSQL("DROP TABLE note_sync_kv_v12")
                    db.execSQL("CREATE TABLE note_active_scope (id INTEGER NOT NULL PRIMARY KEY, scope TEXT NOT NULL)")
                }
            }
        val MIGRATION_13_14: Migration =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS note_active_scope")
                }
            }
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE devices ADD COLUMN publicKeyUpdatedAt INTEGER")
                    db.execSQL("ALTER TABLE devices ADD COLUMN cloudAvailable INTEGER NOT NULL DEFAULT 0")
                }
            }

        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS file_transfers (
                            sessionId TEXT NOT NULL PRIMARY KEY,
                            deviceId TEXT NOT NULL,
                            direction TEXT NOT NULL,
                            fileName TEXT NOT NULL,
                            fileSize INTEGER NOT NULL,
                            transferredBytes INTEGER NOT NULL,
                            totalChunks INTEGER NOT NULL,
                            status TEXT NOT NULL,
                            checksum TEXT NOT NULL,
                            route TEXT NOT NULL,
                            localUri TEXT,
                            error TEXT,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
            }

        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS trusted_peer_keys (
                            deviceId TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            publicKey TEXT NOT NULL,
                            keyUpdatedAt INTEGER NOT NULL,
                            trustedAt INTEGER
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO trusted_peer_keys (
                            deviceId,
                            name,
                            publicKey,
                            keyUpdatedAt,
                            trustedAt
                        )
                        SELECT
                            deviceId,
                            name,
                            publicKey,
                            COALESCE(publicKeyUpdatedAt, 0),
                            NULL
                        FROM devices
                        WHERE publicKey != ''
                        """.trimIndent(),
                    )
                }
            }

        val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE devices ADD COLUMN deviceSources TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE devices ADD COLUMN securityState TEXT NOT NULL DEFAULT 'unverified'")
                }
            }

        val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE trusted_peer_keys_new (
                            device_id TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            public_key TEXT NOT NULL,
                            key_updated_at INTEGER NOT NULL,
                            trusted_by_lan INTEGER NOT NULL DEFAULT 0,
                            trusted_by_cloud INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO trusted_peer_keys_new (
                            device_id,
                            name,
                            public_key,
                            key_updated_at,
                            trusted_by_lan,
                            trusted_by_cloud
                        )
                        SELECT
                            deviceId,
                            name,
                            publicKey,
                            keyUpdatedAt,
                            CASE WHEN trustedAt IS NOT NULL THEN 1 ELSE 0 END,
                            0
                        FROM trusted_peer_keys
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE trusted_peer_keys")
                    db.execSQL("ALTER TABLE trusted_peer_keys_new RENAME TO trusted_peer_keys")
                }
            }

        val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE devices ADD COLUMN lanState TEXT NOT NULL DEFAULT 'unavailable'")
                    db.execSQL("ALTER TABLE devices ADD COLUMN trustedByLan INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE devices ADD COLUMN trustedByCloud INTEGER NOT NULL DEFAULT 0")
                }
            }

        val MIGRATION_7_8: Migration =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE devices_new (
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
                        INSERT INTO devices_new (
                            deviceId, name, type, online, lastSeen, publicKey,
                            publicKeyUpdatedAt, cloudAvailable, activeRoute, deviceSources,
                            trustedByLan, trustedByCloud, securityState
                        )
                        SELECT
                            deviceId, name, type, online, lastSeen, publicKey,
                            publicKeyUpdatedAt, cloudAvailable, activeRoute, deviceSources,
                            trustedByLan, trustedByCloud, securityState
                        FROM devices
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE devices")
                    db.execSQL("ALTER TABLE devices_new RENAME TO devices")
                }
            }

        val MIGRATION_8_9: Migration =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE devices_new (
                            deviceId TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            type TEXT NOT NULL,
                            lastSeen TEXT,
                            publicKey TEXT NOT NULL,
                            publicKeyUpdatedAt INTEGER,
                            deviceSources TEXT NOT NULL,
                            trustedByLan INTEGER NOT NULL,
                            trustedByCloud INTEGER NOT NULL,
                            securityState TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO devices_new (
                            deviceId, name, type, lastSeen, publicKey,
                            publicKeyUpdatedAt, deviceSources, trustedByLan,
                            trustedByCloud, securityState
                        )
                        SELECT
                            deviceId, name, type, lastSeen, publicKey,
                            publicKeyUpdatedAt, deviceSources, trustedByLan,
                            trustedByCloud, securityState
                        FROM devices
                        """.trimIndent(),
                    )
                    db.execSQL("DROP TABLE devices")
                    db.execSQL("ALTER TABLE devices_new RENAME TO devices")
                }
            }

        val MIGRATION_9_10: Migration =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS diagnostic_logs (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            level TEXT NOT NULL,
                            component TEXT NOT NULL,
                            message TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_diagnostic_logs_createdAt ON diagnostic_logs (createdAt)",
                    )
                }
            }

        val MIGRATION_10_11: Migration =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE messages ADD COLUMN deliveryStatus TEXT NOT NULL DEFAULT 'Sent'",
                    )
                }
            }
    }
}
