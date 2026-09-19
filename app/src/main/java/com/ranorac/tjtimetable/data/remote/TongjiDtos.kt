package com.ranorac.tjtimetable.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for 同济大学开放平台 (`api.tongji.edu.cn`).
 *
 * Every field is nullable with a default: the platform documents these
 * responses loosely and adds fields without notice, so a strict mapping would
 * break on every server-side change. Combined with
 * `Json { ignoreUnknownKeys = true }` this makes import resilient.
 */

/** Every response is wrapped in `{code, msg, data}`. */
@Serializable
data class ApiEnvelope<T>(
    val code: String? = null,
    val msg: String? = null,
    val data: T? = null,
)

object ApiCode {
    const val OK = "A00000"

    /** 校历区间未录入 — e.g. asking for next summer before it is published. */
    const val CALENDAR_RANGE_EMPTY = "A06500"
}

// --------------------------------------------------------------------- auth

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
)

// ------------------------------------------------- 学期 / 校历 (无需授权)

/** `GET /v1/rt/teaching_info/semester` — 学年学期信息. */
@Serializable
data class SemesterDto(
    @SerialName("ID") val id: String? = null,
    val semester: String? = null,
    val semesterName: String? = null,
    /** `yyyy-MM-dd` */
    val beginDay: String? = null,
    /** `yyyy-MM-dd` */
    val endDay: String? = null,
    val year: String? = null,
)

/**
 * `GET /v1/rt/onetongji/school_calendar_current_term_calendar`
 * — 当前学期日历编号. `beginDay`/`endDay` are epoch millis.
 */
@Serializable
data class CurrentTermCalendarDto(
    val schoolCalendar: SchoolCalendarDto? = null,
    /** Current teaching week number. */
    val week: Int? = null,
    /** e.g. `2025-2026学年度第2学期` */
    val simpleName: String? = null,
    val now: String? = null,
    val name: String? = null,
)

@Serializable
data class SchoolCalendarDto(
    val id: Long? = null,
    val year: Int? = null,
    val term: Int? = null,
    val beginDay: Long? = null,
    val endDay: Long? = null,
    /** Total weeks in the calendar, including breaks. */
    val weekNum: Int? = null,
    /** Which weekday a teaching week begins on. */
    val weekBenginDay: Int? = null,
)

// ------------------------------------------------------------ 课表 (需授权)

/**
 * One row of `GET /v1/rt/onetongji/student_timetable`.
 *
 * The API returns **one entry per course**, with its meeting slots nested in
 * [timeTableList], which is what makes 单双周 recoverable: each slot carries both
 * a resolved `weeks` array and a raw `weekNum` string.
 */
@Serializable
data class StudentCourseDto(
    val teachingClassId: Long? = null,
    val classCode: String? = null,
    val className: String? = null,
    val courseCode: String? = null,
    val courseName: String? = null,
    val credits: Double? = null,
    val teacherName: String? = null,
    val campus: String? = null,
    val campusI18n: String? = null,
    val classRoom: String? = null,
    val classRoomI18n: String? = null,
    val classRoomName: String? = null,
    val assessmentModeI18n: String? = null,
    val teachingWayI18n: String? = null,
    val classTime: String? = null,
    val compulsory: String? = null,
    val timeTableList: List<TimeTableEntryDto>? = null,
)

/** A single weekly meeting slot inside [StudentCourseDto]. */
@Serializable
data class TimeTableEntryDto(
    /** 1 = Monday … 7 = Sunday. */
    val dayOfWeek: Int? = null,
    /** Start 节. */
    val timeStart: Int? = null,
    /** End 节. */
    val timeEnd: Int? = null,
    /** Raw week string, e.g. `[1-17]`, `[1-17单]`, `[2-16双]`. */
    val weekNum: String? = null,
    val weekstr: String? = null,
    /** Resolved week numbers — the most reliable source when present. */
    val weeks: List<Int>? = null,
    val roomId: String? = null,
    val roomIdI18n: String? = null,
    val teacherName: String? = null,
    val teacherCode: String? = null,
    val timeAndRoom: String? = null,
    val timeTab: String? = null,
    val campus: String? = null,
    val campusI18n: String? = null,
)

/**
 * One row of `GET /v2/dc/teaching_info/student_timetable` — the flat, batch
 * variant. `week` is a comma-separated list such as `"1,2,3,…,17"`, and 单双周
 * appears as an odd or even subset of that list.
 */
@Serializable
data class StudentTimetableRowDto(
    val id: String? = null,
    val userId: String? = null,
    val name: String? = null,
    val year: Int? = null,
    val term: Int? = null,
    val administrativeClassNo: String? = null,
    val newAdministrativeClassNo: String? = null,
    val courseName: String? = null,
    /** 1 = Monday … 7 = Sunday. */
    val weekday: Int? = null,
    val startUnit: Int? = null,
    val endUnit: Int? = null,
    val week: String? = null,
    val teacherId: String? = null,
    val teacherName: String? = null,
    val lessonId: String? = null,
    val projId: String? = null,
    val projName: String? = null,
    val classroomCode: String? = null,
    val classroomName: String? = null,
    val classroomEngName: String? = null,
    val classroomBuildingName: String? = null,
    val campusName: String? = null,
    val classDeptCode: String? = null,
    val classDeptName: String? = null,
    val updateTime: String? = null,
    /** `D` marks a row retracted by 退课 or a cancelled 教学班. */
    val delInd: String? = null,
) {
    val isDeleted: Boolean get() = delInd.equals("D", ignoreCase = true)
}

// ------------------------------------------------------------ 个人信息

@Serializable
data class PersonInfoDto(
    val userId: String? = null,
    val name: String? = null,
    val sexCode: String? = null,
    val deptName: String? = null,
    val majorName: String? = null,
)
