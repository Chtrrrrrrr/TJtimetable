package com.ranorac.tjtimetable.data.remote

import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.WeekPattern
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Maps 开放平台 payloads onto the domain model.
 *
 * Two awkward realities drive this code:
 *
 *  1. The API returns **one entry per course** with its slots nested, but a
 *    course's slots can disagree on teacher/room, so sessions carry their own
 *    room while the course carries the shared metadata.
 *  2. The `weeks` field is sometimes missing or unparseable. When that happens
 *    the 教务系统 means "every week", so the session is widened to the whole term
 *    rather than dropped — silently losing a class is far worse than showing one
 *    that turns out to be sporadic. The original string is preserved in
 *    `rawWeeks` so the guess is auditable.
 */
object TongjiImport {

    /** Result of a mapping pass, including anything worth telling the student. */
    data class Imported(
        val courses: List<Course>,
        /** Sessions keyed by the *index* into [courses]. */
        val sessionsByCourse: Map<Int, List<CourseSession>>,
        val warnings: List<String>,
    ) {
        val sessionCount: Int get() = sessionsByCourse.values.sumOf { it.size }
    }

    /**
     * Maps `v1/rt/onetongji/student_timetable` — the real-time, nested shape.
     *
     * @param defaultWeeks weeks to assume when the server omits them; normally
     *   the whole term.
     */
    fun fromRealtime(
        dtos: List<StudentCourseDto>,
        defaultWeeks: WeekPattern,
    ): Imported {
        val warnings = mutableListOf<String>()
        val courses = mutableListOf<Course>()
        val sessions = mutableMapOf<Int, List<CourseSession>>()

        // Group slots by 教学班, falling back to the class code and then the name,
        // because teachingClassId is occasionally absent.
        val grouped = LinkedHashMap<String, MutableList<StudentCourseDto>>()
        for (dto in dtos) {
            if (dto.courseName.isNullOrBlank()) continue
            val key = dto.teachingClassId?.toString()
                ?: dto.classCode
                ?: dto.courseName!!
            grouped.getOrPut(key) { mutableListOf() }.add(dto)
        }

        for ((_, group) in grouped) {
            val head = group.first()
            val index = courses.size
            val course = Course(
                name = head.courseName!!.trim(),
                courseCode = head.courseCode?.trim()?.ifBlank { null },
                teachingClassId = head.teachingClassId,
                classCode = head.classCode?.trim()?.ifBlank { null },
                className = head.className?.trim()?.ifBlank { null },
                teacher = cleanTeacher(head.teacherName),
                credits = head.credits,
                campus = head.campusI18n?.ifBlank { null } ?: head.campus?.ifBlank { null },
                assessmentMode = head.assessmentModeI18n?.ifBlank { null },
                teachingWay = head.teachingWayI18n?.ifBlank { null },
            )
            courses += course

            val slots = mutableListOf<CourseSession>()
            for (dto in group) {
                for (entry in dto.timeTableList.orEmpty()) {
                    val dow = entry.dayOfWeek?.takeIf { it in 1..7 }?.let(DayOfWeek::of)
                    val start = entry.timeStart
                    val end = entry.timeEnd
                    if (dow == null || start == null || end == null || start > end) {
                        warnings += "「${course.name}」有一条时间不完整的排课已跳过"
                        continue
                    }
                    val raw = entry.weekNum ?: entry.weekstr
                    // `weekstr` is a real fallback field, not merely a label for the warning
                    // below: `fromApiFields` falls back to its third argument, so omitting it
                    // meant a slot whose `weekNum` was missing or unparseable while `weekstr`
                    // carried "[1-17单]" was widened to the whole term — wrong weeks drawn and
                    // spurious reminders. The resolved `weeks` array still wins when present.
                    var weeks = WeekPattern.fromApiFields(entry.weeks, entry.weekNum, entry.weekstr)
                    if (weeks.isEmpty) {
                        weeks = defaultWeeks
                        if (defaultWeeks.isNotEmpty) {
                            warnings += "「${course.name}」周次字段为「${raw ?: "空"}」，已按整学期处理"
                        }
                    }
                    slots += CourseSession(
                        courseId = index.toLong(),
                        dayOfWeek = dow,
                        startUnit = start,
                        endUnit = end,
                        weeks = weeks,
                        rawWeeks = raw,
                        room = entry.roomIdI18n?.ifBlank { null } ?: entry.roomId?.ifBlank { null },
                        teacher = cleanTeacher(entry.teacherName) ?: course.teacher,
                        campus = entry.campusI18n?.ifBlank { null } ?: course.campus,
                    )
                }
            }
            sessions[index] = slots
        }

        return Imported(courses, sessions, warnings)
    }

    /**
     * Maps `v2/dc/teaching_info/student_timetable` — the flat batch shape, where
     * every row is already a single slot and `week` is a comma-separated list.
     */
    fun fromBatch(
        rows: List<StudentTimetableRowDto>,
        defaultWeeks: WeekPattern,
    ): Imported {
        val warnings = mutableListOf<String>()
        // `delInd == "D"` rows mean the class was retracted (退课 / closed 教学班).
        val live = rows.filterNot { it.isDeleted }
        val retracted = rows.size - live.size

        val courses = mutableListOf<Course>()
        val sessions = mutableMapOf<Int, List<CourseSession>>()
        val grouped = LinkedHashMap<String, MutableList<StudentTimetableRowDto>>()
        for (row in live) {
            if (row.courseName.isNullOrBlank()) continue
            val key = row.lessonId ?: row.administrativeClassNo ?: row.courseName!!
            grouped.getOrPut(key) { mutableListOf() }.add(row)
        }

        for ((_, group) in grouped) {
            val head = group.first()
            val index = courses.size
            val course = Course(
                name = head.courseName!!.trim(),
                courseCode = null,
                teachingClassId = head.lessonId?.toLongOrNull(),
                classCode = head.administrativeClassNo?.trim()?.ifBlank { null },
                teacher = cleanTeacher(head.teacherName),
                campus = head.campusName?.trim()?.ifBlank { null },
                department = head.classDeptName?.trim()?.ifBlank { null },
            )
            courses += course

            val slots = mutableListOf<CourseSession>()
            for (row in group) {
                val dow = row.weekday?.takeIf { it in 1..7 }?.let(DayOfWeek::of)
                val start = row.startUnit
                val end = row.endUnit
                if (dow == null || start == null || end == null || start > end) {
                    warnings += "「${course.name}」有一条时间不完整的排课已跳过"
                    continue
                }
                var weeks = WeekPattern.parse(row.week)
                if (weeks.isEmpty) {
                    weeks = defaultWeeks
                    if (defaultWeeks.isNotEmpty) {
                        warnings += "「${course.name}」周次字段为「${row.week ?: "空"}」，已按整学期处理"
                    }
                }
                slots += CourseSession(
                    courseId = index.toLong(),
                    dayOfWeek = dow,
                    startUnit = start,
                    endUnit = end,
                    weeks = weeks,
                    rawWeeks = row.week,
                    room = row.classroomName?.trim()?.ifBlank { null },
                    building = row.classroomBuildingName?.trim()?.ifBlank { null },
                    teacher = cleanTeacher(row.teacherName) ?: course.teacher,
                    campus = row.campusName?.trim()?.ifBlank { null },
                )
            }
            sessions[index] = slots
        }

        if (retracted > 0) warnings += "已忽略 $retracted 条退课/关班记录"

        return Imported(courses, sessions, warnings)
    }

    /**
     * Builds a [TermCalendar] from 当前学期日历编号.
     *
     * `beginDay`/`endDay` are epoch millis. `weekNum` counts *all* weeks in the
     * school calendar including breaks, which is not the number of teaching
     * weeks, so it is used only as an upper bound and the caller can trim it.
     */
    fun toTermCalendar(
        dto: CurrentTermCalendarDto,
        zone: ZoneId = ZoneId.of("Asia/Shanghai"),
    ): TermCalendar? {
        val cal = dto.schoolCalendar ?: return null
        val beginMs = cal.beginDay ?: return null
        val endMs = cal.endDay ?: return null
        val begin = Instant.ofEpochMilli(beginMs).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        val weeks = (cal.weekNum ?: 18).coerceIn(1, WeekPattern.MAX_WEEK)
        return TermCalendar.fromApi(
            calendarId = cal.id?.toString() ?: dto.simpleName ?: "current",
            name = dto.simpleName ?: cal.let { "${it.year}学年度第${it.term}学期" },
            year = cal.year ?: begin.year,
            term = cal.term ?: 1,
            beginDay = begin,
            endDay = end,
            totalWeeks = weeks,
            isCurrent = true,
        )
    }

    /**
     * `teacherName` often arrives as `张亚英(05152)`. The 工号 belongs in the
     * course metadata, not in the name shown on a card.
     */
    private fun cleanTeacher(raw: String?): String? {
        val trimmed = raw?.trim()?.ifBlank { null } ?: return null
        return trimmed.replace(Regex("""[（(]\s*\d+\s*[)）]\s*$"""), "").trim().ifBlank { null }
    }

    /** Whole-term pattern, used as the fallback when the server omits 周次. */
    fun fullTerm(totalWeeks: Int): WeekPattern = WeekPattern.range(1, totalWeeks.coerceAtLeast(1))

    /** Convenience for callers that only have raw 校历 day codes. */
    fun parseEpochDayOrNull(text: String?): LocalDate? =
        text?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
