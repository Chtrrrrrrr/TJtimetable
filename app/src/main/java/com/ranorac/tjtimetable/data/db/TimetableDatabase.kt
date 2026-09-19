package com.ranorac.tjtimetable.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TimetableDatabase : RoomDatabase() {

    abstract fun termDao(): TermDao

    abstract fun courseDao(): CourseDao

    abstract fun dayAdjustmentDao(): DayAdjustmentDao

    companion object {
        private const val NAME = "tj_timetable.db"

        fun build(context: Context): TimetableDatabase =
            Room.databaseBuilder(context.applicationContext, TimetableDatabase::class.java, NAME)
                .build()
    }
}
