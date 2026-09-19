package com.ranorac.tjtimetable.scrape

import com.ranorac.tjtimetable.data.remote.TongjiImport
import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.WeekPattern
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Types shared by the 同济 scrape port.
 *
 * This file is the Kotlin counterpart of two reference files:
 *  - `dotnet/TjtCore/Model.cs` — [TongjiCourse] / [TongjiSession] (their `Course` / `Session`
 *    records) plus [TimetableDefaults] and [Slot].
 *  - `dotnet/TjtCore/Adapters/AdapterTypes.cs` — [ImportFile] / [ImportInput] /
 *    [DiagnosticLevel] / [Diagnostic] / [AdapterInput] and the shape of the import result.
 *
 * Only what [TongjiStudentAdapter] actually consumes is ported: the reference's
 * `Weekday` enum becomes [DayOfWeek] (their 1 = Monday … 7 = Sunday is exactly
 * `DayOfWeek.value`), and their `uint` week masks become [WeekPattern], whose bit 0 is
 * also week 1 — the one convention that makes this port nearly free.
 *
 * Their `ImportResult.Candidates` / `Preselect` are omitted: they exist for adapters that
 * return a pool of 平行班 for the student to tick, and the 同济 student adapter never fills
 * them. For the same reason `ImportInput.Now` (a test-only clock override consumed by the
 * pipeline, not by the adapter) is omitted.
 */

/** 节次时间定义。Ported from `Model.cs`'s `Slot(int Index, string Begin, string End)`. */
data class Slot(val index: Int, val begin: String, val end: String)

/**
 * 学期信息。Ported from `Model.cs`'s `Term`. [startDate] is the Monday of week 1
 * (`YYYY-MM-DD`) or null when it is not known.
 */
data class Term(
    val id: String,
    val name: String,
    val year: Int,
    val termNo: Int,
    val totalWeeks: Int,
    val slots: List<Slot>,
    val startDate: String? = null,
) {
    /** Port of `Term.Label`: a readable label even when the server gave no name. */
    val label: String
        get() = when {
            name.isNotEmpty() -> name
            year > 0 -> "$year-${year + 1}学年第${termNo}学期"
            else -> "未知学期"
        }

    fun slotBegin(index: Int): String = slots.firstOrNull { it.index == index }?.begin ?: ""

    fun slotEnd(index: Int): String = slots.firstOrNull { it.index == index }?.end ?: ""
}

/** Port of `Model.cs`'s `TimetableDefaults`. */
object TimetableDefaults {

    const val SCHEMA_VERSION = 1

    /** 同济校历时间戳按北京时间午夜记录，默认时区偏移（分钟）。 */
    const val DEFAULT_TZ_OFFSET_MINUTES = 480

    /** 同济默认节次表（2026-2027 学年第 1 学期校历，工作日与周末同表）。 */
    val TONGJI_SLOTS: List<Slot> = listOf(
        Slot(1, "08:00", "08:45"),
        Slot(2, "08:50", "09:35"),
        Slot(3, "10:00", "10:45"),
        Slot(4, "10:50", "11:35"),
        Slot(5, "13:30", "14:15"),
        Slot(6, "14:20", "15:05"),
        Slot(7, "15:30", "16:15"),
        Slot(8, "16:20", "17:05"),
        Slot(9, "18:30", "19:15"),
        Slot(10, "19:20", "20:05"),
        Slot(11, "20:10", "20:55"),
    )

    /** 生成 1..n 的默认节次（没有节次表时的通用导入）。 */
    fun makeDefaultSlots(count: Int = 11): List<Slot> =
        if (count == TONGJI_SLOTS.size) {
            // Kotlin lists are immutable, so the reference's defensive `with { }` copy is free.
            TONGJI_SLOTS.toList()
        } else {
            (1..count).map { Slot(it, "", "") }
        }

    /** 过滤非法节次并按下标排序。 */
    fun slotsFromList(slots: Iterable<Slot>): List<Slot> =
        slots.filter { it.index > 0 }.sortedBy { it.index }
}

enum class DiagnosticLevel { INFO, WARN, ERROR }

/** Port of `AdapterTypes.cs`'s `Diagnostic`. */
data class Diagnostic(val level: DiagnosticLevel, val code: String, val message: String)

/** Port of `AdapterTypes.cs`'s `ImportFile`. */
data class ImportFile(val name: String, val text: String)

/** Port of `AdapterTypes.cs`'s `ImportInput`. */
data class ImportInput(
    /** 直接粘贴的 JSON / HTML 文本。 */
    val text: String? = null,
    /** 选择的文件（可多份：课表 + 校历 + 学生信息）。 */
    val files: List<ImportFile>? = null,
    /** 用户显式指定的适配器 id，优先于自动探测。 */
    val adapterId: String? = null,
    /** 校历里有多个学期时，指定要用的学期 id；抓取时来自请求 URL 的 `calendarId`。 */
    val termId: String? = null,
    /** 写入 `Timetable.Source.ImportedAt`（测试用）。 */
    val importedAt: String? = null,
)

/**
 * 一次上课安排（同一天、连续节次、一组周次、一个教室）。
 *
 * Port of `Model.cs`'s `Session(string Id, Weekday Day, int StartSlot, int EndSlot, uint Weeks, string? Room)`.
 * [rawWeeks] is an addition: the app's `CourseSession` keeps the server's own week field
 * verbatim so an import problem can be diagnosed without re-fetching, and the reference's
 * record has nowhere to put it. [id] is the reference's synthesized session id, kept
 * because the flat shape dedupes on it.
 */
data class TongjiSession(
    val id: String,
    val day: DayOfWeek,
    val startSlot: Int,
    val endSlot: Int,
    val weeks: WeekPattern,
    val room: String? = null,
    val rawWeeks: String? = null,
)

/**
 * 教学班（用户视角的「一门课」）。
 *
 * Port of `Model.cs`'s `Course(string Id, string Name, IReadOnlyList<string> Teachers,
 * IReadOnlyList<Session> Sessions, string? CourseCode, string? TeachingClassCode,
 * string? Faculty, string? Campus, string? Color)`. `Color` is dropped: the reference's is a
 * free-form colour string, while this app's `Course.colorIndex` is an index into a fixed
 * palette and is derived from the course name when unset.
 */
data class TongjiCourse(
    val id: String,
    val name: String,
    val teachers: List<String>,
    val sessions: List<TongjiSession>,
    val courseCode: String? = null,
    val teachingClassCode: String? = null,
    val faculty: String? = null,
    val campus: String? = null,
    /**
     * Additions beyond the reference, which ignores these four although the report payload
     * carries them (see `TongjiStudentAdapter`'s report branch).
     */
    val className: String? = null,
    val credits: Double? = null,
    val assessmentMode: String? = null,
    val teachingWay: String? = null,
)

/**
 * 适配器产出：课程 + 按**下标**归档的上课时段 + 诊断。
 *
 * The three load-bearing fields mirror `data/remote/TongjiImport.Imported` exactly, so
 * [toRemoteImported] hands the repository a value it can persist with no glue; the rest
 * carries what the reference's `ImportResult` carries (term, diagnostics, sources, meta).
 */
data class Imported(
    val courses: List<Course>,
    /** Sessions keyed by the **index** into [courses] — the convention this app already uses. */
    val sessionsByCourse: Map<Int, List<CourseSession>>,
    /** Non-fatal problems worth surfacing, i.e. the reference's Warn/Error diagnostics. */
    val warnings: List<String>,
    /** Every diagnostic the reference produced, including its Info-level progress notes. */
    val diagnostics: List<Diagnostic> = emptyList(),
    val term: Term? = null,
    /** 学期 id, explicit `ImportInput.termId` first, then the body's `calendarId`. */
    val calendarId: String? = null,
    val calendarName: String? = null,
    val sources: List<String> = emptyList(),
    val adapterId: String = TongjiStudentAdapter.ADAPTER_ID,
    val adapterName: String = TongjiStudentAdapter.DISPLAY_NAME,
    val adapterVersion: String = TongjiStudentAdapter.ADAPTER_VERSION,
) {
    val sessionCount: Int get() = sessionsByCourse.values.sumOf { it.size }

    /** Drop-in for the repository's existing importer result type. */
    fun toRemoteImported(): TongjiImport.Imported =
        TongjiImport.Imported(courses, sessionsByCourse, warnings)
}

/** Port of `AdapterTypes.cs`'s `AdapterInput` helpers. */
object AdapterInput {

    /** The label the reference gives to pasted text (files use their own name). */
    const val PASTED_LABEL = "<粘贴内容>"

    private val json = Json

    /** 收集输入里所有可解析的文本片段（粘贴的 + 各文件）。 */
    fun texts(input: ImportInput): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        if (!input.text.isNullOrBlank()) list += PASTED_LABEL to input.text
        for (file in input.files.orEmpty()) {
            if (!file.text.isBlank()) list += file.name to file.text
        }
        return list
    }

    /** 安全解析 JSON，失败返回 `null`。 */
    fun tryParseJson(text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        return try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun asRecord(value: JsonElement?): JsonObject? = value as? JsonObject

    fun asArray(value: JsonElement?): JsonArray? = value as? JsonArray

    /** 解开同济教务常见的 `{code, msg, data}` 包装；也接受裸数组。 */
    fun unwrapData(value: JsonElement): JsonElement =
        (value as? JsonObject)?.get("data") ?: value

    fun diagnostic(level: DiagnosticLevel, code: String, message: String): Diagnostic =
        Diagnostic(level, code, message)
}

/**
 * The slice of `dotnet/TjtCore/Time.cs` the adapter needs: absolute instant / `YYYY-MM-DD`
 * conversion in the school's fixed +08:00 zone, and Monday-of-week arithmetic.
 *
 * Oddities are kept, not fixed: `IsoToDayNumber`'s prefix parse tolerates trailing
 * characters (so `"2026-09-14T00:00"` parses) and carries overflowing months/days the way
 * JS `Date.UTC` does, because that is what the reference does. The rest of `Time.cs`
 * (term week, today's sessions, next session, countdown text) is deliberately *not* ported:
 * this app already owns that ground in `domain/TermCalendar`, `domain/TimetableResolver`
 * and `domain/ReminderPlanner`, and two implementations would be worse than one.
 */
object TongjiTime {

    /** 同济校历时间戳按北京时间午夜记录。 */
    const val TZ_OFFSET_MINUTES = TimetableDefaults.DEFAULT_TZ_OFFSET_MINUTES

    /** `^(\d{4})-(\d{2})-(\d{2})`, prefix match — same regex the reference mirrors. */
    private val ISO_PREFIX = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})")

    private val tzOffset: ZoneOffset = ZoneOffset.ofTotalSeconds(TZ_OFFSET_MINUTES * 60)

    /**
     * `YYYY-MM-DD` → date, or null. Prefix match; month/day overflow carries; years
     * `0000`–`0099` are read as `1900 + y` (JS `Date.UTC` semantics).
     */
    fun isoDateOrNull(iso: String?): LocalDate? {
        val text = iso?.trim() ?: return null
        val match = ISO_PREFIX.find(text) ?: return null
        var year = match.groupValues[1].toInt()
        if (year <= 99) year += 1900
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        return runCatching {
            LocalDate.of(year, 1, 1)
                .plusMonths((month - 1).toLong())
                .plusDays((day - 1).toLong())
        }.getOrNull()
    }

    /** 毫秒时间戳 → 指定时区（默认 UTC+8）的 `YYYY-MM-DD`；越界返回 null。 */
    fun msToIsoDate(ms: Long, tzOffsetMinutes: Int = TZ_OFFSET_MINUTES): String? = runCatching {
        Instant.ofEpochMilli(ms)
            .atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60))
            .toLocalDate()
            .toString()
    }.getOrNull()

    /** 某天所在周的周一（按周一为一周起点）；解析失败返回 `null`。 */
    fun mondayOf(iso: String?): String? {
        val date = isoDateOrNull(iso) ?: return null
        return date.minusDays((date.dayOfWeek.value - 1).toLong()).toString()
    }

    /** 日期加减天数；日期解析失败返回 `null`。 */
    fun addDays(iso: String?, days: Int): String? =
        isoDateOrNull(iso)?.plusDays(days.toLong())?.toString()
}

/** Their `(Weekday)day` cast, with the 1..7 guard the adapter applies first. */
internal fun weekdayOf(day: Int): DayOfWeek? = if (day in 1..7) DayOfWeek.of(day) else null

/** [TongjiCourse] → this app's [Course]. */
fun TongjiCourse.toDomainCourse(): Course = Course(
    // Local primary key: 0 means "let Room assign it", exactly as the existing importer does.
    id = 0,
    name = name,
    courseCode = courseCode,
    // The reference keeps the id as a string because 教学班 ids exceed Int and the flat shape
    // may fall back to a non-numeric code; only those that are numeric can be a Long here.
    teachingClassId = id.toLongOrNull(),
    classCode = teachingClassCode,
    // NOTE: the reference maps none of 班级 / 学分 / 考核方式 / 授课方式 even though the report
    // payload carries all four. Deliberately diverging here: they cost nothing to carry and
    // they are exactly what the course detail sheet displays, so dropping them would leave a
    // detail page full of em dashes for no benefit. See TongjiStudentAdapter's report branch
    // for where they are read.
    className = className,
    teacher = teachers.joinToString(", ").ifBlank { null },
    credits = credits,
    campus = campus,
    assessmentMode = assessmentMode,
    teachingWay = teachingWay,
    department = faculty,
    colorIndex = null,
    note = null,
    hidden = false,
)

/** [TongjiCourse] → this app's sessions, tagged with the course's index in the result list. */
fun TongjiCourse.toDomainSessions(courseIndex: Int): List<CourseSession> =
    sessions.map { session ->
        CourseSession(
            id = 0,
            courseId = courseIndex.toLong(),
            dayOfWeek = session.day,
            startUnit = session.startSlot,
            endUnit = session.endSlot,
            weeks = session.weeks,
            rawWeeks = session.rawWeeks,
            room = session.room,
            building = null,
            // The reference's Session carries no teacher and no campus: on a merged cell
            // (a course that swaps teacher by week) a per-session teacher would be meaningless.
            teacher = null,
            campus = null,
        )
    }

/**
 * [Term] → this app's [TermCalendar], or null when the term has no known start date.
 *
 * Not in the reference: their `Term` is consumed directly by their renderer. Our domain's
 * calendar additionally needs an end date, and the reference's term does not carry one, so
 * the term is taken to end on the last day of [Term.totalWeeks] teaching weeks.
 */
fun Term.toTermCalendar(isCurrent: Boolean = true): TermCalendar? {
    val firstWeekStart = TongjiTime.mondayOf(startDate)?.let { LocalDate.parse(it) } ?: return null
    val weeks = totalWeeks.coerceIn(1, WeekPattern.MAX_WEEK)
    return TermCalendar(
        calendarId = id,
        name = label,
        year = year,
        term = termNo,
        firstWeekStart = firstWeekStart,
        endDate = firstWeekStart.plusWeeks(weeks.toLong()).minusDays(1),
        totalWeeks = weeks,
        weekStartDay = DayOfWeek.MONDAY,
        isCurrent = isCurrent,
    )
}
