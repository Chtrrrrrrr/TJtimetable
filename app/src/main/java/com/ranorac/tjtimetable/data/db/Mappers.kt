package com.ranorac.tjtimetable.data.db

import com.ranorac.tjtimetable.domain.AdjustmentSource
import com.ranorac.tjtimetable.domain.CalendarDayKind
import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.DayAdjustment
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.WeekPattern
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Conversions between the Room schema and the domain model.
 *
 * Kept as plain functions rather than extension-heavy helpers so that the
 * direction of every mapping is obvious at the call site — persistence details
 * (bitmasks, epoch days, weekday ints) must not leak into `domain`.
 */

// ------------------------------------------------------------------ terms

fun TermEntity.toDomain(): TermCalendar = TermCalendar(
    calendarId = calendarId,
    name = name,
    year = year,
    term = term,
    firstWeekStart = LocalDate.ofEpochDay(firstWeekStartEpochDay),
    endDate = LocalDate.ofEpochDay(endEpochDay),
    totalWeeks = totalWeeks,
    weekStartDay = runCatching { DayOfWeek.of(weekStartDay) }.getOrDefault(DayOfWeek.MONDAY),
    isCurrent = isCurrent,
)

fun TermCalendar.toEntity(currentWeek: Int? = null): TermEntity = TermEntity(
    calendarId = calendarId,
    name = name,
    year = year,
    term = term,
    firstWeekStartEpochDay = firstWeekStart.toEpochDay(),
    endEpochDay = endDate.toEpochDay(),
    totalWeeks = totalWeeks,
    weekStartDay = weekStartDay.value,
    isCurrent = isCurrent,
    currentWeek = currentWeek,
)

// ---------------------------------------------------------------- courses

fun CourseEntity.toDomain(): Course = Course(
    id = id,
    name = name,
    courseCode = courseCode,
    teachingClassId = teachingClassId,
    classCode = classCode,
    className = className,
    teacher = teacher,
    credits = credits,
    campus = campus,
    assessmentMode = assessmentMode,
    teachingWay = teachingWay,
    department = department,
    colorIndex = colorIndex,
    note = note,
    hidden = hidden,
)

fun Course.toEntity(termId: String, updatedAt: Long = System.currentTimeMillis()): CourseEntity =
    CourseEntity(
        id = id,
        termId = termId,
        name = name,
        courseCode = courseCode,
        teachingClassId = teachingClassId,
        classCode = classCode,
        className = className,
        teacher = teacher,
        credits = credits,
        campus = campus,
        assessmentMode = assessmentMode,
        teachingWay = teachingWay,
        department = department,
        colorIndex = colorIndex,
        note = note,
        hidden = hidden,
        updatedAt = updatedAt,
    )

// --------------------------------------------------------------- sessions

fun SessionEntity.toDomain(): CourseSession = CourseSession(
    id = id,
    courseId = courseId,
    dayOfWeek = runCatching { DayOfWeek.of(dayOfWeek) }.getOrDefault(DayOfWeek.MONDAY),
    startUnit = startUnit,
    endUnit = endUnit,
    weeks = WeekPattern.fromBits(weekBits),
    rawWeeks = rawWeeks,
    room = room,
    building = building,
    teacher = teacher,
    campus = campus,
)

fun CourseSession.toEntity(): SessionEntity = SessionEntity(
    id = id,
    courseId = courseId,
    dayOfWeek = dayOfWeek.value,
    startUnit = startUnit,
    endUnit = endUnit,
    weekBits = weeks.bits,
    rawWeeks = rawWeeks,
    room = room,
    building = building,
    teacher = teacher,
    campus = campus,
)

/** Flattens a course with its slots into domain types. */
fun CourseWithSessions.toDomainPair(): Pair<Course, List<CourseSession>> =
    course.toDomain() to sessions.map { it.toDomain() }

// ------------------------------------------------------- 调休 adjustments

fun DayAdjustmentEntity.toDomain(): DayAdjustment = DayAdjustment(
    date = LocalDate.ofEpochDay(epochDay),
    kind = CalendarDayKind.fromApi(kindCode),
    followsWeekday = followsWeekday?.let { runCatching { DayOfWeek.of(it) }.getOrNull() },
    noClasses = noClasses,
    source = runCatching { AdjustmentSource.valueOf(sourceName) }
        .getOrDefault(AdjustmentSource.MANUAL),
    note = note,
)

fun DayAdjustment.toEntity(termId: String): DayAdjustmentEntity = DayAdjustmentEntity(
    epochDay = date.toEpochDay(),
    termId = termId,
    kindCode = kind.apiCode,
    followsWeekday = followsWeekday?.value,
    noClasses = noClasses,
    sourceName = source.name,
    note = note,
)
