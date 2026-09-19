package com.ranorac.tjtimetable.export

import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.PeriodSchedule
import com.ranorac.tjtimetable.domain.ScheduleAdjustmentSet
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.WeekPattern
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * `.ics` (RFC 5545) export and import for one semester's timetable.
 *
 * **Why this module exists.** The app's primary import path goes through
 * 同济大学开放平台 (`api.tongji.edu.cn`), and that platform is not open to individual
 * students: per its own 学生申请指南 a 学生 has to have a supervising teacher apply
 * through the 教职工 process. A student who cannot arrange that therefore needs an
 * import path that asks for **no credentials at all**, and a calendar file is the one
 * such format that every phone, mail client and 教务 export already speaks. Export
 * exists for the mirror-image reason: it is the only way to get a timetable *out* of
 * this app and into a tool that will never see 同济's API.
 *
 * **Purity.** Nothing here touches Android, a database or the clock, so the whole
 * round trip is covered by plain JVM tests. [export] is a pure function of its
 * arguments: two exports of the same input are byte-identical, which is what makes
 * "export, re-import, diff" a usable debugging technique and keeps `UID`s stable.
 *
 * ## The contract that matters: `parse(export(x)) == x`
 *
 * | exported as                          | comes back as                          |
 * |--------------------------------------|----------------------------------------|
 * | one `VEVENT` per maximal week run     | one [CourseSession] per slot, with the runs merged back into one [WeekPattern] |
 * | `RRULE … INTERVAL=2` for 单/双周      | the original odd/even week set         |
 * | `DTSTART`/`DTEND` floating wall-clock | the same weekday and 节次 range        |
 * | `EXDATE` for a 节假日 inside a run    | those weeks removed from the pattern   |
 *
 * ## What an `.ics` cannot carry
 *
 *  - **节次** are not a calendar concept, so the file records the 作息表's clock times
 *    and re-import maps them back through a [PeriodSchedule] (see [parseWithSchedule]).
 *  - **调休补课** has no iCalendar equivalent: a class displaced to another weekday
 *    stays on its rule weekday in the file. The app re-derives 调休 from the school
 *    calendar on re-import, so nothing is lost inside the app.
 *  - **课程代码 / 学分 / 考核方式** and the split between 校区 / 教学楼 / 教室 are not
 *    representable; the three location parts are written as one `LOCATION` string and
 *    come back as [CourseSession.room].
 *
 * Deliberately **not** emitted: `DTSTAMP`. RFC 5545 asks for it, but it is the current
 * time, and emitting it would make the output non-deterministic for no benefit —
 * nothing that reads a timetable file needs to know when it was written.
 */
object TimetableIcs {

    /**
     * `PRODID` of every export. RFC 5545 requires the value to be globally unique,
     * and the `-//owner//product//language` shape is the conventional one.
     */
    const val PRODID: String = "-//TJtimetable//RanoraC//CN"

    /** Result of parsing an `.ics` file. */
    data class Parsed(
        val term: TermCalendar,
        val courses: List<Course>,
        /** Sessions keyed by the INDEX into [courses]. */
        val sessionsByCourse: Map<Int, List<CourseSession>>,
        val warnings: List<String>,
    ) {
        val sessionCount: Int get() = sessionsByCourse.values.sumOf { it.size }
    }

    // ------------------------------------------------------------------ export

    /**
     * Serialises the timetable to an RFC 5545 iCalendar document.
     *
     * One `VEVENT` is written per **maximal run** of a session's weeks, because a
     * weekly `RRULE` can express a uniform cadence but not an arbitrary week set:
     * 周次 `1-8,10-16` has to become two events, and a 单周 session becomes one event
     * with `INTERVAL=2` anchored on its first odd week. A session whose weeks are empty
     * is skipped — it teaches nothing, and an event with no weeks would be a class the
     * grid never draws.
     *
     * Sessions are matched to [courses] by [CourseSession.courseId] against
     * [Course.id], falling back to the *index* convention that `TongjiImport` uses
     * (`courseId == index`) for rows that have not been persisted yet. A session whose
     * course cannot be found is skipped: an event needs a `SUMMARY` to be useful.
     *
     * @param schedule the 作息表 that turns 节次 into clock times; defaults to
     *   同济's published one.
     * @param adjustments 校历 overrides. A date this session does not meet on — a
     *   节假日, 寒暑假, 停课日 — is written as an `EXDATE`, otherwise every calendar app
     *   would happily draw a class the timetable grid hides. A 补课日 is *not* cancelled:
     *   the class does happen that week, just on another day, and cancelling the date
     *   would lose the week instead of merely misplacing it.
     * @return a CRLF-terminated document, folded at 75 octets per line.
     */
    fun export(
        term: TermCalendar,
        courses: List<Course>,
        sessions: List<CourseSession>,
        schedule: PeriodSchedule = PeriodSchedule.TONGJI,
        adjustments: ScheduleAdjustmentSet = ScheduleAdjustmentSet.EMPTY,
    ): String {
        val lines = ArrayList<String>(16)

        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:$PRODID"
        lines += "CALSCALE:GREGORIAN"
        lines += "X-WR-CALNAME:${escapeText(term.name)}"
        // X-TJ-* is this app's own semester header. Every other calendar client
        // ignores it, and it is what lets a re-import restore the semester exactly
        // instead of guessing it back from the first and last event.
        lines += "X-TJ-CALENDAR-ID:${escapeText(term.calendarId)}"
        lines += "X-TJ-YEAR:${term.year}"
        lines += "X-TJ-TERM:${term.term}"
        lines += "X-TJ-FIRST-WEEK:${DATE_FORMAT.format(term.firstWeekStart)}"
        lines += "X-TJ-TOTAL-WEEKS:${term.totalWeeks}"
        lines += "X-TJ-WEEK-START:${rfcDay(term.weekStartDay)}"

        val emittedUids = HashSet<String>()
        for ((course, courseSessions) in linkSessions(courses, sessions)) {
            for (session in courseSessions) {
                appendEvents(lines, course, session, term, schedule, adjustments, emittedUids)
            }
        }

        lines += "END:VCALENDAR"
        // The space that starts a continuation line is part of that line, so the
        // folder counts it against the same 75 octets.
        return lines.joinToString(separator = CRLF, postfix = CRLF) { foldLine(it) }
    }

    /** One `VEVENT` per maximal run of [session]'s weeks. */
    private fun appendEvents(
        out: MutableList<String>,
        course: Course,
        session: CourseSession,
        term: TermCalendar,
        schedule: PeriodSchedule,
        adjustments: ScheduleAdjustmentSet,
        emittedUids: MutableSet<String>,
    ) {
        if (session.weeks.isEmpty) return

        val (startTime, endTime) = classSpan(session, schedule)
        val location = locationOf(session)
        val description = descriptionOf(course, session)

        for (run in runsOf(session.weeks)) {
            val uid = uidOf(course, session, run, location)
            // Two sessions of one course that agree on weekday, 节次 and run would
            // otherwise produce two identical events under one UID, which some
            // importers reject as a malformed calendar.
            if (!emittedUids.add(uid)) continue

            val date = term.dateOf(run.firstWeek, session.dayOfWeek)

            out += "BEGIN:VEVENT"
            out += "UID:$uid"
            out += "DTSTART:${stamp(LocalDateTime.of(date, startTime))}"
            out += "DTEND:${stamp(LocalDateTime.of(date, endTime))}"
            out += "SUMMARY:${escapeText(course.name)}"
            location?.let { out += "LOCATION:${escapeText(it)}" }
            out += "DESCRIPTION:${escapeText(description)}"
            out += "RRULE:${rruleOf(session, run)}"

            // A date the school calendar has cancelled for this session. Written with
            // the same floating time as DTSTART, because an EXDATE that does not state
            // the same instant as the occurrence it cancels cancels nothing.
            val cancelled = run.weeks
                .map { term.dateOf(it, session.dayOfWeek) }
                .filterNot { adjustments.isTeachingDay(it) }
            if (cancelled.isNotEmpty()) {
                out += "EXDATE:" + cancelled.joinToString(",") {
                    stamp(LocalDateTime.of(it, startTime))
                }
            }
            out += "END:VEVENT"
        }
    }

    /**
     * Pairs each course with its sessions, tolerating both id conventions: a
     * persisted timetable matches on [Course.id], while rows straight out of
     * `TongjiImport` carry the course's *index* as their `courseId`.
     */
    private fun linkSessions(
        courses: List<Course>,
        sessions: List<CourseSession>,
    ): List<Pair<Course, List<CourseSession>>> {
        if (courses.isEmpty() || sessions.isEmpty()) return emptyList()

        val indexById = HashMap<Long, Int>(courses.size)
        courses.forEachIndexed { index, course -> if (course.id != 0L) indexById[course.id] = index }

        val grouped = HashMap<Int, MutableList<CourseSession>>()
        for (session in sessions) {
            val index = indexById[session.courseId]
                ?: session.courseId.takeIf { it >= 0L && it < courses.size.toLong() }?.toInt()
                ?: continue
            grouped.getOrPut(index) { ArrayList() }.add(session)
        }
        return courses.indices.mapNotNull { index ->
            grouped[index]?.let { courses[index] to it }
        }
    }

    /**
     * Splits a pattern into runs a single weekly rule can describe.
     *
     * For an ordinary pattern these are exactly [WeekPattern.ranges]. A genuine
     * 单周 / 双周 pattern is different: `ranges()` reports every odd week as its own
     * singleton run, and one `COUNT=1` event per week would be both unreadable and
     * useless to a calendar app. Merging runs whose weeks are two apart is what turns
     * 单双周 into `INTERVAL=2` — the shape `CalendarSync.buildRrule` already uses, so
     * the app's calendar and its exported file agree on what 单周 means.
     */
    private fun runsOf(weeks: WeekPattern): List<WeekRun> {
        val numbers = weeks.weekNumbers
        if (numbers.isEmpty()) return emptyList()

        val step = if (weeks.isOddOnly || weeks.isEvenOnly) 2 else 1
        val runs = ArrayList<WeekRun>()
        var first = numbers.first()
        var previous = first
        for (i in 1 until numbers.size) {
            val week = numbers[i]
            if (week - previous != step) {
                runs += WeekRun(first, previous, step)
                first = week
            }
            previous = week
        }
        runs += WeekRun(first, previous, step)
        return runs
    }

    /**
     * `FREQ=WEEKLY;…`, the same shape [com.ranorac.tjtimetable.calendar.buildRrule]
     * produces: `INTERVAL` is written only when it is not the RFC default of 1.
     */
    private fun rruleOf(session: CourseSession, run: WeekRun): String = buildString {
        append("FREQ=WEEKLY;")
        if (run.step > 1) append("INTERVAL=").append(run.step).append(';')
        append("BYDAY=").append(rfcDay(session.dayOfWeek)).append(';')
        append("COUNT=").append(run.count)
    }

    /**
     * A stable, unique, ASCII-only `UID`.
     *
     * Stability is a correctness requirement, not cosmetics: UID is the only thing a
     * calendar app uses to decide whether an imported event is new or an update, so
     * an unstable UID duplicates the student's whole semester on the second import.
     * The identity hash covers the course (name, 教学班, room, so two sessions of one
     * course are distinguishable) and the visible part of the UID states the weekday,
     * 节次 and week run, which is enough to debug a file by eye.
     */
    private fun uidOf(
        course: Course,
        session: CourseSession,
        run: WeekRun,
        location: String?,
    ): String {
        val identity = buildString {
            append(course.name.trim())
            append('\u0000').append(course.teachingClassId?.toString() ?: "")
            append('\u0000').append(course.classCode ?: "")
            append('\u0000').append(location ?: "")
        }
        return buildString {
            append("tj-").append(hex32(fnv1a(identity)))
            append('-').append(rfcDay(session.dayOfWeek).lowercase())
            append(session.startUnit).append('-').append(session.endUnit)
            append("-w").append(run.firstWeek).append('-').append(run.lastWeek)
            append("@tjtimetable.ranorac")
        }
    }

    /**
     * The human-readable note block.
     *
     * `key：value` lines, because re-import reads them back: 教师 becomes
     * [Course.teacher] and 教学班 becomes the grouping key that keeps two 教学班 of
     * the same 课程 apart. 周次 uses [WeekPattern.display], which is both what a
     * student reads in the calendar app and (through `WeekPattern.parse`) a lossless
     * round trip of the odd/even wording.
     */
    private fun descriptionOf(course: Course, session: CourseSession): String = buildString {
        (session.teacher ?: course.teacher)?.takeIf { it.isNotBlank() }?.let {
            append(KEY_TEACHER).append(it).append('\n')
        }
        classLabel(course)?.let { append(KEY_CLASS).append(it).append('\n') }
        course.teachingClassId?.let { append(KEY_CLASS_ID).append(it).append('\n') }
        append(KEY_PERIODS).append(session.startUnit).append('-').append(session.endUnit)
            .append("节").append('\n')
        append(KEY_WEEKS).append(session.weeks.display()).append('\n')
        append(DESCRIPTION_MARKER)
    }

    /** 教学班编号, falling back to the 教学班 id when the code is missing. */
    private fun classLabel(course: Course): String? =
        course.classCode?.takeIf { it.isNotBlank() } ?: course.teachingClassId?.toString()

    /** `四平路校区 教学南楼 305`, or null when the session carries no place at all. */
    private fun locationOf(session: CourseSession): String? =
        listOfNotNull(session.campus, session.building, session.room)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
            .ifEmpty { null }

    /**
     * The clock span of a session.
     *
     * A hand-edited 作息表 can be missing an entry, or state a 节 whose end is not
     * after its start. An `.ics` has no way to say "zero length", so the span is
     * repaired rather than written out broken.
     */
    private fun classSpan(
        session: CourseSession,
        schedule: PeriodSchedule,
    ): Pair<LocalTime, LocalTime> {
        val start = schedule.startOf(session.startUnit)
            ?: schedule.periods.firstOrNull()?.start
            ?: DEFAULT_START
        val end = schedule.endOf(session.endUnit) ?: start.plusMinutes(CLASS_MINUTES.toLong())
        return clampSpan(start, end)
    }

    // ------------------------------------------------------------------ import

    /**
     * Parses an `.ics` document.
     *
     * @param fallbackTerm used only when the file carries no usable term info. A file
     *   this app exported always carries it (the `X-TJ-*` header), so the fallback only
     *   matters for a calendar file produced by something else — 教务's own export, a
     *   classmate's timetable, a phone calendar.
     * @return null only when the text holds no usable `VEVENT` at all.
     */
    fun parse(text: String, fallbackTerm: TermCalendar? = null): Parsed? =
        parseWithSchedule(text, fallbackTerm, PeriodSchedule.TONGJI)

    /**
     * [parse], with the 作息表 used to turn clock times back into 节次.
     *
     * An `.ics` records "10:00–11:35", never "3-4节", so the mapping has to go through
     * a [PeriodSchedule] — and the student's own edited 作息表 is the right one when
     * they have changed it in settings.
     */
    fun parseWithSchedule(
        text: String,
        fallbackTerm: TermCalendar? = null,
        schedule: PeriodSchedule = PeriodSchedule.TONGJI,
    ): Parsed? {
        val warnings = ArrayList<String>()
        val document = readDocument(text)

        if (document.unterminated) {
            warnings += "日历文件在最后一个事件结束前就中断了，最后一条课程按已有内容导入"
        }

        val usable = document.events.filter { it.start != null }
        if (usable.isEmpty()) return null
        val dateless = document.events.size - usable.size
        if (dateless > 0) warnings += "有 $dateless 个事件没有可用的开始时间，已跳过"

        // RECURRENCE-ID marks an event that *replaces* one instance of a recurring
        // event — what a phone calendar writes when the student drags a single class
        // to another time. The parent rule has to drop that date, otherwise the
        // original and the moved class are both imported.
        val exceptions = HashMap<String, MutableSet<LocalDate>>()
        var exceptionCount = 0
        for (event in usable) {
            val recurrenceId = event.recurrenceId ?: continue
            val uid = event.uid ?: continue
            exceptions.getOrPut(uid) { HashSet() }.add(recurrenceId)
            exceptionCount++
        }
        if (exceptionCount > 0) warnings += "已按 RECURRENCE-ID 处理 $exceptionCount 处改期的课"

        val ownExport = usable.any { it.ownExport } || document.calendarProps["PRODID"] == PRODID
        if (!ownExport) {
            warnings += "该日历文件不是由 TJ课表 导出的，课程信息按通用日历规则解析"
        }

        val planned = ArrayList<Pair<RawEvent, Expansion>>(usable.size)
        for (event in usable) {
            if (event.status == "CANCELLED") {
                warnings += "「${event.displayTitle}」标记为已取消（STATUS:CANCELLED），已跳过"
                continue
            }
            val expansion = expand(event, event.start ?: continue, exceptions[event.uid].orEmpty())
            expansion.unsupportedFreq?.let {
                warnings += "「${event.displayTitle}」的重复规则 FREQ=$it 不是按周重复，只导入了第一次课"
            }
            if (expansion.truncated) {
                warnings += "「${event.displayTitle}」的重复规则没有可用的结束条件，" +
                    "已按最多 $MAX_OCCURRENCES 次课展开"
            }
            for (unknown in event.rule?.unknownByDay.orEmpty()) {
                warnings += "「${event.displayTitle}」的 BYDAY 里有无法识别的「$unknown」，该天已忽略"
            }
            planned += event to expansion
        }
        // Every event was cancelled: there is nothing to import at all.
        if (planned.isEmpty()) return null

        val term = resolveTerm(
            props = document.calendarProps,
            calendarName = document.calendarProps["X-WR-CALNAME"]
                ?.let { unescapeText(it).trim().ifBlank { null } },
            planned = planned,
            fallback = fallbackTerm,
            warnings = warnings,
        )

        val courses = ArrayList<Course>()
        val courseIndexByKey = LinkedHashMap<String, Int>()
        val fragments = LinkedHashMap<SessionKey, Fragment>()
        var unnamed = 0

        for ((event, expansion) in planned) {
            val name = event.summary ?: run { unnamed++; UNNAMED_COURSE }

            // Grouping key: the 课程名称 plus, when DESCRIPTION carries one, the
            // 教学班. Two 教学班 of one 课程 are different courses to a student —
            // they run on different weeks with different teachers — so the name
            // alone would merge them into one unusable row.
            val courseKey = name + "\u0000" + (event.classCode ?: "")
            val courseIndex = courseIndexByKey.getOrPut(courseKey) {
                courses += Course(
                    name = name,
                    classCode = event.classCode,
                    teachingClassId = event.classCodeId,
                    teacher = event.teacher,
                )
                courses.lastIndex
            }

            // Times are per event, not per weekday, so this is resolved once.
            val startTime = if (expansion.hasTime) expansion.start.toLocalTime() else null
            val endTime = if (event.endHasTime) expansion.end?.toLocalTime() else null
            val units = resolveUnits(startTime, endTime, schedule)
            units.reason?.let {
                warnings += "「$name」$it，已按第 ${units.start}-${units.end} 节导入"
            }

            for ((day, dates) in expansion.byDay) {
                if (dates.isEmpty()) continue

                var weeks = WeekPattern.EMPTY
                var outside = 0
                for (date in dates) {
                    val week = term.weekOf(date)
                    if (week == null) outside++ else weeks = weeks + WeekPattern.of(week)
                }
                if (outside > 0) {
                    warnings += "「$name」有 $outside 次课不在此学期范围内，已忽略"
                }
                // Only ever fires for this app's own output, whose 周次 line is
                // authoritative: a file whose RRULE was lost or hand-edited can still
                // be restored from it. A foreign file has no such line.
                if (weeks.isEmpty && event.ownExport) {
                    event.weeksText?.let { weeks = WeekPattern.parse(it) }
                }

                // The runs of one session arrive as separate VEVENTs, so they are
                // merged back by slot identity. The room and teacher belong to the
                // identity: two classes at the same hour in different rooms are two
                // sessions, not one.
                val key = SessionKey(courseIndex, day, units.start, units.end, event.location, event.teacher)
                val fragment = fragments.getOrPut(key) { Fragment() }
                fragment.weeks = fragment.weeks + weeks
                if (fragment.rawWeeks == null) fragment.rawWeeks = event.weeksText
            }
        }
        if (unnamed > 0) warnings += "有 $unnamed 个事件没有课程名，已按「$UNNAMED_COURSE」导入"

        val byCourse = HashMap<Int, MutableList<CourseSession>>()
        for (index in courses.indices) byCourse[index] = ArrayList()
        for ((key, fragment) in fragments) {
            byCourse.getValue(key.courseIndex) += CourseSession(
                // Same convention as TongjiImport: before the rows are persisted the
                // courseId is the index into courses. The store rewrites both to real
                // database ids on insert.
                courseId = key.courseIndex.toLong(),
                dayOfWeek = key.day,
                startUnit = key.startUnit,
                endUnit = key.endUnit,
                weeks = fragment.weeks,
                rawWeeks = fragment.rawWeeks,
                room = key.room,
                teacher = key.teacher,
            )
        }

        return Parsed(term, courses, byCourse, warnings.distinct())
    }

    /**
     * Decides which semester the imported weeks belong to.
     *
     * Order: the file's own `X-TJ-*` header, then [fallback], then an inference from
     * the events themselves. The file wins over the fallback because a week number is
     * meaningless without the first week's date the file was written against — using
     * today's semester to number an old file would put every class in the wrong week.
     */
    private fun resolveTerm(
        props: Map<String, String>,
        calendarName: String?,
        planned: List<Pair<RawEvent, Expansion>>,
        fallback: TermCalendar?,
        warnings: MutableList<String>,
    ): TermCalendar {
        val dates = ArrayList<LocalDate>()
        for ((event, expansion) in planned) {
            event.start?.let { dates += it.toLocalDate() }
            for (list in expansion.byDay.values) dates += list
        }
        // Unreachable fallbacks: planned is non-empty and every planned event has a start.
        val earliest = dates.minOrNull() ?: LocalDate.of(1970, 1, 1)
        val latest = dates.maxOrNull() ?: earliest

        val fileStart = props["X-TJ-FIRST-WEEK"]?.let { parseStamp(it)?.date }
        val fileWeeks = props["X-TJ-TOTAL-WEEKS"]?.trim()?.toIntOrNull()
            ?.takeIf { it in 1..WeekPattern.MAX_WEEK }

        if (fileStart != null) {
            val total = fileWeeks ?: (inferredWeeks(fileStart, latest) + TERM_WEEK_MARGIN)
                .coerceIn(1, WeekPattern.MAX_WEEK)
            return TermCalendar(
                calendarId = props["X-TJ-CALENDAR-ID"]
                    ?.let { unescapeText(it).trim().ifBlank { null } }
                    ?: IMPORTED_CALENDAR_ID,
                name = calendarName ?: DEFAULT_TERM_NAME,
                year = props["X-TJ-YEAR"]?.trim()?.toIntOrNull() ?: yearGuess(fileStart),
                term = props["X-TJ-TERM"]?.trim()?.toIntOrNull() ?: termGuess(fileStart),
                firstWeekStart = fileStart,
                endDate = endOfTerm(fileStart, total),
                totalWeeks = total,
                weekStartDay = props["X-TJ-WEEK-START"]?.let { dayOfWeek(it) } ?: DayOfWeek.MONDAY,
                isCurrent = false,
            )
        }

        if (fallback != null) return fallback

        // No term information anywhere: anchor week 1 on the Monday of the earliest
        // class, which is 同济's week boundary, and read the length off the last one.
        val firstWeekStart = mondayOf(earliest)
        val total = (inferredWeeks(firstWeekStart, latest) + TERM_WEEK_MARGIN)
            .coerceIn(1, WeekPattern.MAX_WEEK)
        warnings += "日历文件没有学期信息，已按最早一次课推算学期：第 1 周从 $firstWeekStart 开始，共 $total 周"
        return TermCalendar(
            calendarId = IMPORTED_CALENDAR_ID,
            name = calendarName ?: DEFAULT_TERM_NAME,
            year = yearGuess(firstWeekStart),
            term = termGuess(firstWeekStart),
            firstWeekStart = firstWeekStart,
            endDate = endOfTerm(firstWeekStart, total),
            totalWeeks = total,
            weekStartDay = DayOfWeek.MONDAY,
            isCurrent = false,
        )
    }

    /**
     * Expands one event into the dates it actually happens on, grouped by weekday.
     *
     * Grouping by weekday rather than returning a flat list is what lets a
     * `BYDAY=MO,WE` event — a class that meets twice a week, which other apps do
     * write — become two sessions instead of one with the wrong day.
     *
     * @param start the event's start instant; passed in rather than read off [event] so
     *   this function stays total for an event whose `DTSTART` was unreadable.
     * @param exceptions dates the file cancelled through `EXDATE` or through a
     *   `RECURRENCE-ID` sibling.
     */
    private fun expand(event: RawEvent, start: LocalDateTime, exceptions: Set<LocalDate>): Expansion {
        val startDate = start.toLocalDate()
        val excluded: Set<LocalDate> =
            if (event.exdates.isEmpty()) exceptions else event.exdates.toSet() + exceptions

        fun single(unsupportedFreq: String? = null) = Expansion(
            start = start,
            end = event.end,
            hasTime = event.startHasTime,
            byDay = linkedMapOf(
                startDate.dayOfWeek to listOf(startDate).filterNot { it in excluded },
            ),
            unsupportedFreq = unsupportedFreq,
            truncated = false,
        )

        val rule = event.rule ?: return single()
        val freq = rule.freq
        // FREQ=DAILY / MONTHLY / YEARLY cannot be mapped onto teaching weeks, and
        // guessing would invent classes. One occurrence plus a warning is the honest
        // answer; silently dropping the class would not be.
        if (freq != null && freq != "WEEKLY") return single(unsupportedFreq = freq)

        val interval = (rule.interval ?: 1).coerceAtLeast(1)
        val count = rule.count?.takeIf { it > 0 }
        val weekStart = rule.weekStart ?: DayOfWeek.MONDAY
        val days = rule.byDays.ifEmpty { listOf(startDate.dayOfWeek) }
            .distinct()
            .sortedBy { offsetInWeek(it, weekStart) }

        val byDay = LinkedHashMap<DayOfWeek, MutableList<LocalDate>>()
        for (day in days) byDay[day] = ArrayList()

        val anchor = startDate.minusDays(offsetInWeek(startDate.dayOfWeek, weekStart).toLong())
        val startTime = start.toLocalTime()
        var generated = 0
        var truncated = false
        var week = 0L

        loop@ while (true) {
            val weekStartDate = anchor.plusWeeks(week * interval)
            for (day in days) {
                val date = weekStartDate.plusDays(offsetInWeek(day, weekStart).toLong())
                // RFC 5545 leaves a rule whose DTSTART is not one of its own
                // instances undefined; the safe reading is "never before DTSTART",
                // which is also what every calendar app does.
                if (date < startDate) continue

                val until = rule.until
                if (until != null) {
                    val past = if (until.time != null) {
                        date.atTime(startTime).isAfter(until.toLocalDateTime())
                    } else {
                        date.isAfter(until.date)
                    }
                    if (past) break@loop
                }
                if (count != null && generated >= count) break@loop
                if (generated >= MAX_OCCURRENCES) {
                    truncated = true
                    break@loop
                }
                generated++
                // COUNT is applied to the rule's instances, before EXDATE removes any:
                // a cancelled instance still counts towards the rule's length, which
                // is why the counter is bumped above this line.
                if (date !in excluded) byDay.getValue(day).add(date)
            }
            week++
        }

        return Expansion(
            start = start,
            end = event.end,
            hasTime = event.startHasTime,
            byDay = byDay,
            unsupportedFreq = null,
            truncated = truncated,
        )
    }

    /**
     * Maps a clock time onto 节.
     *
     * An exact match on a period start is the only unambiguous reading, and it is what
     * this app's own export always produces. Anything else is a guess — a class moved
     * by ten minutes in another app, an exam, a 讲座 — so the nearest period that has
     * already started is used and the caller is told the mapping was approximate.
     */
    private fun resolveUnits(start: LocalTime?, end: LocalTime?, schedule: PeriodSchedule): Units {
        val periods = schedule.periods
        if (periods.isEmpty()) return Units(1, 1, "作息表为空")

        val maxUnit = schedule.maxUnit.coerceAtLeast(1)
        if (start == null) {
            // An all-day event (DTSTART;VALUE=DATE) states no time at all. 第一节 is a
            // placeholder the student can fix in the app; dropping the class is not.
            val unit = periods.first().index.coerceIn(1, maxUnit)
            return Units(unit, unit, "没有具体的上课时间")
        }

        val exactStart = periods.firstOrNull { it.start == start }
        val startUnit = exactStart?.index
            ?: periods.filter { !it.start.isAfter(start) }.maxByOrNull { it.start }?.index
            ?: periods.first().index

        // The last period that has *started* by the time the event ends: a class
        // 08:00–10:00 covers 1-2节 and must not be stretched into 3节, which starts
        // exactly when the event ends.
        val exactEnd = end?.let { value -> periods.firstOrNull { it.end == value } }
        val endUnit = when {
            end == null -> startUnit
            exactEnd != null -> exactEnd.index
            else -> periods.filter { it.start.isBefore(end) }.maxByOrNull { it.start }?.index ?: startUnit
        }

        val from = startUnit.coerceIn(1, maxUnit)
        val to = endUnit.coerceIn(1, maxUnit).coerceAtLeast(from)
        val reason = when {
            exactStart == null -> "上课时间 $start 与作息表不吻合"
            end == null -> "缺少下课时间"
            exactEnd == null -> "下课时间 $end 与作息表不吻合"
            else -> null
        }
        return Units(from, to, reason)
    }
}

// ------------------------------------------------------------------ iCalendar plumbing

/** One maximal run of weeks that a single `FREQ=WEEKLY` rule describes. */
private data class WeekRun(val firstWeek: Int, val lastWeek: Int, val step: Int) {
    val count: Int get() = ((lastWeek - firstWeek) / step) + 1

    val weeks: List<Int> get() = (0 until count).map { firstWeek + it * step }
}

/** Identity of one meeting slot, used to merge a session's several runs back together. */
private data class SessionKey(
    val courseIndex: Int,
    val day: DayOfWeek,
    val startUnit: Int,
    val endUnit: Int,
    val room: String?,
    val teacher: String?,
)

/** Weeks accumulated for one [SessionKey]. */
private class Fragment {
    var weeks: WeekPattern = WeekPattern.EMPTY
    var rawWeeks: String? = null
}

/** 节 range resolved from clock times, plus why it had to be guessed. */
private data class Units(val start: Int, val end: Int, val reason: String?)

/** A parsed date-time; [time] is null for a `VALUE=DATE` stamp. */
private data class IcsStamp(val date: LocalDate, val time: LocalTime?) {
    fun toLocalDateTime(): LocalDateTime = date.atTime(time ?: LocalTime.MIDNIGHT)
}

/** The `RRULE` keys this app understands; everything else is ignored. */
private data class RawRule(
    val freq: String?,
    val interval: Int?,
    val count: Int?,
    val until: IcsStamp?,
    val byDays: List<DayOfWeek>,
    val weekStart: DayOfWeek?,
    val unknownByDay: List<String>,
)

/** One `VEVENT`, flattened to the fields that can be mapped onto the domain model. */
private data class RawEvent(
    val uid: String?,
    val summary: String?,
    val classCode: String?,
    val classCodeId: Long?,
    val teacher: String?,
    val weeksText: String?,
    val location: String?,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val startHasTime: Boolean,
    val endHasTime: Boolean,
    val exdates: List<LocalDate>,
    val rule: RawRule?,
    val recurrenceId: LocalDate?,
    val status: String?,
    val ownExport: Boolean,
) {
    val displayTitle: String get() = summary ?: "未命名课程"
}

/** The dates one event expands to, grouped by weekday. */
private data class Expansion(
    val start: LocalDateTime,
    val end: LocalDateTime?,
    val hasTime: Boolean,
    val byDay: Map<DayOfWeek, List<LocalDate>>,
    /** Set to a non-`WEEKLY` FREQ when the rule could not be mapped onto weeks. */
    val unsupportedFreq: String?,
    val truncated: Boolean,
)

/** One property line: `NAME;PARAM=VALUE:value`. */
private data class Prop(val name: String, val params: Map<String, String>, val value: String)

private class IcsDocument {
    val events = ArrayList<RawEvent>()
    val calendarProps = HashMap<String, String>()
    var unterminated = false
}

private const val CRLF = "\r\n"

/** RFC 5545 asks for at most 75 octets per line, excluding the line break. */
private const val FOLD_LIMIT = 75

/** Assumed length of one 节 when the 作息表 does not state an end time. */
private const val CLASS_MINUTES = 45

/** Start used when the 作息表 is completely empty. */
private val DEFAULT_START: LocalTime = LocalTime.of(8, 0)

/**
 * Extra weeks added to an inferred semester length.
 *
 * The inference only sees the classes, so a term whose last event sits in the final
 * teaching week would be clipped at exactly that week. The margin also covers the
 * 复习/考试 week that has no scheduled class of its own.
 */
private const val TERM_WEEK_MARGIN = 1

/**
 * Upper bound on occurrences generated for one event.
 *
 * A rule without `COUNT` or `UNTIL` is infinite in RFC 5545, and the week mask itself
 * is only 64 weeks wide, so more than this could never be represented anyway.
 */
private const val MAX_OCCURRENCES = WeekPattern.MAX_WEEK

private const val UNNAMED_COURSE = "未命名课程"

private const val IMPORTED_CALENDAR_ID = "ics"

private const val DEFAULT_TERM_NAME = "从日历文件导入"

/** 学年度 assumed when the file does not state one: 春季 belongs to the previous 学年. */
private fun yearGuess(firstWeekStart: LocalDate): Int =
    if (firstWeekStart.monthValue >= 7) firstWeekStart.year else firstWeekStart.year - 1

/** 1 = 秋季, 2 = 春季. The `.ics` format has no field for 学期, so this is a guess. */
private fun termGuess(firstWeekStart: LocalDate): Int =
    if (firstWeekStart.monthValue >= 7) 1 else 2

private fun endOfTerm(firstWeekStart: LocalDate, totalWeeks: Int): LocalDate =
    firstWeekStart.plusWeeks((totalWeeks - 1).toLong()).plusDays(6)

/** Week count implied by `firstWeekStart … latest`, at least 1. */
private fun inferredWeeks(firstWeekStart: LocalDate, latest: LocalDate): Int {
    val days = latest.toEpochDay() - firstWeekStart.toEpochDay()
    if (days < 0) return 1
    return (days / 7).toInt() + 1
}

private fun mondayOf(date: LocalDate): LocalDate =
    date.minusDays(offsetInWeek(date.dayOfWeek, DayOfWeek.MONDAY).toLong())

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

private val DATETIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

/** Marker line of [TimetableIcs.export], so a re-import recognises its own output. */
private const val DESCRIPTION_MARKER = "由 TJ课表 导出 · TJtimetable:1"

private const val MARKER_TOKEN = "TJtimetable:1"

private const val KEY_TEACHER = "教师："
private const val KEY_CLASS = "教学班："
private const val KEY_CLASS_ID = "教学班ID："
private const val KEY_PERIODS = "节次："
private const val KEY_WEEKS = "周次："

// ------------------------------------------------------------------------ escaping

/**
 * Escapes a TEXT value: `\` `;` `,` and newlines, per RFC 5545 §3.3.11.
 *
 * The backslash must go first, otherwise the backslashes introduced by the later
 * replacements would be escaped again and the value would come back with stray `\`
 * characters in it. Newlines become the two-character sequence `\n` *after* that
 * step, so they survive as a literal backslash-n in the file.
 */
private fun escapeText(value: String): String = value
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\r\n", "\\n")
    .replace("\r", "\\n")
    .replace("\n", "\\n")

/** Reverses [escapeText]. Unknown escapes are kept verbatim rather than dropped. */
private fun unescapeText(value: String): String {
    if (value.indexOf('\\') < 0) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch == '\\' && i + 1 < value.length) {
            when (val next = value[i + 1]) {
                'n', 'N' -> out.append('\n')
                else -> out.append(next)
            }
            i += 2
        } else {
            out.append(ch)
            i++
        }
    }
    return out.toString()
}

/**
 * Folds one logical line to [FOLD_LIMIT] octets, continuing with CRLF + one space.
 *
 * Folding counts **octets, not characters**: a Chinese course name is three bytes per
 * character, so a 40-character line is already over the limit. Splitting inside a
 * multi-byte character would corrupt the file, which is why the loop walks code points
 * and never emits half a character.
 */
private fun foldLine(line: String): String {
    if (line.toByteArray(Charsets.UTF_8).size <= FOLD_LIMIT) return line

    val out = StringBuilder(line.length + 16)
    var used = 0
    var i = 0
    while (i < line.length) {
        val codePoint = line.codePointAt(i)
        val chars = if (codePoint >= 0x10000) 2 else 1
        val octets = utf8Length(codePoint)
        if (used + octets > FOLD_LIMIT) {
            out.append(CRLF).append(' ')
            // The leading space is part of the continuation line, so it counts.
            used = 1
        }
        out.append(line, i, i + chars)
        used += octets
        i += chars
    }
    return out.toString()
}

private fun utf8Length(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

// ------------------------------------------------------------------------ line input

/**
 * Splits the document into logical lines: any of CRLF, LF or CR ends a line, and a
 * line beginning with a space or tab is the continuation of the previous one.
 *
 * Real-world `.ics` files from Outlook, Google and 教务 exports disagree about line
 * endings, and a parser that only accepts CRLF rejects half of them.
 */
private fun unfoldLines(text: String): List<String> {
    val physical = ArrayList<String>(64)
    var start = 0
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '\r' || ch == '\n') {
            physical.add(text.substring(start, i))
            if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
            i++
            start = i
        } else {
            i++
        }
    }
    if (start < text.length) physical.add(text.substring(start))

    val logical = ArrayList<String>(physical.size)
    for (raw in physical) {
        if (logical.isNotEmpty() && raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t')) {
            logical[logical.size - 1] = logical[logical.size - 1] + raw.substring(1)
        } else {
            // A UTF-8 BOM smuggled into the first line would otherwise turn
            // BEGIN:VCALENDAR into a property nobody recognises.
            logical.add(if (logical.isEmpty()) raw.removePrefix("\uFEFF") else raw)
        }
    }
    return logical
}

/**
 * Parses `NAME;PARAM=VALUE;…:value`, or null for anything that is not a property.
 *
 * A property value may itself contain `:` (a `DESCRIPTION` holding a URL, an
 * `RRULE` never), so only the *head* — the part before the first unquoted colon —
 * is split on semicolons.
 */
private fun parseProperty(line: String): Prop? {
    if (line.isEmpty()) return null

    var quoted = false
    var colon = -1
    for (i in line.indices) {
        val ch = line[i]
        when {
            ch == '"' -> quoted = !quoted
            ch == ':' && !quoted -> {
                colon = i
            }
        }
        if (colon >= 0) break
    }
    if (colon <= 0) return null

    val head = line.substring(0, colon)
    val value = line.substring(colon + 1)

    val parts = ArrayList<String>(3)
    var current = StringBuilder()
    quoted = false
    for (ch in head) {
        when {
            ch == '"' -> {
                quoted = !quoted
                current.append(ch)
            }
            ch == ';' && !quoted -> {
                parts += current.toString()
                current = StringBuilder()
            }
            else -> current.append(ch)
        }
    }
    parts += current.toString()

    var name = parts.firstOrNull()?.trim()?.uppercase().orEmpty()
    if (name.isEmpty()) return null
    // RFC 5545 group syntax: `item1.SUMMARY`. The group is only a namespace.
    val dot = name.lastIndexOf('.')
    if (dot >= 0) name = name.substring(dot + 1)
    if (name.isEmpty()) return null

    val params = HashMap<String, String>(2)
    for (i in 1 until parts.size) {
        val part = parts[i]
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        params[part.substring(0, eq).trim().uppercase()] =
            part.substring(eq + 1).trim().removeSurrounding("\"")
    }
    return Prop(name, params, value)
}

/** Reads a whole document: calendar-level properties plus every `VEVENT`. */
private fun readDocument(text: String): IcsDocument {
    val document = IcsDocument()
    var inEvent = false
    var nested = 0
    var props = ArrayList<Prop>()

    for (line in unfoldLines(text)) {
        val prop = parseProperty(line) ?: continue
        when (prop.name) {
            "BEGIN" -> {
                val component = prop.value.trim().uppercase()
                if (component == "VEVENT" && !inEvent) {
                    inEvent = true
                    nested = 0
                    props = ArrayList()
                } else if (inEvent) {
                    // VTIMEZONE, VALARM, … contribute nothing we can use.
                    nested++
                }
            }
            "END" -> {
                val component = prop.value.trim().uppercase()
                if (component == "VEVENT" && inEvent) {
                    if (nested == 0) {
                        document.events += toRawEvent(props)
                        inEvent = false
                    } else {
                        nested--
                    }
                } else if (inEvent && nested > 0) {
                    nested--
                }
            }
            else -> {
                if (inEvent) {
                    if (nested == 0) props += prop
                } else {
                    document.calendarProps[prop.name] = prop.value
                }
            }
        }
    }

    if (inEvent) {
        // Truncated file: whatever came through is still the student's timetable.
        document.events += toRawEvent(props)
        document.unterminated = true
    }
    return document
}

private fun toRawEvent(props: List<Prop>): RawEvent {
    var summary: String? = null
    var description: String? = null
    var location: String? = null
    var uid: String? = null
    var status: String? = null
    var start: IcsStamp? = null
    var end: IcsStamp? = null
    var duration: Duration? = null
    var rule: RawRule? = null
    var recurrenceId: LocalDate? = null
    val exdates = ArrayList<LocalDate>()

    for (prop in props) {
        when (prop.name) {
            "SUMMARY" -> summary = unescapeText(prop.value).trim().ifBlank { null }
            "DESCRIPTION" -> description = unescapeText(prop.value).ifBlank { null }
            "LOCATION" -> location = unescapeText(prop.value).trim().ifBlank { null }
            "UID" -> uid = prop.value.trim().ifBlank { null }
            "STATUS" -> status = prop.value.trim().uppercase()
            "DTSTART" -> start = parseStamp(prop.value)
            "DTEND" -> end = parseStamp(prop.value)
            "DURATION" -> duration = parseDuration(prop.value)
            "RRULE" -> rule = parseRule(prop.value)
            "RECURRENCE-ID" -> recurrenceId = parseStamp(prop.value)?.date
            "EXDATE" -> prop.value.split(',').forEach { parseStamp(it)?.let { stamp -> exdates += stamp.date } }
        }
    }

    // DURATION is the RFC's alternative to DTEND; honour it rather than losing the
    // class's length.
    if (end == null && duration != null && start != null && start.time != null) {
        val computed = start.toLocalDateTime().plus(duration)
        end = IcsStamp(computed.toLocalDate(), computed.toLocalTime())
    }

    val classLabel = fieldOf(description, KEY_CLASS)
    return RawEvent(
        uid = uid,
        summary = summary,
        classCode = classLabel,
        // 教学班ID is only written when it carries information the label cannot; a
        // numeric label is kept as the class code rather than silently promoted.
        classCodeId = fieldOf(description, KEY_CLASS_ID)?.toLongOrNull(),
        teacher = fieldOf(description, KEY_TEACHER),
        weeksText = fieldOf(description, KEY_WEEKS),
        location = location,
        start = start?.toLocalDateTime(),
        end = end?.toLocalDateTime(),
        startHasTime = start?.time != null,
        endHasTime = end?.time != null,
        exdates = exdates,
        rule = rule,
        recurrenceId = recurrenceId,
        status = status,
        ownExport = description?.contains(MARKER_TOKEN) == true,
    )
}

/** The value of a `key：value` line inside DESCRIPTION, or null. */
private fun fieldOf(description: String?, key: String): String? {
    if (description == null) return null
    for (line in description.lineSequence()) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith(key)) {
            return trimmed.removePrefix(key).trim().ifBlank { null }
        }
    }
    return null
}

/**
 * Parses a DATE or DATE-TIME stamp.
 *
 * A trailing `Z` is **stripped, not converted**. A timetable is wall-clock: the file
 * says the class is at 08:00, and the student is standing in the classroom at 08:00
 * local. Shifting by a time-zone offset would move every class by eight hours for a
 * file written in UTC, and the app writes no `Z` at all. `VALUE=DATE` is recognised by
 * the value's own shape (`20250407`), which also covers exporters that omit the
 * parameter, and yields a stamp with no time.
 */
private fun parseStamp(raw: String?): IcsStamp? {
    var value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (value.endsWith("Z") || value.endsWith("z")) value = value.dropLast(1)
    val dot = value.indexOf('.')
    if (dot > 0) value = value.substring(0, dot)
    if (value.isEmpty()) return null

    return try {
        when {
            value.length == 8 && value.all { it.isDigit() } ->
                IcsStamp(LocalDate.parse(value, DATE_FORMAT), null)

            value.length == 15 && value[8] == 'T' ->
                IcsStamp(
                    LocalDate.parse(value.substring(0, 8), DATE_FORMAT),
                    LocalTime.of(
                        value.substring(9, 11).toInt(),
                        value.substring(11, 13).toInt(),
                        value.substring(13, 15).toInt(),
                    ),
                )

            // `yyyyMMdd'T'HHmm`, which some exporters write for minute precision.
            value.length == 13 && value[8] == 'T' ->
                IcsStamp(
                    LocalDate.parse(value.substring(0, 8), DATE_FORMAT),
                    LocalTime.of(value.substring(9, 11).toInt(), value.substring(11, 13).toInt()),
                )

            value.contains('-') -> {
                val at = value.indexOf('T')
                if (at < 0) {
                    IcsStamp(LocalDate.parse(value), null)
                } else {
                    val dateTime = LocalDateTime.parse(value)
                    IcsStamp(dateTime.toLocalDate(), dateTime.toLocalTime())
                }
            }

            else -> null
        }
    } catch (e: RuntimeException) {
        // A malformed stamp is a missing stamp: the caller decides what to do.
        null
    }
}

/** `PT1H35M`, the RFC 5545 duration form. Negative durations are ignored. */
private fun parseDuration(raw: String): Duration? {
    val value = raw.trim().uppercase()
    if (value.isEmpty() || value[0] == '-' || !value.startsWith("P")) return null
    val timePart = value.substringAfter('T', "")
    if (timePart.isEmpty()) return null

    var seconds = 0L
    for (match in DURATION_PART.findAll(timePart)) {
        val amount = match.groupValues[1].toLongOrNull() ?: continue
        seconds += when (match.groupValues[2]) {
            "H" -> amount * 3600
            "M" -> amount * 60
            else -> amount
        }
    }
    return if (seconds > 0) Duration.ofSeconds(seconds) else null
}

private val DURATION_PART = Regex("(\\d+)([HMS])")

/** Parses the keys of an `RRULE` this app maps onto teaching weeks. */
private fun parseRule(raw: String): RawRule? {
    if (raw.isBlank()) return null
    var freq: String? = null
    var interval: Int? = null
    var count: Int? = null
    var until: IcsStamp? = null
    var weekStart: DayOfWeek? = null
    val byDays = ArrayList<DayOfWeek>()
    val unknown = ArrayList<String>()

    for (part in raw.split(';')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val key = part.substring(0, eq).trim().uppercase()
        val value = part.substring(eq + 1).trim()
        when (key) {
            "FREQ" -> freq = value.uppercase()
            "INTERVAL" -> interval = value.toIntOrNull()
            "COUNT" -> count = value.toIntOrNull()
            "UNTIL" -> until = parseStamp(value)
            "WKST" -> weekStart = dayOfWeek(value)
            "BYDAY" -> for (entry in value.split(',')) {
                if (entry.isBlank()) continue
                // `2MO` / `-1FR` are ordinal forms that only mean something to a
                // monthly rule; the day itself is still usable here.
                val code = entry.trim().takeLast(2).uppercase()
                val day = dayOfWeek(code)
                if (day == null) unknown += entry.trim() else if (day !in byDays) byDays += day
            }
        }
    }
    return RawRule(freq, interval, count, until, byDays, weekStart, unknown)
}

/** `MONDAY` -> `MO`, the codes used by `BYDAY` and `WKST`. */
private fun rfcDay(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "MO"
    DayOfWeek.TUESDAY -> "TU"
    DayOfWeek.WEDNESDAY -> "WE"
    DayOfWeek.THURSDAY -> "TH"
    DayOfWeek.FRIDAY -> "FR"
    DayOfWeek.SATURDAY -> "SA"
    DayOfWeek.SUNDAY -> "SU"
}

private fun dayOfWeek(code: String): DayOfWeek? = when (code.trim().uppercase()) {
    "MO", "MON", "MONDAY" -> DayOfWeek.MONDAY
    "TU", "TUE", "TUESDAY" -> DayOfWeek.TUESDAY
    "WE", "WED", "WEDNESDAY" -> DayOfWeek.WEDNESDAY
    "TH", "THU", "THURSDAY" -> DayOfWeek.THURSDAY
    "FR", "FRI", "FRIDAY" -> DayOfWeek.FRIDAY
    "SA", "SAT", "SATURDAY" -> DayOfWeek.SATURDAY
    "SU", "SUN", "SUNDAY" -> DayOfWeek.SUNDAY
    else -> null
}

/** Days between [day] and the first day of its week, for the given `WKST`. */
private fun offsetInWeek(day: DayOfWeek, weekStart: DayOfWeek): Int =
    (day.value - weekStart.value + 7) % 7

private fun stamp(value: LocalDateTime): String = DATETIME_FORMAT.format(value)

/** 32-bit FNV-1a, the same hash `courseHueIndex` uses, so ids stay stable forever. */
private fun fnv1a(key: String): Long {
    var hash = 2166136261L
    for (ch in key) {
        hash = hash xor ch.code.toLong()
        hash = (hash * 16777619L) and 0xFFFFFFFFL
    }
    return hash
}

private fun hex32(value: Long): String = value.toString(16).padStart(8, '0')

/**
 * `DTEND` must be after `DTSTART`. A 作息表 entry whose end is not after its start —
 * a typo, or a 节 that was edited by hand — would otherwise write a zero-length event
 * that calendar apps either hide or reject.
 */
private fun clampSpan(start: LocalTime, end: LocalTime): Pair<LocalTime, LocalTime> {
    if (end.isAfter(start)) return start to end
    val latestStart = LocalTime.of(23, 58, 59)
    val from = if (start.isAfter(latestStart)) latestStart else start
    val bumped = from.plusMinutes(CLASS_MINUTES.toLong())
    // plusMinutes wraps past midnight, which shows up here as a value that is no
    // longer after the start.
    val to = if (bumped.isAfter(from)) bumped else LocalTime.of(23, 59, 59)
    return from to to
}
