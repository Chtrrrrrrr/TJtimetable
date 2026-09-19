package com.ranorac.tjtimetable.scrape

import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.WeekPattern
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/**
 * 同济大学 1 系统「个人课表」适配器。
 *
 * Ported from `gzy31007/TJDesktopTimetable`'s `dotnet/TjtCore/Adapters/TongjiStudentAdapter.cs`
 * (adapter id `tongji-student`, version `3.1.0`), which is itself a port of their TS
 * `adapters/tongji-student.ts`.
 *
 * 主数据源：选课服务接口 `POST /api/electionservice/student/{id}/getDataBk` 的响应，
 * 结构为 `data.selectedCourses[].course.times[]`:
 *
 * ```
 * { "data": { "calendarId": 122,
 *     "selectedCourses": [{ "course": {
 *       "courseName": "测试课程A", "courseCode": "TSTA001",
 *       "teachClassId": 9000000000000011, "teachClassCode": "TSTA0A01",
 *       "times": [{ "dayOfWeek": 4, "timeStart": 5, "timeEnd": 6, "weeks": [1,3,5],
 *                   "roomIdI18n": "测试楼201", "teacherCodeI18n": "测试教师甲", "teacherCode": "10001" }] } }] } }
 * ```
 *
 * (Field names are the real ones; the values above are invented — see
 * `app/src/test/java/.../scrape/Samples.kt` for why.)
 *
 * 三个形状都认，因为 1 系统有三条接口：
 *  1. **选课服务** `data.selectedCourses[].course.times[]` —— `weeks` 是**周次数组**；
 *  2. **报表接口** `data[].timeTableList[]`（课表页真正调的那条，
 *     `GET /api/electionservice/reportManagement/findStudentTimetab?calendarId=…&studentCode=…`；
 *     研究生 `findSchoolTimetab2` 按前端源码是 `data.list`，同一套字段）——
 *     `dayOfWeek/timeStart/timeEnd/weeks` 语义与选课服务完全一致，只是包法不同；
 *  3. **排课服务** 扁平行（`weekState` 掩码 + `dayOfWeek`）。
 *
 * 三条路的取舍与参考实现逐条对齐，包括那些看起来「多余」的细节：
 *  - 同一格（同天 + 同起止节次 + 同教室）的多条 times **合并成一块、周次取并集**
 *    （按周次换老师的课程不合并就是一堆完全重叠的色块）；
 *  - 没有排课时段的课程（军训）**跳过**并给出 Info 级诊断；
 *  - 教师指纹 `姓名(工号)` 从 `value` 文本里正则提取，拿不到才退回同一行的 `teacherCode`；
 *  - `weeks` 缺失/空数组时按 **整学期 16 周** 处理（参考实现硬编码 `Weeks.FullMask(16)`，
 *    与学期长度无关）。
 *
 * JSON 一律用 [JsonElement] 动态读取，与参考实现用 `JsonElement` 而不是反序列化成具体类型
 * 的理由相同：教务数据异构、字段名不确定，弱类型探测比强类型绑定更贴合。这也让整个适配器
 * 不依赖 Android，可以从一段 JSON 字符串直接单测。
 *
 * The reference's `try/finally { doc.Dispose() }` around its `JsonDocument`s has no
 * counterpart here: kotlinx `JsonElement`s are plain immutable values with no unmanaged
 * resources, so there is nothing to release when parsing fails halfway.
 */
object TongjiStudentAdapter {

    const val ADAPTER_ID = "tongji-student"
    const val ADAPTER_VERSION = "3.1.0"
    const val TONGJI_ORIGIN = "https://1.tongji.edu.cn"
    const val DISPLAY_NAME = "同济大学 · 1 系统个人课表"

    const val DESCRIPTION =
        "解析 1 系统课表：个人课表（`data.selectedCourses[].course.times[]`）与课表页报表接口" +
            "（`data[].timeTableList[]`，如 `reportManagement/findStudentTimetab`），也兼容排课服务的扁平格式。"

    /** 接口本身支持抓取（Cookie 抓取在 UI 侧）。 */
    const val CAN_FETCH = true

    /**
     * Weeks to assume when a `times[]`/`timeTableList[]` entry carries no `weeks`.
     *
     * 16 is not a guess: the reference hard-codes `Weeks.FullMask(16)` at both call sites,
     * independently of the term's own length.
     */
    const val FALLBACK_TOTAL_WEEKS = 16

    /**
     * 教室/教师文本里的教师指纹：`姓名(工号)`。
     *
     * 与 TS 的 `/([\u4e00-\u9fa5]{2,8})\((\d{3,6})\)/g` 等价，两处细节必须保持一致：
     * 中文用 BMP 汉字区间 `\u4e00-\u9fa5`；数字用 `[0-9]` 而不是 `\d`（JS 的 `\d` 只匹配 ASCII）。
     */
    private val TEACHER_RE = Regex("([\u4e00-\u9fa5]{2,8})\\(([0-9]{3,6})\\)")

    private val INTEGER_RE = Regex("^-?[0-9]+$")

    /** 文本末尾是不是「（工号）」：`(12345)`（半角，实测就是这个形态）。 */
    private val TRAILING_CODE_RE = Regex("\\([0-9]{3,6}\\)$")

    // ------------------------------------------------------------------ 入口

    /**
     * 自动探测：像不像可识别的同济课表数据（0 = 不匹配，1 = 确定）。
     *
     * 与参考实现的 `DetectScore` 一致：先看标记子串再解析，且**空数组也算识别成功**，
     * 好让解析器给出「已识别但没有课表数据」这类更有用的诊断，而不是「无法识别格式」。
     */
    fun detect(input: ImportInput): Double {
        for ((_, text) in AdapterInput.texts(input)) {
            if (!text.contains("selectedCourses") &&
                !text.contains("weekState") &&
                !text.contains("timeTableList")
            ) {
                continue
            }

            val root = AdapterInput.tryParseJson(text) ?: continue
            val raw = AdapterInput.unwrapData(root)

            if (AdapterInput.asArray(prop(raw, "selectedCourses")) != null) return 0.98
            if (collectReportItems(raw).isNotEmpty()) return 0.97

            val list = AdapterInput.asArray(raw)
            if (list != null && list.any { has(it, "weekState") && has(it, "dayOfWeek") }) return 0.9
        }

        return 0.0
    }

    /** Convenience overload of [detect] for a single pasted body. */
    fun detect(text: String?): Double = detect(ImportInput(text = text))

    /**
     * Compatibility shim for callers that only ask "is this ours?".
     * Implemented on top of [detect] so the two can never disagree.
     */
    fun looksLikeTimetable(text: String?): Boolean = detect(text) > 0.0

    /**
     * Parses one captured body.
     *
     * @param termId 学期 id read off the request URL by [TongjiWebCapture.calendarIdOf];
     *   the report and election responses do not carry it, and without it the built-in
     *   term table cannot supply the semester's start date.
     */
    fun parse(text: String, termId: String? = null): Imported =
        parse(ImportInput(text = text, termId = termId))

    /**
     * Nullable convenience for callers that want "this was not 同济课表数据" to be a `null` rather
     * than an empty result plus a diagnostic — the shape `TimetableRepository`'s captured-response
     * import is written against.
     *
     * [parse] itself deliberately never returns null: the reference's `Parse` always produces an
     * `ImportResult` and reports the failure as a `tongji.schedule.missing` diagnostic, so the
     * "recognised or not" decision belongs to [detect] / [looksLikeTimetable] plus this helper.
     */
    fun parseOrNull(text: String, termId: String? = null): Imported? =
        parse(text, termId).takeIf { imported ->
            imported.diagnostics.none { it.code == "tongji.schedule.missing" }
        }

    /** Port of the reference's `Parse(ImportInput, AdapterContext)`; never throws. */
    fun parse(input: ImportInput): Imported {
        val diagnostics = mutableListOf<Diagnostic>()
        val classified = classify(input)

        // 学期 id：显式指定（抓取时从请求 URL 的 calendarId 取）优先于响应体里的 calendarId。
        // 报表格式的响应体里没有 calendarId（它只在 URL 上），所以这一条是它能拿到
        // 开学日期/教学周的前提 —— 内置学期表按 id 命中（见 [TongjiTerms]）。
        val termId = input.termId ?: classified.calendarId

        val term = buildTerm(
            pickTerm(classified.calendar, termId),
            termId,
            classified.calendarName,
            diagnostics,
        )

        var courses: List<TongjiCourse> = emptyList()
        if (classified.selected.isNotEmpty()) {
            courses = buildCoursesFromSelected(classified.selected, diagnostics)
            diagnostics += AdapterInput.diagnostic(
                DiagnosticLevel.INFO,
                "tongji.personal",
                "识别为个人课表：已选 ${classified.selected.size} 门课。",
            )
        } else if (classified.report.isNotEmpty()) {
            courses = buildCoursesFromReport(classified.report, diagnostics)
            diagnostics += AdapterInput.diagnostic(
                DiagnosticLevel.INFO,
                "tongji.report",
                "识别为 1 系统课表（报表接口格式）：共 ${classified.report.size} 门课。",
            )
        } else if (classified.flat.isNotEmpty()) {
            courses = buildCoursesFromFlat(classified.flat)
            diagnostics += AdapterInput.diagnostic(
                DiagnosticLevel.INFO,
                "tongji.flat",
                "按排课服务格式解析 ${classified.flat.size} 条记录。",
            )
        } else {
            diagnostics += AdapterInput.diagnostic(
                DiagnosticLevel.ERROR,
                "tongji.schedule.missing",
                "没有找到课表数据：需要 `data.selectedCourses[].course.times[]`（个人课表）或含 `weekState/dayOfWeek` 的数组。",
            )
        }

        val sessionCount = courses.sumOf { it.sessions.size }
        if (courses.isNotEmpty()) {
            diagnostics += AdapterInput.diagnostic(
                DiagnosticLevel.INFO,
                "tongji.summary",
                "导入 ${courses.size} 门课程 / $sessionCount 条上课安排" +
                    "（学期 ${term.name.ifEmpty { term.id }}，共 ${term.totalWeeks} 教学周）。",
            )
        }

        // The reference sorts *before* callers index into the list, and this app's
        // `sessionsByCourse` is keyed by that same index — so the sort has to happen first,
        // otherwise re-imports would file sessions under the wrong course.
        val sorted = sortCourses(courses)
        val domainCourses = ArrayList<Course>(sorted.size)
        val sessionsByCourse = LinkedHashMap<Int, List<CourseSession>>(sorted.size)
        for ((index, course) in sorted.withIndex()) {
            domainCourses += course.toDomainCourse()
            sessionsByCourse[index] = course.toDomainSessions(index)
        }

        return Imported(
            courses = domainCourses,
            sessionsByCourse = sessionsByCourse,
            // Our `warnings` is documented as "non-fatal problems worth surfacing", so the
            // reference's Info-level progress notes stay in `diagnostics` only.
            warnings = diagnostics.filter { it.level != DiagnosticLevel.INFO }.map { it.message },
            diagnostics = diagnostics.toList(),
            term = term,
            calendarId = classified.calendarId,
            calendarName = classified.calendarName,
            sources = classified.sources.toList(),
        )
    }

    // -------------------------------------------------------- 参考实现的静态方法

    /** 从 `value`（"课程(代码) 教师(工号) [周次] 教室"）里解析教师。 */
    fun parseTeachersFromValue(value: String?): List<String> {
        val result = mutableListOf<String>()
        if (value.isNullOrEmpty()) return result

        for (match in TEACHER_RE.findAll(value)) {
            val name = match.groupValues[1]
            val code = match.groupValues[2]
            if (name.isNotEmpty() && code.isNotEmpty()) result += "$name($code)"
        }

        return result
    }

    /**
     * 选课服务格式 → 课程列表。
     *
     * @param diagnostics the reference appends a single `tongji.noSchedule` note here when
     *   courses without any slot were skipped; the list is mutated in place like theirs.
     */
    fun buildCoursesFromSelected(
        selected: List<JsonElement>,
        diagnostics: MutableList<Diagnostic> = mutableListOf(),
    ): List<TongjiCourse> {
        val courses = mutableListOf<TongjiCourse>()
        var skipped = 0

        for (entry in selected) {
            val entryObject = entry as? JsonObject ?: continue
            val course = prop(entryObject, "course") as? JsonObject ?: continue

            val times = elements(prop(course, "times")).filterIsInstance<JsonObject>()
            if (times.isEmpty()) {
                // 军训等没有排课时段的课程不进课表
                skipped += 1
                continue
            }

            val courseCode = toStr(prop(course, "courseCode"))
            val classCode = toStr(prop(course, "teachClassCode"))
            val id = toStr(prop(course, "teachClassId"))
                ?: classCode
                ?: courseCode
                ?: "course-${courses.size}"
            val name = toStr(prop(course, "courseName")) ?: "(未知课程)"

            val teacherOrder = mutableListOf<String>()
            val teacherSeen = HashSet<String>()

            // 同一格（同天 + 同起止节次 + 同教室）可能有多条 times：
            // 典型如「按周次换老师」的课程（weeks=[10] / [11] / [1,2,3,4,13,14,15,16] …），
            // 必须合并成一块、周次取并集，否则课表上会出现一堆完全重叠的色块。
            val sessions = mutableListOf<TongjiSession>()
            val sessionIndex = HashMap<String, Int>()

            for (time in times) {
                val day = toInt(prop(time, "dayOfWeek"))
                val startSlot = toInt(prop(time, "timeStart"))
                val endSlot = toInt(prop(time, "timeEnd"))
                if (day == null || startSlot == null || endSlot == null) continue
                if (day < 1 || day > 7) continue
                val weekday = weekdayOf(day) ?: continue

                for (teacher in parseTeachersFromValue(
                    toStr(prop(time, "value")) ?: toStr(prop(time, "newValue")),
                )) {
                    if (teacherSeen.add(teacher)) teacherOrder += teacher
                }

                val single = toStr(prop(time, "teacherCodeI18n"))
                val teacherCode = toStr(prop(time, "teacherCode"))
                if (single != null && teacherCode != null) {
                    val withCode = "$single($teacherCode)"
                    if (teacherSeen.add(withCode)) teacherOrder += withCode
                } else if (single != null) {
                    if (teacherSeen.add(single)) teacherOrder += single
                }

                val weeksElement = prop(time, "weeks")
                val weekList = AdapterInput.asArray(weeksElement)
                val weeks = if (weekList != null && weekList.isNotEmpty()) {
                    Weeks.fromWeeks(weekList.map { toInt(it) ?: 0 })
                } else {
                    Weeks.fullMask(FALLBACK_TOTAL_WEEKS)
                }

                val room = toStr(prop(time, "roomIdI18n"))
                val key = "$day-$startSlot-$endSlot-${room ?: ""}"

                val existing = sessionIndex[key]
                if (existing != null) {
                    val prior = sessions[existing]
                    sessions[existing] = prior.copy(
                        weeks = prior.weeks + weeks,
                        rawWeeks = mergeRawWeeks(prior.rawWeeks, rawWeeksOf(weeksElement)),
                    )
                    continue
                }

                sessionIndex[key] = sessions.size
                sessions += TongjiSession(
                    id = "$id-$key",
                    day = weekday,
                    startSlot = startSlot,
                    endSlot = endSlot,
                    weeks = weeks,
                    room = room,
                    rawWeeks = rawWeeksOf(weeksElement),
                )
            }

            if (sessions.isEmpty()) {
                skipped += 1
                continue
            }

            courses += TongjiCourse(
                id = id,
                name = name,
                teachers = teacherOrder.toList(),
                sessions = sortedSessions(sessions),
                courseCode = courseCode,
                teachingClassCode = classCode,
            )
        }

        if (skipped > 0) {
            diagnostics += AdapterInput.diagnostic(
                DiagnosticLevel.INFO,
                "tongji.noSchedule",
                "有 $skipped 门课没有排课时段（如军训），已跳过。",
            )
        }

        return sortCourses(courses)
    }

    /**
     * 报表服务格式（`data[].timeTableList[]`，课表页真正调的那条接口）→ 课程列表。
     *
     * 与 [buildCoursesFromSelected] 的差异只有「包法」：课程在数组顶层而不是
     * `selectedCourses[].course`，排课数组叫 `timeTableList` 而不是 `times`；
     * `dayOfWeek` / `timeStart` / `timeEnd` / `weeks` 数组的语义完全一致。
     *
     * 教室：优先 `roomIdI18n`（如「测试楼201」），空则退 `roomLable`（线上课堂 / 操场这类
     * 没有教室编号的场地）—— 实测 27 条里 23 条是前者、4 条只有后者。
     */
    fun buildCoursesFromReport(
        items: List<JsonElement>,
        diagnostics: MutableList<Diagnostic> = mutableListOf(),
    ): List<TongjiCourse> {
        val courses = mutableListOf<TongjiCourse>()
        var skipped = 0

        for (item in items) {
            val itemObject = item as? JsonObject ?: continue

            val times = elements(prop(itemObject, "timeTableList")).filterIsInstance<JsonObject>()
            if (times.isEmpty()) {
                // 军训等没有排课时段的课程不进课表
                skipped += 1
                continue
            }

            val courseCode = toStr(prop(itemObject, "courseCode"))
            val classCode = toStr(prop(itemObject, "classCode"))
            val id = toStr(prop(itemObject, "teachingClassId"))
                ?: classCode
                ?: courseCode
                ?: "course-${courses.size}"
            val name = toStr(prop(itemObject, "courseName")) ?: "(未知课程)"

            val teacherOrder = mutableListOf<String>()
            val teacherSeen = HashSet<String>()

            // 同一格（同天 + 同起止节次 + 同教室）合并、周次取并集 —— 与选课服务那条路同规则
            val sessions = mutableListOf<TongjiSession>()
            val sessionIndex = HashMap<String, Int>()

            for (time in times) {
                val day = toInt(prop(time, "dayOfWeek"))
                val startSlot = toInt(prop(time, "timeStart"))
                val endSlot = toInt(prop(time, "timeEnd"))
                if (day == null || startSlot == null || endSlot == null) continue
                if (day < 1 || day > 7) continue
                val weekday = weekdayOf(day) ?: continue

                // timeTableList[].teacherName 就是「姓名(工号)」；拿不到时退到 teacherCode 拼一个
                addTeacher(
                    teacherOrder,
                    teacherSeen,
                    toStr(prop(time, "teacherName")),
                    toStr(prop(time, "teacherCode")),
                )

                val weeksElement = prop(time, "weeks")
                val weekList = AdapterInput.asArray(weeksElement)
                val weeks = if (weekList != null && weekList.isNotEmpty()) {
                    Weeks.fromWeeks(weekList.map { toInt(it) ?: 0 })
                } else {
                    Weeks.fullMask(FALLBACK_TOTAL_WEEKS)
                }

                val room = firstNonEmpty(
                    toStr(prop(time, "roomIdI18n")),
                    toStr(prop(time, "roomLable")),
                    toStr(prop(time, "classRoomName")),
                )
                val key = "$day-$startSlot-$endSlot-${room ?: ""}"

                val existing = sessionIndex[key]
                if (existing != null) {
                    val prior = sessions[existing]
                    sessions[existing] = prior.copy(
                        weeks = prior.weeks + weeks,
                        rawWeeks = mergeRawWeeks(prior.rawWeeks, rawWeeksOf(weeksElement)),
                    )
                    continue
                }

                sessionIndex[key] = sessions.size
                sessions += TongjiSession(
                    id = "$id-$key",
                    day = weekday,
                    startSlot = startSlot,
                    endSlot = endSlot,
                    weeks = weeks,
                    room = room,
                    rawWeeks = rawWeeksOf(weeksElement),
                )
            }

            if (sessions.isEmpty()) {
                skipped += 1
                continue
            }

            // 兜底教师：课程级的 teacherName 是「张三,李四」这种纯名字列表（通常不带工号）
            if (teacherOrder.isEmpty()) {
                for (teacher in splitNames(toStr(prop(itemObject, "teacherName")))) {
                    if (teacherSeen.add(teacher)) teacherOrder += teacher
                }
            }

            courses += TongjiCourse(
                id = id,
                name = name,
                teachers = teacherOrder.toList(),
                sessions = sortedSessions(sessions),
                courseCode = courseCode,
                teachingClassCode = classCode,
                faculty = null,
                campus = firstNonEmpty(
                    toStr(prop(itemObject, "campusI18n")),
                    toStr(prop(itemObject, "campus")),
                ),
                // The reference leaves these four unmapped, but the payload carries them and
                // they are what the course detail sheet shows — carrying them is free and
                // avoids a detail page of em dashes.
                className = toStr(prop(itemObject, "className")),
                credits = toDouble(prop(itemObject, "credits")),
                assessmentMode = firstNonEmpty(
                    toStr(prop(itemObject, "assessmentModeI18n")),
                    toStr(prop(itemObject, "assessmentMode")),
                ),
                teachingWay = firstNonEmpty(
                    toStr(prop(itemObject, "teachingWayI18n")),
                    toStr(prop(itemObject, "teachingWay")),
                ),
            )
        }

        if (skipped > 0) {
            diagnostics += AdapterInput.diagnostic(
                DiagnosticLevel.INFO,
                "tongji.noSchedule",
                "有 $skipped 门课没有排课时段（如军训），已跳过。",
            )
        }

        return sortCourses(courses)
    }

    /**
     * 排课服务扁平格式 → 课程列表（兼容保留）。
     *
     * 与另外两条路的差别：周次是 `weekState` **掩码**（bit0 = 第 1 周）而不是数组，
     * 教师来自 `value` 文本或 `teacherCodes` 数组，课程级字段叫 `facultyI18n` / `campusI18n`。
     */
    fun buildCoursesFromFlat(items: List<JsonElement>): List<TongjiCourse> {
        val byId = HashMap<String, Int>()
        val accumulated = mutableListOf<MutableCourse>()

        for (item in items) {
            val row = item as? JsonObject ?: continue

            val day = toInt(prop(row, "dayOfWeek"))
            val startSlot = toInt(prop(row, "timeStart"))
            val endSlot = toInt(prop(row, "timeEnd"))
            val weekState = toInt(prop(row, "weekState"))
            if (day == null || startSlot == null || endSlot == null || weekState == null) continue
            if (day < 1 || day > 7) continue
            val weekday = weekdayOf(day) ?: continue

            val classCode = toStr(prop(row, "code"))
            val courseCode = toStr(prop(row, "courseCode"))
            val id = toStr(prop(row, "teachingClassId"))
                ?: classCode
                ?: "${courseCode ?: "unknown"}-$day-$startSlot"
            val name = toStr(prop(row, "courseName")) ?: "(未知课程)"
            val room = toStr(prop(row, "roomName"))

            val position = byId.getOrPut(id) {
                val fromValue = parseTeachersFromValue(
                    toStr(prop(row, "value")) ?: toStr(prop(row, "newValue")),
                )
                val codes = elements(AdapterInput.asArray(prop(row, "teacherCodes"))).map { jsText(it) }
                val index = accumulated.size
                accumulated += MutableCourse(
                    id = id,
                    name = name,
                    courseCode = courseCode,
                    teachingClassCode = classCode,
                    teachers = if (fromValue.isNotEmpty()) fromValue else codes,
                    faculty = toStr(prop(row, "facultyI18n")),
                    campus = toStr(prop(row, "campusI18n")),
                )
                index
            }

            val course = accumulated[position]
            // `unchecked((uint)weeks.Value)`: the mask is unsigned 32-bit, so a value above
            // Int.MAX_VALUE arrives negative and must be widened without sign extension.
            val mask = weekState.toLong() and 0xFFFFFFFFL
            val session = TongjiSession(
                id = "$id-$day-$startSlot-$endSlot-$mask",
                day = weekday,
                startSlot = startSlot,
                endSlot = endSlot,
                weeks = WeekPattern.fromBits(mask),
                room = room,
                rawWeeks = "weekState=$weekState",
            )
            if (course.sessions.none { it.id == session.id }) course.sessions += session
        }

        return sortCourses(
            accumulated.map { course ->
                TongjiCourse(
                    id = course.id,
                    name = course.name,
                    teachers = course.teachers,
                    sessions = sortedSessions(course.sessions),
                    courseCode = course.courseCode,
                    teachingClassCode = course.teachingClassCode,
                    faculty = course.faculty,
                    campus = course.campus,
                )
            },
        )
    }

    // ------------------------------------------------------------------ 归类

    /** 扁平格式累积中的课程（参考实现里 `Course` 是不可变 record，所以先攒 sessions）。 */
    private class MutableCourse(
        val id: String,
        val name: String,
        val courseCode: String?,
        val teachingClassCode: String?,
        val teachers: List<String>,
        val faculty: String?,
        val campus: String?,
    ) {
        val sessions = mutableListOf<TongjiSession>()
    }

    /** 分类结果：一个输入里可能同时含个人课表 / 报表课表 / 排课扁平表 / 校历。 */
    private class Classified {
        val selected = mutableListOf<JsonObject>()

        /** 报表服务（`reportManagement/findStudentTimetab` 等）返回的课程数组。 */
        val report = mutableListOf<JsonObject>()

        val flat = mutableListOf<JsonObject>()
        val calendar = mutableListOf<JsonObject>()
        var calendarId: String? = null
        var calendarName: String? = null
        val sources = mutableListOf<String>()
    }

    /** 把输入里的文本片段按「选课服务 / 报表课表 / 排课扁平表 / 校历」归类。 */
    private fun classify(input: ImportInput): Classified {
        val result = Classified()

        for ((label, text) in AdapterInput.texts(input)) {
            val root = AdapterInput.tryParseJson(text) ?: continue
            val raw = AdapterInput.unwrapData(root)

            // 1) 选课服务：{ data: { calendarId, selectedCourses: [...] } }
            val selected = AdapterInput.asArray(prop(raw, "selectedCourses"))
            if (selected != null && selected.isNotEmpty()) {
                // 先并入累积列表、再取 calendarId（container 优先），最后遍历**整个累积列表**
                // 找课程自带的 calendarId/calendarName —— 与参考实现的顺序逐条一致。
                for (entry in selected) {
                    if (entry is JsonObject) result.selected += entry
                }

                if (result.calendarId == null) result.calendarId = toStr(prop(raw, "calendarId"))
                for (entry in result.selected) {
                    val course = prop(entry, "course")
                    if (result.calendarId == null) result.calendarId = toStr(prop(course, "calendarId"))
                    if (result.calendarName == null) {
                        result.calendarName = toStr(prop(course, "calendarName"))
                    }
                }

                result.sources += "$label:已选课程 ${selected.size} 门"
                continue
            }

            // 2) 报表服务：课表页真正调的那条接口。
            val reportItems = collectReportItems(raw)
            if (reportItems.isNotEmpty()) {
                result.report += reportItems
                result.sources += "$label:报表课表 ${reportItems.size} 门"
                continue
            }

            val list = AdapterInput.asArray(raw) ?: continue
            val objects = list.filterIsInstance<JsonObject>()

            val scheduleItems = objects.filter { has(it, "weekState") && has(it, "dayOfWeek") }
            if (scheduleItems.isNotEmpty()) {
                result.flat += scheduleItems
                result.sources += "$label:课表 ${scheduleItems.size} 条"
                continue
            }

            val calendarTerms = objects.filter {
                has(it, "noWeekendWorkTimes") || (has(it, "beginDay") && has(it, "weekNum"))
            }
            if (calendarTerms.isNotEmpty()) {
                result.calendar += calendarTerms
                result.sources += "$label:校历 ${calendarTerms.size} 个学期"
            }
        }

        return result
    }

    /**
     * 从报表服务的响应里挑出「带排课时段的课程项」。
     *
     * 认两种容器：`data` 直接是数组（本科 `findStudentTimetab`，已实测），
     * 或 `data.list`（研究生 `findSchoolTimetab2`，按前端源码推断，未实测）。
     * 判据是元素里有没有 `timeTableList` 数组 —— 它把报表格式与排课服务的扁平表区分开。
     */
    private fun collectReportItems(raw: JsonElement?): List<JsonObject> {
        val list = AdapterInput.asArray(raw)
            ?: AdapterInput.asArray(prop(raw, "list"))
            ?: return emptyList()
        return list.filterIsInstance<JsonObject>()
            .filter { AdapterInput.asArray(prop(it, "timeTableList")) != null }
    }

    // ------------------------------------------------------------------ 学期

    private fun pickTerm(calendar: List<JsonObject>, termId: String?): JsonObject? {
        if (calendar.isEmpty()) return null

        if (termId != null) {
            for (term in calendar) {
                if (termKey(term) == termId) return term
            }
        }

        // `currentTermFlag` / `nextTermFlag` are JSON booleans in the real payload; the
        // reference compares `ValueKind == True`, so anything else (including "true" as a
        // string) does not count.
        for (term in calendar) {
            if (isJsonTrue(prop(term, "currentTermFlag"))) return term
        }

        for (term in calendar) {
            if (isJsonTrue(prop(term, "nextTermFlag"))) return term
        }

        return calendar[0]
    }

    private fun isJsonTrue(value: JsonElement?): Boolean =
        value is JsonPrimitive && !value.isString && value.content == "true"

    private fun termKey(term: JsonObject): String {
        val id = prop(term, "id")
        val numeric = toInt(id)
        return numeric?.toString() ?: jsText(id)
    }

    private fun toSlotList(term: JsonObject): List<Slot> {
        val raw = AdapterInput.asArray(prop(term, "noWeekendWorkTimes"))
            ?: AdapterInput.asArray(prop(term, "weekendWorkTimes"))
        val slots = mutableListOf<Slot>()
        for (entry in raw.orEmpty()) {
            val slot = entry as? JsonObject ?: continue
            val index = toInt(prop(slot, "classNode"))
            val begin = prop(slot, "beginTime")
            val end = prop(slot, "endTime")
            if (index == null || index <= 0) continue
            slots += Slot(
                index,
                if (begin is JsonPrimitive && begin.isString) begin.content else "",
                if (end is JsonPrimitive && end.isString) end.content else "",
            )
        }

        return if (slots.isNotEmpty()) {
            TimetableDefaults.slotsFromList(slots)
        } else {
            TimetableDefaults.makeDefaultSlots()
        }
    }

    private fun resolveStartDate(term: JsonObject): String? {
        // beginDay 是毫秒时间戳（如 1789315200000），必须用 long 接：int 会溢出成「缺少 beginDay」。
        val beginDay = toLong(prop(term, "beginDay")) ?: return null

        // TS 那边越界会得到 Invalid Date（"NaN-NaN-NaN"）；移植版按「缺少 beginDay」降级更安全。
        val iso = TongjiTime.msToIsoDate(beginDay) ?: return null

        val monday = TongjiTime.mondayOf(iso) ?: return null
        val teachingWeekStart = toInt(prop(term, "teachingWeekStart")) ?: 1
        return if (teachingWeekStart > 1) {
            TongjiTime.addDays(monday, (teachingWeekStart - 1) * 7)
        } else {
            monday
        }
    }

    /** 学期解析优先级：显式校历 JSON > 内置学期表（按 calendarId）> 默认 16 周。 */
    private fun buildTerm(
        calendarTerm: JsonObject?,
        calendarId: String?,
        calendarName: String?,
        diagnostics: MutableList<Diagnostic>,
    ): Term {
        if (calendarTerm != null) {
            val startDate = resolveStartDate(calendarTerm)
            val teachingWeekEnd = toInt(prop(calendarTerm, "teachingWeekEnd"))
            if (startDate == null) {
                diagnostics += AdapterInput.diagnostic(
                    DiagnosticLevel.WARN,
                    "tongji.term.startDate",
                    "校历缺少 beginDay，无法计算当前教学周。",
                )
            }

            val id = toInt(prop(calendarTerm, "id"))
            return Term(
                id = id?.toString() ?: "unknown",
                name = toStr(prop(calendarTerm, "fullName")) ?: "",
                // DateTime.Now.Year in the reference — the reference reads the host clock here,
                // and so do we; it only ever fills a label, never a date.
                year = toInt(prop(calendarTerm, "year")) ?: LocalDate.now().year,
                termNo = toInt(prop(calendarTerm, "term")) ?: 1,
                totalWeeks = if (teachingWeekEnd != null && teachingWeekEnd > 0) {
                    teachingWeekEnd
                } else {
                    toInt(prop(calendarTerm, "weekNum")) ?: 16
                },
                slots = toSlotList(calendarTerm),
                startDate = startDate,
            )
        }

        val preset = TongjiTerms.findPreset(calendarId)
        if (preset != null) return TongjiTerms.termFromPreset(preset)

        diagnostics += AdapterInput.diagnostic(
            DiagnosticLevel.WARN,
            "tongji.term.unknown",
            "学期 ${calendarId ?: "未知"} 不在内置学期表里：已按 16 教学周 + 内置节次时间解析，" +
                "挂件不会显示\"当前第几周\"（若能一并导入校历响应，即可从 beginDay 补上开学日期）。",
        )

        return Term(
            id = calendarId ?: "unknown",
            name = calendarName ?: "",
            year = LocalDate.now().year,
            termNo = 1,
            totalWeeks = 16,
            slots = TimetableDefaults.makeDefaultSlots(),
        )
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 登记一个教师：文本里**已经带工号**（"张三(12345)"）就原样收下，否则用同一行的
     * `teacherCode` 拼成「姓名(工号)」（工号是教师指纹的一半，能拼就拼）。
     *
     * 不能无条件拼：那样会得到 `姓名(工号)(工号)`。这道判断也不能只靠 [TEACHER_RE] ——
     * 它只认「汉字姓名」，名字里带字母/数字时匹配不到，于是「已经有工号」会被漏判
     * （实测数据里的脱敏姓名如 `教师M(10008)` 就踩到了这一点）。
     */
    private fun addTeacher(
        order: MutableList<String>,
        seen: MutableSet<String>,
        nameWithCode: String?,
        code: String?,
    ) {
        if (nameWithCode.isNullOrBlank()) return
        val text = nameWithCode.trim()

        val formatted = if (TRAILING_CODE_RE.containsMatchIn(text) || code.isNullOrEmpty()) {
            text
        } else {
            "$text($code)"
        }
        if (seen.add(formatted)) order += formatted
    }

    /** 把「张三,李四 王五」这类纯名字列表切开（不带工号时的兜底）。 */
    private fun splitNames(value: String?): List<String> {
        val result = mutableListOf<String>()
        if (value.isNullOrEmpty()) return result

        val separators = charArrayOf(',', '，', ' ', '、', ';', '；')
        for (part in value.split(*separators)) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) result += trimmed
        }

        return result
    }

    private fun firstNonEmpty(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun sortedSessions(sessions: List<TongjiSession>): List<TongjiSession> =
        sessions.sortedWith(compareBy({ it.day.value }, { it.startSlot }))

    /**
     * 课程序列排序：参考实现用 `OrderBy(Name, InvariantCulture).ThenBy(Id, InvariantCulture)`
     * （TS 侧是 `localeCompare(name, 'zh-Hans-CN')`）。
     *
     * Kotlin's [String] comparison is UTF-16 code-unit order, which the reference itself
     * notes it cannot match either (their `InvariantGlobalization` build falls back from ICU
     * collation too). The practical consequence is only the order of same-named courses;
     * every lookup in this app and in the ported tests is by name, not by position.
     */
    private fun sortCourses(courses: List<TongjiCourse>): List<TongjiCourse> =
        courses.sortedWith(compareBy({ it.name }, { it.id }))

    /**
     * The weeks field of this entry, verbatim, for `CourseSession.rawWeeks`.
     *
     * Not in the reference (their `Session` has no such field): this app keeps the server's
     * own week data on every session so an import problem can be diagnosed without
     * re-fetching. A merged cell concatenates the entries it merged, which is exactly the
     * information that makes the union auditable.
     */
    private fun rawWeeksOf(weeksElement: JsonElement?): String? =
        if (weeksElement is JsonArray) weeksElement.toString() else null

    private fun mergeRawWeeks(existing: String?, incoming: String?): String? = when {
        existing == null -> incoming
        incoming == null -> existing
        existing == incoming -> existing
        else -> "$existing | $incoming"
    }

    // ── JSON 取值小工具（参考实现里每个适配器各有一份 toInt/toStr，这里镜像同样的组织方式）──

    /** 按名字取元素；不是对象或字段不存在时返回 null（对应他们的 `Prop`）。 */
    private fun prop(parent: JsonElement?, name: String): JsonElement? =
        (parent as? JsonObject)?.get(name)

    /** 字段是否存在（显式 `null` 也算存在，与 `TryGetProperty` 一致）。 */
    private fun has(value: JsonElement?, name: String): Boolean =
        value is JsonObject && value.containsKey(name)

    /** 数组元素；不是数组时为空（对应他们的 `Elements`）。 */
    private fun elements(value: JsonElement?): List<JsonElement> =
        (value as? JsonArray)?.toList() ?: emptyList()

    /** 按名字取整数（字符串数字也接受）：用于节次/星期/掩码等小整数。 */
    private fun toInt(value: JsonElement?): Int? {
        if (value == null || value is JsonNull) return null
        if (value !is JsonPrimitive) return null

        if (!value.isString) {
            val number = value.content.toDoubleOrNull() ?: return null
            if (!number.isFinite()) return null
            val truncated = truncate(number)
            if (truncated > Int.MAX_VALUE.toDouble() || truncated < Int.MIN_VALUE.toDouble()) return null
            return truncated.toInt()
        }

        val text = value.content.trim()
        if (!INTEGER_RE.matches(text)) return null
        return text.toIntOrNull()
    }

    /**
     * 按名字取 64 位整数：`beginDay` 这类**毫秒时间戳**远超 int 范围，必须走这条。
     */
    private fun toLong(value: JsonElement?): Long? {
        if (value == null || value is JsonNull) return null
        if (value !is JsonPrimitive) return null

        if (!value.isString) {
            value.content.toLongOrNull()?.let { return it }
            val number = value.content.toDoubleOrNull() ?: return null
            if (!number.isFinite()) return null
            val truncated = truncate(number)
            if (truncated > Long.MAX_VALUE.toDouble() || truncated < Long.MIN_VALUE.toDouble()) return null
            return truncated.toLong()
        }

        val text = value.content.trim()
        if (!INTEGER_RE.matches(text)) return null
        return text.toLongOrNull()
    }

    /**
     * Reads a decimal such as `credits: 4.0`. Parsed from the literal text rather than
     * requiring a JSON number, because some fields arrive quoted.
     */
    private fun toDouble(value: JsonElement?): Double? {
        if (value == null || value is JsonNull) return null
        if (value !is JsonPrimitive) return null
        val text = value.content.trim()
        if (text == "true" || text == "false") return null
        return text.toDoubleOrNull()
    }

    private fun toStr(value: JsonElement?): String? {
        if (value == null || value is JsonNull) return null
        if (value !is JsonPrimitive) return null

        if (value.isString) {
            val text = value.content
            return if (text.isBlank()) null else text.trim()
        }

        // JsonValueKind.True / False → the reference's `ToStr` returns null for those.
        if (value.content == "true" || value.content == "false") return null

        // Numbers keep their literal text, i.e. `teachClassId: 1111111124960363` → "1111111124960363".
        return value.content
    }

    /**
     * JS `String(value)`-like rendering, used for id/name fallbacks.
     *
     * Objects and arrays print as compact JSON here, where `System.Text.Json`'s
     * `JsonElement.ToString()` echoes the raw source text; nothing in the adapter depends on
     * that difference (it only ever reads strings from `teacherCodes`, and an id).
     */
    private fun jsText(value: JsonElement?): String = when {
        value == null -> "undefined"
        value is JsonNull -> "null"
        value is JsonPrimitive -> value.content
        else -> value.toString()
    }

    /** `Math.Truncate`: towards zero, so -1.5 → -1. */
    private fun truncate(number: Double): Double =
        if (number < 0) Math.ceil(number) else Math.floor(number)
}
