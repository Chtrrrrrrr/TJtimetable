package com.ranorac.tjtimetable.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter

/**
 * Room schema for the timetable.
 *
 * Courses are scoped to a [TermEntity] via [CourseEntity.termId] so that viewing
 * a past semester, or importing the next one, never mixes two semesters' data.
 */
@Entity(tableName = "terms")
data class TermEntity(
    /** 学期编号 (`calendarId`) from the 教务系统; the natural primary key. */
    @PrimaryKey val calendarId: String,
    val name: String,
    val year: Int,
    val term: Int,
    /** Epoch day of the first day of teaching week 1. */
    val firstWeekStartEpochDay: Long,
    val endEpochDay: Long,
    val totalWeeks: Int,
    /** `java.time.DayOfWeek.value`. */
    val weekStartDay: Int,
    val isCurrent: Boolean,
    /** Current teaching week as last reported by the API, if known. */
    val currentWeek: Int? = null,
)

@Entity(
    tableName = "courses",
    indices = [Index("termId"), Index("teachingClassId"), Index("courseCode")],
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: String,
    val name: String,
    val courseCode: String? = null,
    val teachingClassId: Long? = null,
    val classCode: String? = null,
    val className: String? = null,
    val teacher: String? = null,
    val credits: Double? = null,
    val campus: String? = null,
    val assessmentMode: String? = null,
    val teachingWay: String? = null,
    val department: String? = null,
    /** Student override; null means "derive from the name". */
    val colorIndex: Int? = null,
    val note: String? = null,
    val hidden: Boolean = false,
    val updatedAt: Long = 0,
) {
    /**
     * Identity used to carry student customisations across a re-import.
     * 教学班 id is preferred; a course with no id falls back to its name so that
     * manual courses keep their colour too.
     */
    val customizationKey: String
        get() = teachingClassId?.toString() ?: courseCode ?: name
}

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("courseId")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    /** `java.time.DayOfWeek.value`, 1 = Monday. */
    val dayOfWeek: Int,
    val startUnit: Int,
    val endUnit: Int,
    /** [com.ranorac.tjtimetable.domain.WeekPattern] bitmask. */
    val weekBits: Long,
    /** Verbatim week string from the 教务系统, for diagnosing bad imports. */
    val rawWeeks: String? = null,
    val room: String? = null,
    val building: String? = null,
    val teacher: String? = null,
    val campus: String? = null,
)

/**
 * Resolved 调休串休 state for one semester.
 *
 * Every source is persisted, not just hand edits, so that 调休信息 survives a
 * restart and works offline — re-fetching the school calendar on every launch
 * would make the timetable wrong whenever the network is.
 *
 * The three sources are still distinguished by [sourceName], because they have
 * different lifetimes: on refresh the API and inferred rows are replaced, while
 * MANUAL rows are the student's own edits and are never overwritten.
 *
 * **The key is `(termId, epochDay)`, not `epochDay` alone.** The rows are written with
 * `OnConflictStrategy.REPLACE` and read with a `termId` filter, so an `epochDay`-only key
 * meant a write for one semester deleted another's row for the same calendar date — and the
 * other semester could never recover it, because a refresh only rewrites rows it can still
 * see. Two terms overlapping in time is not exotic: the open-platform import and a captured
 * 教务 response use different calendar ids, an `.ics` falls back to `"ics"`, and a summer
 * term overlaps the autumn one.
 */
@Entity(
    tableName = "day_adjustments",
    primaryKeys = ["termId", "epochDay"],
)
data class DayAdjustmentEntity(
    val termId: String,
    /** `LocalDate.toEpochDay()`. */
    val epochDay: Long,
    /** [com.ranorac.tjtimetable.domain.CalendarDayKind.apiCode], or "" for unknown. */
    val kindCode: String,
    /** `DayOfWeek.value` whose timetable runs, or null for "its own weekday". */
    val followsWeekday: Int? = null,
    val noClasses: Boolean = false,
    /** [com.ranorac.tjtimetable.domain.AdjustmentSource] name. */
    val sourceName: String = "MANUAL",
    val note: String? = null,
)

/** A course together with its weekly meeting slots. */
data class CourseWithSessions(
    @Embedded val course: CourseEntity,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val sessions: List<SessionEntity>,
)

/**
 * Room type converters.
 *
 * The domain layer's `WeekPattern` is a `@JvmInline value class` over a `Long`,
 * but Room cannot persist value classes directly, so the bitmask crosses the
 * boundary as a plain `Long` in [SessionEntity.weekBits] and is rebuilt by the
 * mappers.
 */
class Converters {
    @TypeConverter
    fun dayOfWeekToInt(value: java.time.DayOfWeek?): Int? = value?.value

    @TypeConverter
    fun intToDayOfWeek(value: Int?): java.time.DayOfWeek? =
        value?.let { java.time.DayOfWeek.of(it) }
}
