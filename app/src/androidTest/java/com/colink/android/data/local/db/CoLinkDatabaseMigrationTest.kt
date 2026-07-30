package com.colink.android.data.local.db

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoLinkDatabaseMigrationTest {
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
