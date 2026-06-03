package com.colink.android.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.colink.android.data.local.db.dao.DeviceDao
import com.colink.android.data.local.db.dao.FileTransferDao
import com.colink.android.data.local.db.dao.MessageDao
import com.colink.android.data.local.db.dao.TrustedPeerKeyDao
import com.colink.android.data.local.db.entity.DeviceEntity
import com.colink.android.data.local.db.entity.FileTransferEntity
import com.colink.android.data.local.db.entity.MessageEntity
import com.colink.android.data.local.db.entity.TrustedPeerKeyEntity

@Database(
    entities = [
        DeviceEntity::class,
        MessageEntity::class,
        FileTransferEntity::class,
        TrustedPeerKeyEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class CoLinkDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    abstract fun messageDao(): MessageDao

    abstract fun fileTransferDao(): FileTransferDao

    abstract fun trustedPeerKeyDao(): TrustedPeerKeyDao

    companion object {
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
    }
}
