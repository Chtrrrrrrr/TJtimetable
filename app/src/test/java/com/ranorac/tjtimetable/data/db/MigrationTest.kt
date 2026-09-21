package com.ranorac.tjtimetable.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins the upgrade path from the **released** schema to the current one.
 *
 * This exists because that path was broken in the most expensive way possible. `day_adjustments`
 * went from `PRIMARY KEY(epochDay)` to `PRIMARY KEY(termId, epochDay)` while
 * `TimetableDatabase.version` stayed at **1**. Room records the schema identity in
 * `room_master_table` and re-verifies it on every open, so every installed copy of the previous
 * release failed with
 *
 *     Room cannot verify the data integrity ... you can simply fix this by increasing the version number
 *
 * on the timetable screen's first query — the app **exited immediately on launch** for existing
 * users, while a fresh install was perfectly fine. A clean-database startup test cannot see that;
 * only an upgrade test can.
 *
 * What is pinned here:
 *  1. a v1→v2 migration exists at all (the version alone is not enough), and
 *  2. **the statements that ship** turn the released table shape into the one the entities
 *     describe, without losing the student's own 调休 rows, and
 *  3. the migrated file carries Room's v2 identity, so the next open is a plain open — not
 *     another identity failure.
 *
 * Step 3 is what makes this a real guard rather than a shape check: the identity hash is derived
 * from the full schema, so if the entities change again without a matching migration this fails
 * instead of shipping.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun dbFile(name: String) = context.getDatabasePath(name)

    /** The released shape of the one table whose key changed. */
    private fun seedReleasedDayAdjustments(name: String, seed: (SQLiteDatabase) -> Unit) {
        val path = dbFile(name)
        path.parentFile?.mkdirs()
        path.delete()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `day_adjustments` (
                    `epochDay` INTEGER NOT NULL,
                    `termId` TEXT NOT NULL,
                    `kindCode` TEXT NOT NULL,
                    `followsWeekday` INTEGER,
                    `noClasses` INTEGER NOT NULL,
                    `sourceName` TEXT NOT NULL,
                    `note` TEXT,
                    PRIMARY KEY(`epochDay`)
                )
                """.trimIndent(),
            )
            seed(db)
        }
    }

    /** Runs the statements that actually ship, in order. */
    private fun migrate(name: String) {
        SQLiteDatabase.openDatabase(
            dbFile(name).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.beginTransaction()
            try {
                for (statement in TimetableDatabase.MIGRATION_1_2_SQL) db.execSQL(statement)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun readIdentityHash(name: String): String? =
        SQLiteDatabase.openDatabase(
            dbFile(name).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }

    @Test
    fun `the schema version moved with the schema`() {
        // The bug in one line: the schema changed and the version did not. Room needs a version
        // bump *plus* a registered migration to run an upgrade instead of failing identity.
        assertTrue(
            "a v1→v2 migration must be registered",
            TimetableDatabase.MIGRATIONS.any { it.startVersion == 1 && it.endVersion == 2 },
        )
    }

    @Test
    fun `the migration keeps every 调休 row and rebuilds the key`() = runBlocking {
        val name = "migration-keeps-rows.db"
        seedReleasedDayAdjustments(name) { db ->
            db.execSQL(
                """
                INSERT INTO day_adjustments
                    (epochDay, termId, kindCode, followsWeekday, noClasses, sourceName, note)
                VALUES (20500, '122', '1', NULL, 1, 'API', NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO day_adjustments
                    (epochDay, termId, kindCode, followsWeekday, noClasses, sourceName, note)
                VALUES (20501, '122', '2', 3, 0, 'MANUAL', '手动设为按周三课表')
                """.trimIndent(),
            )
        }
        migrate(name)

        val db = Room.databaseBuilder(context, TimetableDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = db.dayAdjustmentDao().forTerm("122")
            assertEquals("both rows must survive the rebuild", 2, rows.size)

            val manual = rows.single { it.sourceName == "MANUAL" }
            assertEquals(3, manual.followsWeekday)
            assertEquals(false, manual.noClasses)
            // The student's own edit is the one thing a migration must never lose.
            assertEquals("手动设为按周三课表", manual.note)

            assertTrue("the API row's noClasses flag must survive", rows.single { it.sourceName == "API" }.noClasses)
        } finally {
            db.close()
        }

        // Room wrote its own v2 identity when it opened the file, which is what makes the next
        // launch a plain open. A migration that produced the wrong table shape would have thrown
        // during that open instead of getting here.
        assertEquals(V2_IDENTITY_HASH, readIdentityHash(name))
    }

    @Test
    fun `the migrated table accepts the same date in two terms`() = runBlocking {
        // The reason for the composite key: with `epochDay` alone the second term's row replaced
        // the first's, so one semester silently took the other's 调休 arrangement.
        val name = "migration-two-terms.db"
        seedReleasedDayAdjustments(name) { db ->
            db.execSQL(
                """
                INSERT INTO day_adjustments
                    (epochDay, termId, kindCode, followsWeekday, noClasses, sourceName, note)
                VALUES (20500, '122', '1', NULL, 1, 'API', NULL)
                """.trimIndent(),
            )
        }
        migrate(name)

        val db = Room.databaseBuilder(context, TimetableDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            db.dayAdjustmentDao().upsert(
                DayAdjustmentEntity(
                    termId = "999",
                    epochDay = 20500,
                    kindCode = "2",
                    noClasses = false,
                    sourceName = "API",
                ),
            )
            assertEquals("the first term keeps its row", 1, db.dayAdjustmentDao().forTerm("122").size)
            assertEquals("the second term gets its own", 1, db.dayAdjustmentDao().forTerm("999").size)
        } finally {
            db.close()
        }
    }
}

/**
 * `identityHash` from the exported `2.json`.
 *
 * Room derives it from the whole schema, so this is the fingerprint of "what the entities say
 * today". Pinning it means an entity change without a new migration fails this test rather than
 * bricking the next upgrade.
 */
private const val V2_IDENTITY_HASH = "ef58e1ce071f56c55b0143298bb4e008"
