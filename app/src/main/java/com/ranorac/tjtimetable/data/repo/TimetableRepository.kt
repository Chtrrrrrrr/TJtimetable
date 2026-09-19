package com.ranorac.tjtimetable.data.repo

import androidx.room.withTransaction
import com.ranorac.tjtimetable.calendar.CalendarScope
import com.ranorac.tjtimetable.calendar.CalendarSync
import com.ranorac.tjtimetable.calendar.CalendarSyncResult
import com.ranorac.tjtimetable.calendar.DeviceCalendar
import com.ranorac.tjtimetable.data.db.CourseEntity
import com.ranorac.tjtimetable.data.db.DayAdjustmentDao
import com.ranorac.tjtimetable.data.db.TimetableDatabase
import com.ranorac.tjtimetable.data.db.toDomain
import com.ranorac.tjtimetable.data.db.toDomainPair
import com.ranorac.tjtimetable.data.db.toEntity
import com.ranorac.tjtimetable.data.prefs.Credentials
import com.ranorac.tjtimetable.data.prefs.CredentialsStore
import com.ranorac.tjtimetable.data.remote.TongjiApi
import com.ranorac.tjtimetable.data.remote.TongjiApiException
import com.ranorac.tjtimetable.data.remote.TongjiImport
import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.DayAdjustment
import com.ranorac.tjtimetable.domain.PeriodSchedule
import com.ranorac.tjtimetable.domain.ScheduleAdjustmentSet
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.Timetable
import com.ranorac.tjtimetable.domain.WeekPattern
import com.ranorac.tjtimetable.export.TimetableIcs
import com.ranorac.tjtimetable.scrape.Imported
import com.ranorac.tjtimetable.scrape.TongjiStudentAdapter
import com.ranorac.tjtimetable.scrape.TongjiWebCapture
import com.ranorac.tjtimetable.scrape.toTermCalendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate

/** Result of a 教务 import, phrased for direct display. */
sealed interface ImportOutcome {
    data class Success(
        val termName: String,
        val courseCount: Int,
        val sessionCount: Int,
        /** Non-fatal problems worth surfacing, e.g. unparseable 周次. */
        val warnings: List<String>,
    ) : ImportOutcome

    /** Client id / 学号 not filled in, or no usable token. */
    data object NotConfigured : ImportOutcome

    data object Unauthorized : ImportOutcome

    data class Failed(val message: String) : ImportOutcome
}

/** Outcome of writing the timetable into the device's own calendar. */
sealed interface CalendarRegistration {
    data class Done(val events: Int, val skippedHolidays: Int) : CalendarRegistration

    data object NoPermission : CalendarRegistration

    data object NoTimetable : CalendarRegistration

    data class Failed(val message: String) : CalendarRegistration
}

/** Term plus the fully resolved 调休 state — what the 调休 editor renders. */
data class AdjustmentOverview(
    val term: TermCalendar,
    val adjustments: ScheduleAdjustmentSet,
)

/** Outcome of importing a `.ics` calendar file. */
sealed interface IcsImport {
    data class Success(
        val termName: String,
        val courseCount: Int,
        val sessionCount: Int,
        val warnings: List<String>,
    ) : IcsImport

    /** The file had no VEVENT this app could turn into a class. */
    data class Failed(val message: String) : IcsImport
}

/** Outcome of importing a response captured from the login browser, or pasted by hand. */
sealed interface ScrapeImport {
    data class Success(
        val termName: String,
        val courseCount: Int,
        val sessionCount: Int,
        val warnings: List<String>,
    ) : ScrapeImport

    /** Recognisably not timetable data — the student copied the wrong request. */
    data object NotTimetable : ScrapeImport

    data class Failed(val message: String) : ScrapeImport
}

/**
 * Single source of truth for the timetable.
 *
 * Everything the UI shows flows from [observeTimetable]; nothing reads the API
 * directly. That keeps the widget, the calendar writer and the UI consistent,
 * and means 调休 working offline falls out of persistence rather than needing a
 * cache layer.
 */
class TimetableRepository(
    private val db: TimetableDatabase,
    private val api: TongjiApi,
    private val credentials: CredentialsStore,
    private val calendarSync: CalendarSync,
    /**
     * Invoked after any change that alters what the timetable looks like.
     *
     * The home-screen widget has no live link to the database, so without this
     * nudge it would keep showing stale content for up to 30 minutes — the
     * minimum `updatePeriodMillis` Android allows. A callback rather than a
     * `Context` keeps this layer free of widget plumbing.
     */
    private val onTimetableChanged: () -> Unit = {},
) {
    /** Failures here must never break the write that triggered them. */
    private fun notifyChanged() {
        runCatching { onTimetableChanged() }
    }

    private val termDao get() = db.termDao()
    private val courseDao get() = db.courseDao()
    private val adjustmentDao: DayAdjustmentDao get() = db.dayAdjustmentDao()

    /**
     * The current semester as a [Timetable], or null before anything is imported.
     *
     * Recomputes automatically whenever courses or 调休 rows change, so every
     * screen stays in step without manual invalidation.
     */
    fun observeTimetable(periodSchedule: PeriodSchedule = PeriodSchedule.TONGJI): Flow<Timetable?> =
        termDao.observeCurrent().flatMapLatest { term ->
            if (term == null) {
                flowOf(null)
            } else {
                combine(
                    courseDao.observeVisibleForTerm(term.calendarId),
                    adjustmentDao.observeForTerm(term.calendarId),
                ) { courses, adjustments ->
                    val pairs = courses.map { it.toDomainPair() }
                    Timetable(
                        term = term.toDomain(),
                        courses = pairs.map { it.first },
                        sessions = pairs.flatMap { it.second },
                        // Rows come back source-tagged; manual edits already won
                        // at write time, so a plain fold is enough here.
                        adjustments = ScheduleAdjustmentSet.EMPTY.withAll(adjustments.map { it.toDomain() }),
                        periodSchedule = periodSchedule,
                    )
                }
            }
        }

    /** Non-fatal notes from the last import, for a dismissible banner. */
    private val _lastWarnings = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())

    val lastImportWarnings: Flow<List<String>> = _lastWarnings

    /**
     * The current term together with every resolved 调休 row, for the editor.
     *
     * Unlike [observeTimetable] this keeps hidden courses out of the picture
     * entirely and emits even when there are no courses, because the student may
     * legitimately want to fix the 校历 before importing anything.
     */
    fun observeAdjustmentOverview(): Flow<AdjustmentOverview?> =
        termDao.observeCurrent().flatMapLatest { term ->
            if (term == null) {
                flowOf(null)
            } else {
                adjustmentDao.observeForTerm(term.calendarId).map { rows ->
                    AdjustmentOverview(
                        term = term.toDomain(),
                        adjustments = ScheduleAdjustmentSet.EMPTY.withAll(rows.map { it.toDomain() }),
                    )
                }
            }
        }

    // ------------------------------------------------------------- import

    /**
     * Fetches the current semester's timetable from 教务 and replaces the local copy.
     *
     * Student customisations (colour, note, hidden) are carried across by matching
     * on 教学班 id, so re-importing after a 选课 change does not undo them.
     */
    suspend fun importCurrentSemester(): ImportOutcome {
        val creds = credentials.current()
        if (!creds.hasClient || creds.userId.isBlank()) return ImportOutcome.NotConfigured

        val token = try {
            ensureToken(creds)
        } catch (e: TongjiApiException) {
            return if (e.kind == TongjiApiException.Kind.UNAUTHORIZED) ImportOutcome.Unauthorized
            else ImportOutcome.Failed(e.message ?: "登录失败")
        } ?: return ImportOutcome.NotConfigured

        return try {
            val termDto = api.currentTermCalendar(token)
            val term = TongjiImport.toTermCalendar(termDto)
                ?: return ImportOutcome.Failed("教务系统未返回学期信息，请稍后重试")

            val dtos = api.studentTimetable(token, creds.userId, term.calendarId)
            val imported = TongjiImport.fromRealtime(dtos, TongjiImport.fullTerm(term.totalWeeks))
            if (imported.courses.isEmpty()) {
                // Never wipe a good local copy just because the server hiccuped.
                return ImportOutcome.Failed("教务系统未返回课程，已保留本地课表")
            }

            persist(term, imported.courses, imported.sessionsByCourse)
            refreshAdjustments(term)
            _lastWarnings.value = imported.warnings

            ImportOutcome.Success(
                termName = term.name,
                courseCount = imported.courses.size,
                sessionCount = imported.sessionCount,
                warnings = imported.warnings,
            )
        } catch (e: TongjiApiException) {
            if (e.kind == TongjiApiException.Kind.UNAUTHORIZED) ImportOutcome.Unauthorized
            else ImportOutcome.Failed(e.message ?: "导入失败")
        }
    }

    /**
     * Returns a usable access token, minting one via 客户端模式 when the stored
     * one has expired. Returns null when the student still has to authorise
     * through the browser (授权码模式).
     *
     * @param creds pass a value already in hand to avoid a second DataStore read;
     *   null reads the current credentials. It is nullable rather than defaulted
     *   to `credentials.current()` because Kotlin forbids a suspend call in a
     *   default parameter value.
     */
    suspend fun ensureToken(creds: Credentials? = null): String? {
        val current = creds ?: credentials.current()
        if (current.isTokenFresh()) return current.accessToken
        if (!current.canUseClientCredentials) return null
        val response = api.clientCredentialsToken(current.clientId, current.clientSecret)
        val access = response.accessToken ?: return null
        credentials.setToken(access, response.expiresIn ?: 7200L)
        return access
    }

    /** Stores a token obtained by the 授权码模式 browser flow. */
    suspend fun storeToken(accessToken: String, expiresInSeconds: Long, userId: String? = null) {
        credentials.setToken(accessToken, expiresInSeconds)
        if (!userId.isNullOrBlank()) credentials.setStudent(userId, null)
    }

    // ------------------------------------------------------------- 校历/调休

    /**
     * Refreshes 校历 day types and inferred 补课 mappings.
     *
     * Runs without a token, because the 校历 endpoint needs no authorisation —
     * so 调休 information is available even before the student logs in.
     * Failure is silent and non-destructive: a stale-but-present 校历 beats
     * clearing it because the network blipped.
     */
    suspend fun refreshAdjustments(term: TermCalendar): Int {
        val from = term.firstWeekStart.minusDays(7)
        val to = term.endDate.plusDays(7)
        val raw = try {
            api.schoolCalendar(from, to)
        } catch (e: TongjiApiException) {
            return 0
        }
        if (raw.isEmpty()) return 0

        val withInferred = ScheduleAdjustmentSet.inferMakeupDays(
            ScheduleAdjustmentSet.fromApiCalendar(raw),
        )

        db.withTransaction {
            adjustmentDao.deleteDerivedForTerm(term.calendarId)
            val manualDays = adjustmentDao.manualForTerm(term.calendarId)
                .mapTo(HashSet()) { it.epochDay }
            val rows = withInferred.all
                .filter { it.date.toEpochDay() !in manualDays }
                .map { it.toEntity(term.calendarId) }
            if (rows.isNotEmpty()) adjustmentDao.upsertAll(rows)
        }
        notifyChanged()
        return withInferred.all.size
    }

    /** Overrides one date, e.g. after reading the 教务处 notice. */
    suspend fun putAdjustment(termId: String, adjustment: DayAdjustment) {
        adjustmentDao.upsert(adjustment.toEntity(termId))
        notifyChanged()
    }

    /** Removes an override, restoring whatever the 校历 says. */
    suspend fun clearAdjustment(termId: String, date: LocalDate) {
        adjustmentDao.delete(termId, date.toEpochDay())
        notifyChanged()
    }

    /**
     * Applies the 调休 rules parsed out of a pasted 教务处 notice.
     *
     * @return how many dates were set.
     */
    suspend fun applyNotice(termId: String, noticeText: String, year: Int): Int {
        val parsed = ScheduleAdjustmentSet.parseNotice(noticeText, year)
        if (parsed.isEmpty()) return 0
        adjustmentDao.upsertAll(parsed.map { it.toEntity(termId) })
        notifyChanged()
        return parsed.size
    }

    // --------------------------------------------------------- course edits

    suspend fun setCourseColor(courseId: Long, colorIndex: Int?) {
        courseDao.setColor(courseId, colorIndex)
        notifyChanged()
    }

    suspend fun setCourseHidden(courseId: Long, hidden: Boolean) {
        courseDao.setHidden(courseId, hidden)
        notifyChanged()
    }

    suspend fun setCourseNote(courseId: Long, note: String?) {
        courseDao.setNote(courseId, note)
        notifyChanged()
    }

    suspend fun deleteCourse(courseId: Long) {
        courseDao.deleteById(courseId)
        notifyChanged()
    }

    /** Adds a course by hand, with its slots. Returns the new course id. */
    suspend fun addManualCourse(
        termId: String,
        course: Course,
        sessions: List<CourseSession>,
    ): Long = db.withTransaction {
        val id = courseDao.insertCourse(course.toEntity(termId))
        if (sessions.isNotEmpty()) {
            courseDao.insertSessions(sessions.map { it.copy(courseId = id).toEntity() })
        }
        id
    }.also { notifyChanged() }

    suspend fun currentTerm(): TermCalendar? = termDao.current()?.toDomain()

    // ------------------------------------------------------ 抓取/粘贴响应导入

    /**
     * Imports a course-table response captured from the login browser, or pasted
     * from a desktop browser's DevTools.
     *
     * @param url the request URL, when known. `calendarId` lives on it and nowhere
     *   in the body, so passing it is how the semester is identified.
     */
    suspend fun importCapturedResponse(url: String?, body: String): ScrapeImport {
        if (!TongjiStudentAdapter.looksLikeTimetable(body)) return ScrapeImport.NotTimetable
        if (url != null && !TongjiWebCapture.isEndpoint(url)) {
            // Not fatal — the student may have pasted only the response — but it means
            // we cannot tell which endpoint produced this.
        }

        val calendarId = url?.let { TongjiWebCapture.calendarIdOf(it) }
        // `parseOrNull`, not `parse`: the faithful port's `parse` never returns null (it
        // reports unrecognised input through diagnostics, matching the reference), so a
        // `?:` on it would be dead code. This is the variant that signals "not a
        // timetable" so the caller can tell the student they copied the wrong request.
        val parsed = TongjiStudentAdapter.parseOrNull(body, termId = calendarId)
            ?: return ScrapeImport.NotTimetable
        if (parsed.courses.isEmpty()) {
            return ScrapeImport.Failed("已识别为课表数据，但没有解析出任何课程（可能本学期无课）")
        }

        val term = resolveTermForScrape(calendarId, parsed)
        persist(term, parsed.courses, parsed.sessionsByCourse)
        _lastWarnings.value = parsed.warnings

        return ScrapeImport.Success(
            termName = term.name,
            courseCount = parsed.courses.size,
            sessionCount = parsed.sessionCount,
            warnings = parsed.warnings,
        )
    }

    /**
     * Builds the semester for a scraped import.
     *
     * The report response carries `calendarId` on the URL but **no dates at all**, so
     * the term has to come from elsewhere. `v1/rt/teaching_info/semester` is the right
     * source because it needs **no authorisation** — which is what lets a scraped
     * import produce a correct week→date mapping for a student who has no platform
     * credentials at all.
     *
     * Falls back to a locally estimated term so an import is never blocked, and says
     * so in the warnings rather than silently inventing dates.
     */
    private suspend fun resolveTermForScrape(
        calendarId: String?,
        parsed: Imported,
    ): TermCalendar {
        // The parser's own term wins. It comes from the reference's verbatim preset table
        // (calendarId 122 -> 2026-09-14, 16 weeks), which is authoritative for the
        // **1 system's** calendarId. Re-deriving from the open platform's `semester`
        // endpoint instead would mix two systems' semester boundaries — and since every
        // date in the grid, the widget and the calendar is computed from week 1, a
        // one-week disagreement silently shifts the whole timetable.
        parsed.term?.toTermCalendar()?.let { return it }

        val highestWeek = parsed.sessionsByCourse.values.flatten()
            .maxOfOrNull { it.weeks.lastWeek ?: 0 } ?: 0

        val fromApi = runCatching { api.semesters() }.getOrNull().orEmpty()
        val today = LocalDate.now()
        val match = fromApi
            .mapNotNull { dto ->
                val begin = dto.beginDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val end = dto.endDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (begin == null || end == null) null else Triple(dto, begin, end)
            }
            .let { candidates ->
                // Prefer the semester containing today; otherwise the most recent one.
                candidates.firstOrNull { (_, begin, end) -> !today.isBefore(begin) && !today.isAfter(end) }
                    ?: candidates.maxByOrNull { (_, begin, _) -> begin }
            }

        if (match != null) {
            val (dto, begin, end) = match
            val weeks = maxOf(
                highestWeek,
                ((java.time.temporal.ChronoUnit.DAYS.between(begin, end) / 7).toInt() + 1),
            )
            return TermCalendar.fromApi(
                calendarId = calendarId ?: dto.id ?: "scraped",
                name = dto.semesterName ?: "本学期",
                year = dto.year?.toIntOrNull() ?: begin.year,
                term = dto.semester?.toIntOrNull() ?: 1,
                beginDay = begin,
                endDay = end,
                totalWeeks = weeks.coerceIn(1, WeekPattern.MAX_WEEK),
                isCurrent = true,
            )
        }

        // No semester list available: anchor week 1 to the Monday of the current week
        // and warn, because every date in the grid depends on this being right.
        val monday = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        val weeks = (highestWeek.takeIf { it > 0 } ?: 17).coerceIn(1, WeekPattern.MAX_WEEK)
        _lastWarnings.value = _lastWarnings.value + listOf(
            "未能获取学期起止日期，已按「本周为第 1 周」估算，请核对课表日期",
        )
        return TermCalendar(
            calendarId = calendarId ?: "scraped",
            name = "本学期",
            year = today.year,
            term = 1,
            firstWeekStart = monday,
            endDate = monday.plusWeeks(weeks.toLong()),
            totalWeeks = weeks,
            isCurrent = true,
        )
    }

    // ------------------------------------------------------ 日历文件导入导出

    /**
     * Serialises the current timetable to an iCalendar document.
     *
     * This path exists because 同济's open platform is closed to individual
     * student developers, so an .ics file is the only import/export route that
     * needs no institutional credentials. Returns null when there is nothing to
     * export, so the caller can say so rather than handing the student an empty file.
     */
    suspend fun exportIcs(): String? {
        val snapshot = observeTimetable().first() ?: return null
        if (snapshot.isEmpty) return null
        return TimetableIcs.export(
            term = snapshot.term,
            courses = snapshot.courses,
            sessions = snapshot.sessions,
            schedule = snapshot.periodSchedule,
            adjustments = snapshot.adjustments,
        )
    }

    /**
     * Replaces the local timetable with the contents of an `.ics` document.
     *
     * The previous term is kept in the database rather than deleted, so an
     * accidental import is recoverable; it simply stops being the current term.
     */
    suspend fun importIcs(text: String): IcsImport {
        val parsed = TimetableIcs.parse(text, currentTerm())
            ?: return IcsImport.Failed("文件里没有可识别的日程")
        if (parsed.courses.isEmpty()) return IcsImport.Failed("文件里没有可识别的课程")

        persist(parsed.term, parsed.courses, parsed.sessionsByCourse)
        _lastWarnings.value = parsed.warnings
        return IcsImport.Success(
            termName = parsed.term.name,
            courseCount = parsed.courses.size,
            sessionCount = parsed.sessionCount,
            warnings = parsed.warnings,
        )
    }

    /**
     * Writes the current semester into the device calendar.
     *
     * Deliberately takes a snapshot of the *current* [Timetable] rather than
     * querying again, so the calendar matches exactly what the student sees —
     * including the resolved 调休 state, which is what makes 补课 days land on the
     * right date instead of being silently dropped.
     *
     * @param calendarId the calendar the student picked; an app cannot create one, so
     *   this always refers to a calendar that already exists on the device.
     * @param scope how much of the term to write; defaults to the current week.
     */
    suspend fun registerCurrentTermToCalendar(
        calendarId: Long,
        scope: CalendarScope = CalendarScope.CURRENT_WEEK,
        reminderMinutes: Int = 15,
    ): CalendarRegistration {
        val snapshot = observeTimetable().first() ?: return CalendarRegistration.NoTimetable
        if (snapshot.isEmpty) return CalendarRegistration.NoTimetable

        val result = calendarSync.sync(
            courses = snapshot.courses,
            sessions = snapshot.sessions,
            term = snapshot.term,
            schedule = snapshot.periodSchedule,
            adjustments = snapshot.adjustments,
            calendarId = calendarId,
            scope = scope,
            fromDate = snapshot.term.firstWeekStart,
            toDate = snapshot.term.endDate,
            reminderMinutes = reminderMinutes,
        )
        return when (result) {
            is CalendarSyncResult.Success ->
                CalendarRegistration.Done(result.eventsInserted, result.skippedHolidays)
            CalendarSyncResult.NoPermission -> CalendarRegistration.NoPermission
            is CalendarSyncResult.Failed -> CalendarRegistration.Failed(result.message)
        }
    }

    /** Calendars the student can register into, best candidate first. */
    suspend fun availableCalendars(): List<DeviceCalendar> = calendarSync.availableCalendars()

    /**
     * Removes every event this app has written to the system calendar.
     *
     * @return how many rows were deleted, so the screen can say whether anything was there.
     */
    suspend fun removeTimetableFromCalendar(): Int = calendarSync.removeWrittenEvents()

    /**
     * Not `suspend`: it only hands back a cold [Flow], and marking it suspend
     * would force callers into a coroutine just to obtain the stream.
     */
    fun observeCredentials(): Flow<Credentials> = credentials.credentials

    suspend fun saveClient(clientId: String, clientSecret: String) =
        credentials.setClient(clientId, clientSecret)

    suspend fun saveStudent(userId: String, userName: String?) =
        credentials.setStudent(userId, userName)

    suspend fun signOut() = credentials.clearAll()

    // ------------------------------------------------------------ internals

    private suspend fun persist(
        term: TermCalendar,
        courses: List<Course>,
        sessionsByCourse: Map<Int, List<CourseSession>>,
    ) {
        db.withTransaction {
            // Snapshot customisations before the wipe so they can be re-applied.
            val previous = courseDao.allForTerm(term.calendarId)
                .associate { it.course.customizationKey to it.course }

            termDao.upsert(
                term.copy(isCurrent = true).toEntity(),
            )
            termDao.markCurrent(term.calendarId)
            courseDao.deleteForTerm(term.calendarId)

            for ((index, course) in courses.withIndex()) {
                val prev = previous[customizationKeyOf(course)]
                val merged = course.copy(
                    colorIndex = course.colorIndex ?: prev?.colorIndex,
                    note = prev?.note ?: course.note,
                    hidden = prev?.hidden ?: course.hidden,
                )
                val newId = courseDao.insertCourse(merged.toEntity(term.calendarId))
                val slots = sessionsByCourse[index].orEmpty()
                if (slots.isNotEmpty()) {
                    courseDao.insertSessions(slots.map { it.copy(courseId = newId).toEntity() })
                }
            }
        }
    }

    /** Must mirror [CourseEntity.customizationKey] so merges line up. */
    private fun customizationKeyOf(course: Course): String =
        course.teachingUnitKey
}

/**
 * The key used to match a course across imports. Declared here rather than on
 * [Course] because it exists purely to reconcile two persisted generations.
 */
private val Course.teachingUnitKey: String
    get() = teachingClassId?.toString() ?: courseCode ?: name
