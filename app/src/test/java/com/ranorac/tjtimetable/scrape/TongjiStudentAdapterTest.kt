package com.ranorac.tjtimetable.scrape

import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.WeekPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Acceptance for the 同济 student adapter.
 *
 * The **expectations** come from the reference implementation's own xUnit suite
 * (`TongjiReportFormatTests.cs`, `E2ETimetableTests.cs`, `ImportTests.cs`): the same merge rule, the
 * same room/teacher fallbacks, the same week-array→mask semantics, the same skip-with-a-note
 * behaviour, the same detect scores. The **payloads** are synthetic ([Samples]) with invented
 * values but real field names, because a captured 教务 response belongs to a specific student and
 * this project is published — see [Samples] for the full field list.
 */
class TongjiStudentAdapterTest {

    // --------------------------------------------------------------- personal

    private fun personal(): Imported =
        TongjiStudentAdapter.parse(Samples.PERSONAL)

    private fun course(imported: Imported, name: String): Course =
        imported.courses.firstOrNull { it.name == name }
            ?: error("no course named $name in ${imported.courses.map { it.name }}")

    private fun sessions(imported: Imported, name: String): List<CourseSession> {
        val index = imported.courses.indexOfFirst { it.name == name }
        assertTrue("course $name missing", index >= 0)
        return imported.sessionsByCourse[index].orEmpty()
    }

    @Test
    fun personalCaptureIsDetectedAsTongji() {
        assertEquals(0.98, TongjiStudentAdapter.detect(Samples.PERSONAL), 1e-9)
        assertTrue(TongjiStudentAdapter.looksLikeTimetable(Samples.PERSONAL))
    }

    @Test
    fun personalCaptureSkipsCoursesWithoutSlots() {
        val imported = personal()

        assertTrue(
            imported.diagnostics.toString(),
            imported.diagnostics.any { it.code == "tongji.personal" && it.level == DiagnosticLevel.INFO },
        )
        val noSchedule = imported.diagnostics.firstOrNull { it.code == "tongji.noSchedule" }
        assertNotNull("expected a tongji.noSchedule diagnostic", noSchedule)
        assertTrue(noSchedule!!.message, noSchedule.message.contains("1 门课"))
        assertTrue(imported.diagnostics.any { it.code == "tongji.summary" })

        // 6 selectedCourses, 测试课程D has no times → 5 courses; 7 times, 测试课程B's 9 merge to 1.
        assertEquals(5, imported.courses.size)
        assertEquals(6, imported.sessionCount)
        assertFalse(imported.courses.map { it.name }.contains("测试课程D"))
        // 被跳过的课程不该有 Session 残留
        assertTrue(imported.sessionsByCourse.values.all { it.isNotEmpty() })
    }

    @Test
    fun personalCourseFieldsMapAsTheReferenceMapsThem() {
        val imported = personal()
        val courseA = course(imported, "测试课程A")

        assertEquals("TSTA001", courseA.courseCode)
        assertEquals("TSTA0A01", courseA.classCode)
        assertEquals(9000000000000011L, courseA.teachingClassId)
        // The reference keeps 姓名(工号): the 工号 is half of a teacher fingerprint.
        assertEquals("测试教师甲(10001)", courseA.teacher)

        val thursday = sessions(imported, "测试课程A").single { it.dayOfWeek == DayOfWeek.THURSDAY }
        assertEquals(5, thursday.startUnit)
        assertEquals(6, thursday.endUnit)
        assertEquals("测试楼201", thursday.room)
        // weeks arrives as an array [1,3,5,...] → bit0 = week 1, i.e. 单周.
        assertEquals(WeekPattern.of(1, 3, 5, 7, 9, 11, 13, 15), thursday.weeks)
        assertTrue(thursday.weeks.isOddOnly)
        assertEquals("单周", thursday.weeks.parityLabel)
        assertEquals(2, thursday.unitSpan)
        // rawWeeks keeps the server's own field verbatim for diagnosis.
        assertEquals("[1,3,5,7,9,11,13,15]", thursday.rawWeeks)
    }

    @Test
    fun personalMultiSlotCoursesHaveSortedSessions() {
        val imported = personal()
        val slots = sessions(imported, "测试课程A")

        assertEquals(2, slots.size)
        // Ordered by day then first 节, like the reference's ThenBy(StartSlot).
        assertEquals(
            slots.sortedWith(compareBy({ it.dayOfWeek.value }, { it.startUnit })),
            slots,
        )
        assertEquals(listOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), slots.map { it.dayOfWeek })
    }

    @Test
    fun personalMergesSameCellTimesAndUnionsWeeks() {
        val imported = personal()
        val courseB = course(imported, "测试课程B")
        val slots = sessions(imported, "测试课程B")

        // 9 times in the response, all in the same cell (周三 9-10 节 测试楼301) → 1 block.
        assertEquals(1, slots.size)
        val session = slots.single()
        assertEquals(DayOfWeek.WEDNESDAY, session.dayOfWeek)
        assertEquals(9, session.startUnit)
        assertEquals(10, session.endUnit)
        assertEquals("测试楼301", session.room)
        // The union of the 8 single weeks and [1,2,3,4,13,14,15,16] is the whole term.
        assertEquals(WeekPattern.range(1, 16), session.weeks)
        assertEquals(16, session.weeks.count)
        assertFalse(session.weeks.isOddOnly)
        assertNull(session.weeks.parityLabel)
        // Nine teachers, in first-seen order, all kept on the course.
        assertEquals(9, courseB.teacher!!.split(",").size)
        assertTrue(courseB.teacher!!, courseB.teacher!!.startsWith("测试教师乙(10002)"))
        assertTrue(courseB.teacher!!, courseB.teacher!!.contains("测试教师癸(10010)"))
        // The merged entry records every weeks array it merged, so the union is auditable.
        assertTrue(session.rawWeeks!!, session.rawWeeks!!.contains(" | "))
    }

    @Test
    fun personalCoursesWithoutARoomKeepNull() {
        val imported = personal()
        // 测试课程E carries an empty roomIdI18n and no roomLable.
        assertNull(sessions(imported, "测试课程E").first().room)
    }

    @Test
    fun personalEvenWeekCourseIsEvenParity() {
        val imported = personal()
        val session = sessions(imported, "测试课程F").single()

        assertTrue(session.weeks.isEvenOnly)
        assertEquals("双周", session.weeks.parityLabel)
        assertEquals(WeekPattern.of(2, 4, 6, 8, 10, 12, 14, 16), session.weeks)
    }

    @Test
    fun emptyWeeksArrayMeansTheWholeTerm() {
        val imported = personal()
        val session = sessions(imported, "测试课程C").single()

        // The reference hard-codes Weeks.FullMask(16) here, whatever the term's length is.
        assertEquals(WeekPattern.range(1, 16), session.weeks)
        // An empty array is still an array: recorded verbatim, which is what makes the fallback
        // auditable from the database alone.
        assertEquals("[]", session.rawWeeks)
    }

    @Test
    fun sessionsAreKeyedByTheIndexOfTheirCourse() {
        val imported = personal()

        assertEquals((0 until imported.courses.size).toList(), imported.sessionsByCourse.keys.sorted())
        for ((index, slots) in imported.sessionsByCourse) {
            for (slot in slots) assertEquals(index.toLong(), slot.courseId)
        }
    }

    @Test
    fun theTermComesFromTheBodysCalendarIdAndTheBuiltInTable() {
        val imported = personal()
        val term = imported.term!!

        assertEquals("122", imported.calendarId)
        assertEquals("122", term.id)
        assertEquals("2026-2027学年第1学期", term.name)
        assertEquals("2026-09-14", term.startDate)
        assertEquals(16, term.totalWeeks)
        assertEquals(11, term.slots.size)
        assertEquals(Slot(1, "08:00", "08:45"), term.slots[0])
        assertEquals(Slot(11, "20:10", "20:55"), term.slots[10])
        assertEquals("08:00", term.slotBegin(1))
        assertEquals("20:55", term.slotEnd(11))
        // 走内置学期表 → 不该有「未知学期」或「缺少 beginDay」警告
        assertFalse(imported.diagnostics.any { it.code == "tongji.term.unknown" })
        assertFalse(imported.diagnostics.any { it.code == "tongji.term.startDate" })
        assertEquals(listOf("<粘贴内容>:已选课程 6 门"), imported.sources)
        assertTrue(imported.warnings.isEmpty())
    }

    // ----------------------------------------------------------------- report

    private fun report(termId: String? = Samples.KNOWN_CALENDAR_ID): Imported =
        TongjiStudentAdapter.parse(Samples.REPORT, termId = termId)

    @Test
    fun reportCaptureIsParsedWithTheReferenceFieldNames() {
        assertEquals(0.97, TongjiStudentAdapter.detect(Samples.REPORT), 1e-9)

        val imported = report()

        assertTrue(
            imported.diagnostics.toString(),
            imported.diagnostics.any { it.code == "tongji.report" && it.level == DiagnosticLevel.INFO },
        )
        val noSchedule = imported.diagnostics.first { it.code == "tongji.noSchedule" }
        assertTrue(noSchedule.message, noSchedule.message.contains("1 门课"))

        // 10 course items / 18 timeTableList entries: 测试课程E skipped (-1), one group of 9 merged (-8).
        assertEquals(9, imported.courses.size)
        assertEquals(10, imported.sessionCount)
        assertFalse(imported.courses.map { it.name }.contains("测试课程E"))
        assertTrue(imported.sessionsByCourse.values.all { it.isNotEmpty() })
    }

    @Test
    fun reportCourseFieldsFollowTheMeasuredRoomAndTeacherRules() {
        val imported = report()

        val courseA = course(imported, "测试课程A")
        assertEquals(9000000000000011L, courseA.teachingClassId)
        assertEquals("TSTA0A01", courseA.classCode)
        assertEquals("TSTA001", courseA.courseCode)
        // The teacher comes from timeTableList[].teacherName, not the course-level name.
        assertEquals("测试教师甲(10001)", courseA.teacher)
        assertEquals("测试校区", courseA.campus)
        assertEquals(
            listOf("测试楼201", "测试楼201"),
            sessions(imported, "测试课程A").sortedBy { it.dayOfWeek.value }.map { it.room },
        )

        // roomIdI18n is empty for these two, so the reference falls back to roomLable.
        val courseB = course(imported, "测试课程B")
        assertEquals("TSTB002", courseB.courseCode)
        assertEquals("线上课堂", sessions(imported, "测试课程B").single().room)
        assertEquals("测试教师乙(10002)", courseB.teacher)

        assertEquals("测试操场（2号）", sessions(imported, "测试课程C").single().room)
    }

    @Test
    fun reportWeekArraysBecomeMasksAndMergedCellsUnion() {
        val imported = report()

        val evenThursday = sessions(imported, "测试课程A").single { it.dayOfWeek == DayOfWeek.THURSDAY }
        assertEquals(WeekPattern.of(2, 4, 6, 8, 10, 12, 14, 16), evenThursday.weeks)
        assertEquals("双周", evenThursday.weeks.parityLabel)

        assertEquals(WeekPattern.of(11, 12, 13, 14), sessions(imported, "测试课程F").single().weeks)
        assertEquals(WeekPattern.range(1, 12), sessions(imported, "测试课程G").single().weeks)

        // 测试课程D: 9 timeTableList entries in one cell, weeks union → the whole term, 9 teachers.
        val courseD = course(imported, "测试课程D")
        val session = sessions(imported, "测试课程D").single()
        assertEquals(DayOfWeek.WEDNESDAY, session.dayOfWeek)
        assertEquals(9, session.startUnit)
        assertEquals(10, session.endUnit)
        assertEquals("测试楼301", session.room)
        assertEquals(WeekPattern.range(1, 16), session.weeks)
        assertEquals(9, courseD.teacher!!.split(",").size)
        assertTrue(courseD.teacher!!, courseD.teacher!!.startsWith("测试教师丁(10004)"))
        assertTrue(courseD.teacher!!, courseD.teacher!!.contains("测试教师己(10006)"))
        // The course-level 姓名,姓名 list must not be mixed in when the rows carried names.
        assertFalse(courseD.teacher!!, courseD.teacher!!.contains("测试教师庚"))
    }

    @Test
    fun reportAppendsTheRowTeacherCodeOnlyWhenTheNameLacksOne() {
        val imported = report()

        // 姓名 without a 工号 in the text → the row's teacherCode is appended.
        assertEquals("测试教师癸(10009)", course(imported, "测试课程H").teacher)
        // 姓名 that already carries one → appended exactly once (the reference's
        // "姓名(工号)(工号)" trap: the regex only recognises 汉字 names).
        val courseI = course(imported, "测试课程I").teacher!!
        assertEquals("测试教师子(10010)", courseI)
        assertFalse(courseI, courseI.contains("(10010)(10010)"))
    }

    @Test
    fun reportFallsBackToTheCourseLevelTeacherNameList() {
        val imported = report()

        // No teacher on the row at all → the course-level 「姓名,姓名」 list is split.
        assertEquals("测试教师丑, 测试教师寅", course(imported, "测试课程J").teacher)
    }

    @Test
    fun reportCarriesTheCourseFieldsTheReferenceIgnores() {
        val imported = report()
        val courseA = course(imported, "测试课程A")

        // DELIBERATE divergence from the reference. The payload carries all four and the
        // reference adapter drops them; they are precisely what the course detail sheet
        // displays, so dropping them produced a detail page of em dashes for no gain.
        assertEquals("测试01班", courseA.className)
        assertEquals(3.0, courseA.credits!!, 1e-9)
        assertEquals("考试", courseA.assessmentMode)
        assertEquals("线下授课", courseA.teachingWay)

        assertEquals("TSTA001", courseA.courseCode)
        // 开课学院 (facultyI18n) exists only on the flat shape, so it stays null for the report.
        assertNull(courseA.department)
    }

    @Test
    fun aMissingCalendarIdFallsBackToSixteenWeeksWithAReadableDiagnostic() {
        val imported = report(termId = null)

        assertNull(imported.term!!.startDate)
        assertEquals(16, imported.term!!.totalWeeks)
        assertTrue(
            imported.diagnostics.toString(),
            imported.diagnostics.any { it.code == "tongji.term.unknown" && it.level == DiagnosticLevel.WARN },
        )
        // The timetable itself is parsed regardless, and the WARN reaches our `warnings`.
        assertEquals(9, imported.courses.size)
        assertEquals(1, imported.warnings.size)
        assertTrue(imported.warnings.first(), imported.warnings.first().startsWith("学期 未知"))
    }

    @Test
    fun theCalendarPayloadSuppliesTheStartDateAndItsOwnSlotTable() {
        val imported = TongjiStudentAdapter.parse(
            ImportInput(
                files = listOf(
                    ImportFile("test-calendar.json", Samples.SCHOOL_CALENDAR),
                    ImportFile("test-personal.json", Samples.PERSONAL),
                ),
            ),
        )

        // The body's calendarId (122) matches no term in the calendar, so the reference's
        // `currentTermFlag` fallback picks the calendar's own term.
        assertEquals("9122", imported.term!!.id)
        assertEquals("测试学年第一学期", imported.term!!.name)
        // beginDay 1788739200000 is UTC midnight of a Monday → 2026-09-07, week 1.
        assertEquals("2026-09-07", imported.term!!.startDate)
        assertEquals(LocalDate.parse("2026-09-07").dayOfWeek, DayOfWeek.MONDAY)
        assertEquals(16, imported.term!!.totalWeeks)
        // The payload's own 节次 table wins over the built-in 同济 table (08:05 != 08:00).
        assertEquals(11, imported.term!!.slots.size)
        assertEquals(Slot(1, "08:05", "08:50"), imported.term!!.slots[0])
        assertEquals(5, imported.courses.size)
        assertFalse(imported.diagnostics.any { it.code == "tongji.term.startDate" })
        assertTrue(
            imported.sources.toString(),
            imported.sources.any { it.contains("校历 2 个学期") } &&
                imported.sources.any { it.contains("已选课程") },
        )
    }

    // ------------------------------------------------------------------- flat

    @Test
    fun flatWeekStateRowsStillParse() {
        assertEquals(0.9, TongjiStudentAdapter.detect(Samples.FLAT), 1e-9)

        val imported = TongjiStudentAdapter.parse(Samples.FLAT)

        assertTrue(imported.diagnostics.any { it.code == "tongji.flat" })
        val courseK = course(imported, "测试课程K")
        val session = sessions(imported, "测试课程K").single()
        assertEquals("测试楼202", session.room)
        // 教师指纹从 value 文本里正则解析，而不是把整行文本当名字。
        assertEquals("测试教师卯(10016)", courseK.teacher)
        assertEquals(WeekPattern.range(1, 16), session.weeks)
        assertEquals("weekState=65535", session.rawWeeks)
        // 课程级 facultyI18n / campusI18n 在这条路上是 mapped 的（与另外两条路不同）。
        assertEquals("测试学院", courseK.department)
        assertEquals("测试校区", courseK.campus)
        // The flat course id comes from teachingClassId, so it survives as a 教学班 id.
        assertEquals(9101L, courseK.teachingClassId)
    }

    @Test
    fun flatIdsFallBackLikeTheReference() {
        val withCode = """
        {"data": [{"code": "FLT01", "courseCode": "FLT", "courseName": "测试课程K", "dayOfWeek": 2,
                   "timeStart": 3, "timeEnd": 4, "weekState": 65535}]}
        """
        val parsed = TongjiStudentAdapter.buildCoursesFromFlat(arrayOf(withCode))
        assertEquals("FLT01", parsed.single().id)
        // …and a non-numeric id cannot become a 教学班 Long in this app's model.
        assertNull(parsed.single().toDomainCourse().teachingClassId)

        val withoutCode = """
        {"data": [{"courseCode": "FLT", "courseName": "测试课程K", "dayOfWeek": 2, "timeStart": 3,
                   "timeEnd": 4, "weekState": 65535}]}
        """
        val parsed2 = TongjiStudentAdapter.buildCoursesFromFlat(arrayOf(withoutCode))
        assertEquals("FLT-2-3", parsed2.single().id)
    }

    /** `data` unwrapped to its array, as `Classify` sees it. */
    private fun arrayOf(text: String): List<kotlinx.serialization.json.JsonElement> =
        (AdapterInput.unwrapData(AdapterInput.tryParseJson(text)!!) as kotlinx.serialization.json.JsonArray).toList()

    @Test
    fun flatTeacherCodesAreUsedWhenThereIsNoValueText() {
        val imported = TongjiStudentAdapter.parse(Samples.FLAT)
        val courseL = course(imported, "测试课程L")

        // No `value` and no `newValue` → the `teacherCodes` array is used as-is.
        assertEquals("10017, 10018", courseL.teacher)
    }

    @Test
    fun weekStateIsAnUnsignedThirtyTwoBitMask() {
        // 0x8000_0000 is week 32 — the sign bit, which is exactly why the reference casts to uint.
        val week32 = sessions(TongjiStudentAdapter.parse(Samples.FLAT), "测试课程L").single()
        assertEquals(listOf(32), week32.weeks.weekNumbers)
        assertEquals("weekState=-2147483648", week32.rawWeeks)

        // 21845 = 0x5555 = 第 1,3,5,7,9,11,13,15 周
        val odd = TongjiStudentAdapter.parse(
            Samples.envelope(
                """
                [{"teachingClassId": 9103, "courseName": "测试课程M", "dayOfWeek": 1, "timeStart": 1,
                  "timeEnd": 2, "weekState": 21845}]
                """.trimIndent(),
            ),
        )
        val session = sessions(odd, "测试课程M").single()
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), session.weeks.weekNumbers)
        assertEquals("单周", session.weeks.parityLabel)
    }

    // ------------------------------------------------- reference import tests

    @Test
    fun teacherFingerprintRegexMatchesTheReferenceBoundaries() {
        assertEquals(
            listOf("测试教师甲(10001)"),
            TongjiStudentAdapter.parseTeachersFromValue("测试课程A 测试教师甲(10001) 星期一1-2节"),
        )
        assertEquals(
            listOf("测试教师甲(10001)", "测试教师乙(10002)"),
            TongjiStudentAdapter.parseTeachersFromValue("测试教师甲(10001) 测试教师乙(10002)"),
        )
        assertTrue(TongjiStudentAdapter.parseTeachersFromValue("没有括号").isEmpty())
        assertTrue(TongjiStudentAdapter.parseTeachersFromValue("测试(12)").isEmpty())      // 工号少于 3 位
        assertTrue(TongjiStudentAdapter.parseTeachersFromValue("测试(1234567)").isEmpty()) // 工号多于 6 位
        assertTrue(TongjiStudentAdapter.parseTeachersFromValue("A(12345)").isEmpty())      // 姓名不是 2-8 个汉字
        assertTrue(TongjiStudentAdapter.parseTeachersFromValue(null).isEmpty())
    }

    @Test
    fun theThreeIdsAreNotConfused() {
        val selected = """
        {"course": {"courseName": "测试课程N", "courseCode": "TSTN001", "teachClassId": 9000000000000099,
                    "teachClassCode": "TSTN0N01",
                    "times": [{"dayOfWeek": 3, "timeStart": 1, "timeEnd": 2, "weeks": [1]}]}}
        """
        val courseN = TongjiStudentAdapter.parse(Samples.personalWith(selected)).courses.single()

        assertEquals(9000000000000099L, courseN.teachingClassId)
        assertEquals("TSTN0N01", courseN.classCode)
        assertEquals("TSTN001", courseN.courseCode)
    }

    @Test
    fun sameCellTimesMergeAndUnionWeeks() {
        val payload = Samples.personalWith(
            """
            {"course": {"courseName": "测试课程O", "courseCode": "TSTO001", "teachClassId": 9000000000000021,
              "teachClassCode": "TSTO0O01",
              "times": [
                {"dayOfWeek": 3, "timeStart": 9, "timeEnd": 10, "weeks": [1, 2], "roomIdI18n": "测试楼301",
                 "teacherCodeI18n": "测试教师甲", "teacherCode": "10001"},
                {"dayOfWeek": 3, "timeStart": 9, "timeEnd": 10, "weeks": [3], "roomIdI18n": "测试楼301",
                 "teacherCodeI18n": "测试教师乙", "teacherCode": "10002"}
              ]}}
            """.trimIndent(),
        )
        val imported = TongjiStudentAdapter.parse(payload)

        val slots = imported.sessionsByCourse[0]!!
        assertEquals(1, slots.size)
        assertEquals(WeekPattern.of(1, 2, 3), slots.single().weeks)
        assertEquals("测试教师甲(10001), 测试教师乙(10002)", imported.courses[0].teacher)
    }

    @Test
    fun timesInDifferentRoomsDoNotMerge() {
        val payload = Samples.personalWith(
            """
            {"course": {"courseName": "测试课程P", "courseCode": "TSTP001", "teachClassId": 9000000000000022,
              "teachClassCode": "TSTP0P01",
              "times": [
                {"dayOfWeek": 3, "timeStart": 9, "timeEnd": 10, "weeks": [1], "roomIdI18n": "测试楼301"},
                {"dayOfWeek": 3, "timeStart": 9, "timeEnd": 10, "weeks": [2], "roomIdI18n": "测试楼302"}
              ]}}
            """.trimIndent(),
        )
        assertEquals(2, TongjiStudentAdapter.parse(payload).sessionsByCourse[0]!!.size)
    }

    @Test
    fun aMissingRoomIsNullNotZero() {
        val payload = Samples.personalWith(
            """
            {"course": {"courseName": "测试课程Q", "courseCode": "TSTQ001", "teachClassId": 9000000000000023,
              "teachClassCode": "TSTQ0Q01",
              "times": [{"dayOfWeek": 1, "timeStart": 1, "timeEnd": 2, "weeks": [1, 2, 3]}]}}
            """.trimIndent(),
        )
        val session = TongjiStudentAdapter.parse(payload).sessionsByCourse[0]!!.single()

        assertNull(session.room)
        assertEquals(WeekPattern.of(1, 2, 3), session.weeks)
    }

    @Test
    fun detectScoresMatchTheReference() {
        assertEquals(0.98, TongjiStudentAdapter.detect("""{"data": {"selectedCourses": []}}"""), 1e-9)
        assertEquals(0.9, TongjiStudentAdapter.detect("""{"data": [{"dayOfWeek": 1, "weekState": 65535}]}"""), 1e-9)
        assertEquals(0.0, TongjiStudentAdapter.detect("""{"foo":1}"""), 1e-9)
        assertEquals(0.0, TongjiStudentAdapter.detect("not json at all"), 1e-9)
        assertEquals(0.0, TongjiStudentAdapter.detect(null), 1e-9)
        // A login redirect is the likeliest thing to be pasted by mistake.
        assertEquals(0.0, TongjiStudentAdapter.detect("""{"code":401,"msg":"未登录"}"""), 1e-9)
    }

    @Test
    fun anEmptySelectedCoursesArrayIsRecognisedButReportsNoData() {
        // Detect says "recognised" (0.98) so the user gets the useful diagnostic…
        assertEquals(0.98, TongjiStudentAdapter.detect("""{"code": 200, "data": {"selectedCourses": []}}"""), 1e-9)

        // …but the parse reports the error rather than silently producing an empty timetable.
        val imported = TongjiStudentAdapter.parse("""{"code": 200, "data": {"selectedCourses": []}}""")
        assertTrue(imported.courses.isEmpty())
        assertTrue(
            imported.diagnostics.toString(),
            imported.diagnostics.any { it.code == "tongji.schedule.missing" && it.level == DiagnosticLevel.ERROR },
        )
        // No calendarId in the body and none supplied → a second (WARN) diagnostic about the term.
        assertEquals(2, imported.warnings.size)
        // A caller that only wants "import it when it really is 同济课表数据" can say so directly.
        assertNull(TongjiStudentAdapter.parseOrNull("""{"code": 200, "data": {"selectedCourses": []}}"""))
        assertNotNull(TongjiStudentAdapter.parseOrNull(Samples.PERSONAL))
    }

    @Test
    fun anUnknownCalendarIdDegradesToSixteenWeeks() {
        val unknown = Samples.envelope(
            """
            {"calendarId": 999999, "selectedCourses": [
              {"course": {"courseName": "测试课程R", "courseCode": "TSTR001", "teachClassId": 9000000000000024,
               "teachClassCode": "TSTR0R01",
               "times": [{"dayOfWeek": 1, "timeStart": 1, "timeEnd": 2, "weeks": [1, 2, 3],
                          "roomIdI18n": "测试楼101"}]}}
            ]}
            """.trimIndent(),
        )
        val imported = TongjiStudentAdapter.parse(unknown)

        assertEquals(1, imported.courses.size)
        assertNull(imported.term!!.startDate)
        assertEquals(16, imported.term!!.totalWeeks)
        assertTrue(
            imported.diagnostics.toString(),
            imported.diagnostics.any { it.code == "tongji.term.unknown" && it.level == DiagnosticLevel.WARN },
        )
        assertEquals(WeekPattern.of(1, 2, 3), imported.sessionsByCourse[0]!!.single().weeks)
        // The 16-week fallback still carries the full 同济 作息表, because MakeDefaultSlots() with
        // the default count returns that table verbatim.
        assertEquals(11, imported.term!!.slots.size)
        assertEquals("08:00", imported.term!!.slotBegin(1))
        assertEquals("20:55", imported.term!!.slotEnd(11))
    }

    // ------------------------------------------------------------ degradation

    @Test
    fun truncatedJsonYieldsWarningsInsteadOfThrowing() {
        val truncated = Samples.PERSONAL.substring(0, Samples.PERSONAL.length / 2)

        val imported = TongjiStudentAdapter.parse(truncated)

        assertTrue(imported.courses.isEmpty())
        assertTrue(imported.sessionsByCourse.isEmpty())
        assertEquals(0, imported.sessionCount)
        // Two non-fatal problems: the term could not be resolved, and no data matched.
        assertEquals(2, imported.warnings.size)
        assertTrue(imported.warnings.toString(), imported.warnings.any { it.contains("没有找到课表数据") })
        assertNull(TongjiStudentAdapter.parseOrNull(truncated))
        // The term still resolves to a placeholder, so the caller can still report which semester
        // the import failed for.
        assertEquals("unknown", imported.term!!.id)
    }

    @Test
    fun htmlAndEmptyBodiesDegradeGracefully() {
        for (body in listOf("<!doctype html><html></html>", "", "   ", "[1,2,3]")) {
            val imported = TongjiStudentAdapter.parse(body)
            assertTrue(body, imported.courses.isEmpty())
            assertTrue(body, imported.warnings.isNotEmpty())
        }
    }

    @Test
    fun aCourseWithUnusableTimesIsSkippedWithADiagnosticNotAnException() {
        val payload = Samples.envelope(
            """
            {"calendarId": 122, "selectedCourses": [
              {"course": {"courseName": "测试课程S", "teachClassId": 1,
                "times": [{"timeStart": 1, "timeEnd": 2, "weeks": [1]}]}},
              {"course": {"courseName": "测试课程T", "teachClassId": 2,
                "times": [{"dayOfWeek": 9, "timeStart": 1, "timeEnd": 2, "weeks": [1]}]}},
              {"course": {"courseName": "测试课程U", "teachClassId": 3,
                "times": [{"dayOfWeek": 1, "timeStart": 3, "timeEnd": 4,
                           "weeks": [1, "x", 33, 0, -2, 17]}]}}
            ]}
            """.trimIndent(),
        )
        val imported = TongjiStudentAdapter.parse(payload)

        assertEquals(listOf("测试课程U"), imported.courses.map { it.name })
        // Out-of-range weeks are ignored, valid ones kept.
        assertEquals(WeekPattern.of(1, 17), imported.sessionsByCourse[0]!!.single().weeks)
        val note = imported.diagnostics.first { it.code == "tongji.noSchedule" }
        assertTrue(note.message, note.message.contains("有 2 门课"))
        // Info-level, so it is a diagnostic, not one of our user-facing warnings.
        assertTrue(imported.warnings.isEmpty())
    }

    @Test
    fun twoParsesOfTheSamePayloadAgree() {
        val first = personal()
        val second = personal()

        // Re-importing the same capture must produce the same order, so the index-keyed sessions
        // stay attached to the same course.
        assertEquals(first.courses.map { it.name }, second.courses.map { it.name })
        assertEquals(first.sessionsByCourse, second.sessionsByCourse)
        assertEquals(first.courses, second.courses)
    }

    // ------------------------------------------------------------ integration

    @Test
    fun theResultIsConsumableByTheExistingRepositoryType() {
        val imported = personal()
        val remote = imported.toRemoteImported()

        assertEquals(imported.courses, remote.courses)
        assertEquals(imported.sessionsByCourse, remote.sessionsByCourse)
        assertEquals(imported.warnings, remote.warnings)
        assertEquals(imported.sessionCount, remote.sessionCount)
        assertEquals("tongji-student", imported.adapterId)
        assertEquals("3.1.0", imported.adapterVersion)
        assertEquals("同济大学 · 1 系统个人课表", imported.adapterName)
    }

    @Test
    fun theTermConvertsToTheAppsOwnCalendar() {
        val term = personal().term!!
        val calendar = term.toTermCalendar()

        assertNotNull(calendar)
        assertEquals("122", calendar!!.calendarId)
        assertEquals("2026-2027学年第1学期", calendar.name)
        assertEquals(2026, calendar.year)
        assertEquals(1, calendar.term)
        assertEquals(16, calendar.totalWeeks)
        // 2026-09-14 is the Monday of teaching week 1.
        assertEquals(LocalDate.parse("2026-09-14"), calendar.firstWeekStart)
        assertEquals(DayOfWeek.MONDAY, calendar.firstWeekStart.dayOfWeek)
        assertEquals(1, calendar.weekOf(LocalDate.parse("2026-09-14")))
        assertEquals(DayOfWeek.THURSDAY, calendar.dateOf(1, DayOfWeek.THURSDAY).dayOfWeek)
        assertEquals(LocalDate.parse("2027-01-03"), calendar.weekEnd(16))
    }

    @Test
    fun termsWithoutAStartDateCannotBecomeACalendar() {
        assertNull(report(termId = null).term!!.toTermCalendar())
    }
}
