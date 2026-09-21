package com.ranorac.tjtimetable.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's local store.
 *
 * `exportSchema = true` writes the schema to `app/schemas/` so that migrations can be
 * written against the real column names instead of guessed ones.
 *
 * There is deliberately **no** `fallbackToDestructiveMigration()`. That fallback drops
 * every table on a version bump, which would silently destroy the student's timetable,
 * colour overrides, notes and hand-edited 调休 rows — the very things this app exists to
 * remember. Omitting it means a forgotten migration throws at runtime during development
 * rather than losing data in the field, so **every bump of [version] must ship a real
 * `Migration`** added via `addMigrations(...)`.
 */
@Database(
    entities = [
        TermEntity::class,
        CourseEntity::class,
        SessionEntity::class,
        DayAdjustmentEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TimetableDatabase : RoomDatabase() {

    abstract fun termDao(): TermDao

    abstract fun courseDao(): CourseDao

    abstract fun dayAdjustmentDao(): DayAdjustmentDao

    companion object {
        private const val NAME = "tj_timetable.db"

        /**
         * v1 → v2: `day_adjustments` gets the composite primary key `(termId, epochDay)`.
         *
         * Two terms that overlap in time used to share one set of rows keyed by date alone, so
         * writing 调休 for one semester REPLACEd the other's row for the same calendar date — and
         * the other could never recover it, because a refresh only rewrites rows it can still
         * see.
         *
         * SQLite cannot alter a primary key, so the table is rebuilt. Note this **changes the
         * [TimetableDatabase] version**, which is not optional: `version` is what Room stores in
         * `room_master_table`, and leaving it at 1 while the schema changed makes every existing
         * install fail the identity check on its first query — `Room cannot verify the data
         * integrity... you can simply fix this by increasing the version number` — which happens
         * during the timetable screen's first composition and therefore **kills the app on
         * launch**. A fresh install never notices, because it creates the new schema directly;
         * only an upgrade does. That is exactly how this shipped broken once.
         *
         * The copy is `INSERT OR REPLACE` rather than a plain `INSERT`: the whole point of the
         * change is that a date may legitimately hold one row per term, and a v1 database can
         * only hold one row per date anyway, so nothing is lost — but the statement stays valid
         * even against a database that somehow already has the new shape.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in MIGRATION_1_2_SQL) db.execSQL(statement)
            }
        }

        /**
         * The v1 → v2 rebuild, statement by statement.
         *
         * Exposed so the upgrade test can exercise **the statements that ship** against a
         * database shaped the way the released version left it, rather than a copy of them that
         * could drift. The migration body itself is only a loop over this list.
         */
        val MIGRATION_1_2_SQL: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS `day_adjustments_new` (
                `termId` TEXT NOT NULL,
                `epochDay` INTEGER NOT NULL,
                `kindCode` TEXT NOT NULL,
                `followsWeekday` INTEGER,
                `noClasses` INTEGER NOT NULL,
                `sourceName` TEXT NOT NULL,
                `note` TEXT,
                PRIMARY KEY(`termId`, `epochDay`)
            )
            """.trimIndent(),
            """
            INSERT OR REPLACE INTO `day_adjustments_new`
                (`termId`, `epochDay`, `kindCode`, `followsWeekday`, `noClasses`, `sourceName`, `note`)
            SELECT `termId`, `epochDay`, `kindCode`, `followsWeekday`, `noClasses`, `sourceName`, `note`
            FROM `day_adjustments`
            """.trimIndent(),
            "DROP TABLE `day_adjustments`",
            "ALTER TABLE `day_adjustments_new` RENAME TO `day_adjustments`",
        )

        /** Every migration the app ships. A bump without an entry here crashes on upgrade. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context): TimetableDatabase =
            Room.databaseBuilder(context.applicationContext, TimetableDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
