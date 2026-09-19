package com.ranorac.tjtimetable.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * A course, as opposed to a single meeting of it.
 *
 * 同济's API returns one row per meeting, so a course with two weekly slots and
 * three rooms arrives as several rows. They are grouped into one [Course] plus
 * several [CourseSession]s, keyed on [teachingClassId] — the 教学班 id, which is
 * the only identifier the API gives that is stable across semesters and unique
 * per class.
 */
data class Course(
    /** Local primary key. */
    val id: Long = 0,
    val name: String,
    /** 课程代码, e.g. `101019`. */
    val courseCode: String? = null,
    /** 教学班 id from the API; the grouping key. */
    val teachingClassId: Long? = null,
    /** 教学班编号, e.g. `10016502`. */
    val classCode: String? = null,
    /** 班级, e.g. `01班`. */
    val className: String? = null,
    val teacher: String? = null,
    val credits: Double? = null,
    val campus: String? = null,
    /** 考核方式, e.g. `考查`. */
    val assessmentMode: String? = null,
    /** 授课方式, e.g. `线下授课`. */
    val teachingWay: String? = null,
    /** 开课学院. */
    val department: String? = null,
    /** Explicit palette index, or null to derive one from the name. */
    val colorIndex: Int? = null,
    val note: String? = null,
    /** Hidden courses stay in the database but are not drawn. */
    val hidden: Boolean = false,
) {
    /** The colour index to actually draw with. */
    val effectiveColorIndex: Int
        get() = colorIndex ?: courseHueIndex(name, teachingClassId?.toString() ?: classCode)
}

/**
 * One weekly meeting slot of a [Course]: a weekday, a 节 range, and the set of
 * weeks it runs in.
 *
 * The [weeks] field is where 单双周 lives — see [WeekPattern].
 */
data class CourseSession(
    val id: Long = 0,
    val courseId: Long,
    val dayOfWeek: DayOfWeek,
    val startUnit: Int,
    val endUnit: Int,
    val weeks: WeekPattern,
    /**
     * The exact week string the 教务系统 returned, kept verbatim so an import
     * problem can be diagnosed without re-fetching.
     */
    val rawWeeks: String? = null,
    val room: String? = null,
    val building: String? = null,
    val teacher: String? = null,
    val campus: String? = null,
) {
    /** Number of 节 spanned, used for grid row height. */
    val unitSpan: Int get() = (endUnit - startUnit + 1).coerceAtLeast(1)

    fun runsIn(week: Int): Boolean = weeks.contains(week)
}

/**
 * A [CourseSession] resolved onto one concrete date.
 *
 * Produced by [TimetableResolver] and consumed by the grid, the widget and the
 * calendar writer, so all three agree on what happens when.
 */
data class ClassOccurrence(
    val course: Course,
    val session: CourseSession,
    val date: LocalDate,
    val week: Int,
    val startUnit: Int = session.startUnit,
    val endUnit: Int = session.endUnit,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val room: String? = session.room,
    /**
     * Set when this occurrence exists only because of a 调休补课日 — i.e. the
     * date runs another weekday's schedule. Drives the "补课" chip.
     */
    val isMakeup: Boolean = false,
    /**
     * False when the session does not actually run in [week].
     *
     * Such occurrences are produced by [TimetableResolver.weekGrid] purely so the
     * grid can ghost a 单双周 course in its off weeks — seeing where a course
     * *would* be is more useful than it silently vanishing.
     */
    val isActive: Boolean = true,
) {
    val unitSpan: Int get() = (endUnit - startUnit + 1).coerceAtLeast(1)

    /** e.g. `5-6节`. */
    val unitLabel: String get() = if (startUnit == endUnit) "$startUnit 节" else "$startUnit-$endUnit 节"

    /** e.g. `13:30–15:05`, or null when the 作息表 has no such 节. */
    val timeLabel: String?
        get() = if (startTime != null && endTime != null) "$startTime–$endTime" else null
}

/**
 * Stable palette index for a course.
 *
 * A small FNV-style hash over the course name (plus a discriminator such as the
 * 教学班 id) keeps a course's colour fixed across sessions, devices and
 * re-imports without having to persist an assignment for every course.
 *
 * Modulo 10 matches the size of the UI palette; the two must stay in step.
 */
fun courseHueIndex(name: String, discriminator: String? = null): Int {
    val key = if (discriminator.isNullOrEmpty()) name else "$name\u0000$discriminator"
    var hash = 2166136261L
    for (ch in key) {
        hash = hash xor ch.code.toLong()
        hash = (hash * 16777619L) and 0xFFFFFFFFL
    }
    return (hash % 10).toInt()
}
