package com.ranorac.tjtimetable.export

import com.ranorac.tjtimetable.domain.AdjustmentSource
import com.ranorac.tjtimetable.domain.CalendarDayKind
import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.DayAdjustment
import com.ranorac.tjtimetable.domain.Period
import com.ranorac.tjtimetable.domain.PeriodSchedule
import com.ranorac.tjtimetable.domain.ScheduleAdjustmentSet
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.WeekPattern
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TimetableIcs].
 *
 * The property that carries the most weight is the **round trip**:
 * `parse(export(term, courses, sessions))` must give back the same courses, weekdays,
 * 节次 and week sets. Everything else in the module — the RRULE shape, the `EXDATE`
 * bookkeeping, the choice of `DTSTART` — exists only to make that true, and a mistake
 * in any of them shows up as a semester that is silently wrong rather than as an
 * exception. So each 单双周 shape gets its own round-trip test, and a clean round trip
 * is additionally asserted to produce *no warnings at all*, which is what distinguishes
 * "this file re-imports perfectly" from "this file re-imports with repairs".
 *
 * [WeekPattern] is a `@JvmInline value class` with a private constructor, so patterns
 * here are only ever built through its companion factories.
 */
class TimetableIcsTest {

    // ------------------------------------------------------------------ round trips

    @Test
    fun `an every week session round trips through export and parse`() {
        val ics = export(listOf(course()), listOf(slot(weeks = WeekPattern.range(1, 16))))
        val parsed = TimetableIcs.parse(ics, TERM)!!

        assertEquals(1, parsed.courses.size)
        val back = parsed.sessionsByCourse.getValue(0).single()
        assertEquals(DayOfWeek.MONDAY, back.dayOfWeek)
        assertEquals(1, back.startUnit)
        assertEquals(2, back.endUnit)
        assertEquals(WeekPattern.range(1, 16), back.weeks)
        assertEquals("教学南楼305", back.room)
        // A clean round trip must not need a single repair.
        assertEquals(emptyList<String>(), parsed.warnings)
    }

    @Test
    fun `the exported semester header reproduces the term exactly on a re-import`() {
        val ics = export(listOf(course()), listOf(slot(weeks = WeekPattern.range(1, 4))))

        // No fallback passed: the file has to carry enough to rebuild the semester,
        // otherwise week numbers would be re-anchored on whatever semester is current.
        val parsed = TimetableIcs.parse(ics)!!
        assertEquals(TERM, parsed.term)
    }

    @Test
    fun `course name 教学班 teacher and 课程 metadata survive a round trip`() {
        val original = Course(
            id = 1L,
            name = "高等数学A",
            classCode = "10016502",
            teachingClassId = 987654L,
            teacher = "张亚英",
        )
        val ics = export(listOf(original), listOf(slot(courseId = 1L, weeks = WeekPattern.range(1, 4))))
        val back = TimetableIcs.parse(ics, TERM)!!.courses.single()

        assertEquals("高等数学A", back.name)
        assertEquals("10016502", back.classCode)
        assertEquals(987654L, back.teachingClassId)
        assertEquals("张亚英", back.teacher)
    }

    @Test
    fun `an odd week session round trips as an INTERVAL 2 rule`() {
        val weeks = WeekPattern.parse("[1-17单]")
        val ics = export(listOf(course()), listOf(slot(weeks = weeks)))

        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;COUNT=9"))
        assertTrue(ics.contains("DTSTART:20250217T080000"))

        val back = TimetableIcs.parse(ics, TERM)!!.sessionsByCourse.getValue(0).single()
        assertEquals(weeks, back.weeks)
        assertTrue(back.weeks.isOddOnly)
        assertEquals(9, back.weeks.count)
    }

    @Test
    fun `an even week session round trips and starts on its first even week`() {
        val weeks = WeekPattern.parse("[2-16双]")
        val ics = export(listOf(course()), listOf(slot(weeks = weeks)))

        // 双周 has to be anchored on an even week: anchoring on week 1 would put every
        // class in the wrong week for the whole semester.
        assertTrue(ics.contains("DTSTART:20250224T080000"))
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;COUNT=8"))

        val back = TimetableIcs.parse(ics, TERM)!!.sessionsByCourse.getValue(0).single()
        assertEquals(weeks, back.weeks)
        assertTrue(back.weeks.isEvenOnly)
    }

    @Test
    fun `a two run week pattern round trips through two VEVENTs`() {
        val weeks = WeekPattern.range(1, 8) + WeekPattern.range(10, 16)
        val ics = export(listOf(course()), listOf(slot(weeks = weeks)))

        // One rule cannot express a gap, so the run has to be split.
        assertEquals(2, eventCount(ics))
        assertTrue(ics.contains("COUNT=8"))
        assertTrue(ics.contains("COUNT=7"))
        assertTrue(ics.contains("DTSTART:20250421T080000")) // week 10

        val parsed = TimetableIcs.parse(ics, TERM)!!
        val sessions = parsed.sessionsByCourse.getValue(0)
        // …and the two events have to be merged back into the one session they were.
        assertEquals(1, sessions.size)
        assertEquals(weeks, sessions.single().weeks)
        assertEquals(listOf(1..8, 10..16), sessions.single().weeks.ranges())
    }

    @Test
    fun `a single week session round trips as one occurrence`() {
        val weeks = WeekPattern.of(5)
        val ics = export(listOf(course()), listOf(slot(weeks = weeks)))

        assertEquals(1, eventCount(ics))
        assertTrue(ics.contains("COUNT=1"))
        assertTrue(ics.contains("DTSTART:20250317T080000"))
        // A lone odd week is not 单周, so it must not be given an alternating rule.
        assertFalse(ics.contains("INTERVAL"))

        val back = TimetableIcs.parse(ics, TERM)!!.sessionsByCourse.getValue(0).single()
        assertEquals(weeks, back.weeks)
        assertEquals(listOf(5), back.weeks.weekNumbers)
    }

    @Test
    fun `every session of a course round trips with its own weekday and 节次`() {
        val sessions = listOf(
            slot(day = DayOfWeek.MONDAY, start = 1, end = 2, weeks = WeekPattern.range(1, 16)),
            slot(day = DayOfWeek.WEDNESDAY, start = 5, end = 8, weeks = WeekPattern.parse("[1-17单]")),
            slot(day = DayOfWeek.FRIDAY, start = 9, end = 11, weeks = WeekPattern.of(3)),
        )
        val ics = export(listOf(course()), sessions)
        val parsed = TimetableIcs.parse(ics, TERM)!!

        val back = parsed.sessionsByCourse.getValue(0)
        assertEquals(3, back.size)
        assertEquals(sessions.map { it.dayOfWeek }, back.map { it.dayOfWeek })
        assertEquals(sessions.map { it.startUnit to it.endUnit }, back.map { it.startUnit to it.endUnit })
        assertEquals(sessions.map { it.weeks }, back.map { it.weeks })
        assertEquals(emptyList<String>(), parsed.warnings)
    }

    @Test
    fun `a holiday inside a run becomes an EXDATE and comes back as a missing week`() {
        val holiday = ScheduleAdjustmentSet.fromApiCalendar(
            mapOf("2025-03-17" to CalendarDayKind.HOLIDAY.apiCode),
        )
        val ics = export(
            courses = listOf(course()),
            sessions = listOf(slot(weeks = WeekPattern.range(1, 16))),
            adjustments = holiday,
        )

        // Week 5 falls on 2025-03-17; the rule still covers weeks 1-16, so the only way
        // to stop a calendar app drawing that class is to cancel the date by name.
        assertTrue(ics.contains("EXDATE:20250317T080000"))
        assertTrue(ics.contains("COUNT=16"))

        val back = TimetableIcs.parse(ics, TERM)!!.sessionsByCourse.getValue(0).single()
        assertEquals(WeekPattern.range(1, 16) without WeekPattern.of(5), back.weeks)
        assertFalse(back.weeks.contains(5))
        assertEquals(15, back.weeks.count)
    }

    @Test
    fun `export writes no EXDATE when nothing is cancelled`() {
        val ics = export(listOf(course()), listOf(slot(weeks = WeekPattern.range(1, 16))))
        assertFalse(ics.contains("EXDATE"))
    }

    @Test
    fun `a makeup day does not remove the week from the pattern`() {
        // 2025-03-17 is a 补课日 running Tuesday's timetable. The Monday class is not
        // held there — but it still *runs that week*, just a day later, and the .ics
        // format cannot say "moved". Cancelling the date would silently lose the week,
        // which is worse than showing it on its usual weekday.
        val makeup = ScheduleAdjustmentSet.EMPTY.with(
            DayAdjustment(
                date = LocalDate.of(2025, 3, 17),
                kind = CalendarDayKind.WORKDAY,
                followsWeekday = DayOfWeek.TUESDAY,
                source = AdjustmentSource.API,
            ),
        )
        val ics = export(
            courses = listOf(course()),
            sessions = listOf(slot(weeks = WeekPattern.range(1, 16))),
            adjustments = makeup,
        )

        assertFalse(ics.contains("EXDATE"))
        val back = TimetableIcs.parse(ics, TERM)!!.sessionsByCourse.getValue(0).single()
        assertTrue(back.weeks.contains(5))
    }

    @Test
    fun `every teaching day of a run is cancelled when the whole term is a break`() {
        val winter = ScheduleAdjustmentSet.fromApiCalendar(
            mapOf(
                "2025-06-02" to CalendarDayKind.WINTER_BREAK.apiCode,
                "2025-06-09" to CalendarDayKind.WINTER_BREAK.apiCode,
            ),
        )
        val ics = export(
            courses = listOf(course()),
            sessions = listOf(slot(weeks = WeekPattern.range(1, 17))),
            adjustments = winter,
        )

        assertTrue(ics.contains("EXDATE:20250602T080000"))
        assertTrue(ics.contains("20250609T080000"))
        val back = TimetableIcs.parse(ics, TERM)!!.sessionsByCourse.getValue(0).single()
        assertEquals(15, back.weeks.count)
        assertFalse(back.weeks.contains(16))
        assertFalse(back.weeks.contains(17))
    }

    @Test
    fun `a session whose weeks fall outside the term is reported rather than lost`() {
        val shortTerm = TERM.copy(totalWeeks = 4)
        val ics = export(
            courses = listOf(course()),
            sessions = listOf(slot(weeks = WeekPattern.range(1, 8))),
            term = shortTerm,
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!

        // The file's own header wins, so weeks 5-8 have nowhere to land.
        assertEquals(4, parsed.term.totalWeeks)
        val back = parsed.sessionsByCourse.getValue(0).single()
        assertEquals(WeekPattern.range(1, 4), back.weeks)
        assertTrue(parsed.warnings.any { it.contains("不在此学期范围内") })
    }

    // ------------------------------------------------------------------ export shape

    @Test
    fun `export writes the required calendar header and trailer with CRLF endings`() {
        val ics = export(listOf(course()), listOf(slot(weeks = WeekPattern.range(1, 4))))
        val lines = ics.split("\r\n")

        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertTrue(lines.contains("VERSION:2.0"))
        assertTrue(lines.contains("PRODID:${TimetableIcs.PRODID}"))
        assertTrue(lines.contains("CALSCALE:GREGORIAN"))
        assertTrue(lines.contains("X-WR-CALNAME:${TERM.name}"))
        assertEquals("END:VCALENDAR", lines[lines.size - 2])
        // The document ends with CRLF, and no bare LF is left anywhere.
        assertEquals("", lines.last())
        assertFalse(ics.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `export writes floating local times with no zone information`() {
        val ics = export(listOf(course()), listOf(slot(weeks = WeekPattern.range(1, 4))))

        assertFalse(ics.contains("TZID"))
        val stamps = ics.split("\r\n").filter { it.startsWith("DTSTART") || it.startsWith("DTEND") }
        assertEquals(2, stamps.size)
        for (line in stamps) {
            assertTrue("expected a floating stamp, got <$line>", FLOATING.matchEntire(line) != null)
        }
        assertEquals("DTSTART:20250217T080000", stamps[0])
        assertEquals("DTEND:20250217T093500", stamps[1])
    }

    @Test
    fun `export writes a readable description with the app marker`() {
        val ics = export(listOf(course()), listOf(slot(weeks = WeekPattern.range(1, 16))))

        assertEquals(
            "教师：张亚英\\n教学班：10016502\\n节次：1-2节\\n周次：1-16周\\n由 TJ课表 导出 · TJtimetable:1",
            property(ics, "DESCRIPTION"),
        )
        assertEquals("高等数学A", property(ics, "SUMMARY"))
        assertEquals("教学南楼305", property(ics, "LOCATION"))
    }

    @Test
    fun `the 周次 line uses the 单双周 wording from WeekPattern display`() {
        val odd = export(listOf(course()), listOf(slot(weeks = WeekPattern.parse("[1-17单]"))))
        // display() lists the weeks and carries the parity suffix, and the commas in it
        // are escaped because DESCRIPTION is a TEXT value.
        assertTrue(property(odd, "DESCRIPTION")!!.contains("周次：1\\,3\\,5\\,7\\,9\\,11\\,13\\,15\\,17周(单)"))

        val even = export(listOf(course()), listOf(slot(weeks = WeekPattern.parse("[2-16双]"))))
        assertTrue(property(even, "DESCRIPTION")!!.contains("周(双)"))
    }

    @Test
    fun `commas semicolons backslashes and newlines survive an export and an import`() {
        val trickyName = "高数, 上; 实验\\A\n第二行"
        val trickyRoom = "南楼, 305; B"
        val ics = export(
            courses = listOf(course(name = trickyName)),
            sessions = listOf(slot(weeks = WeekPattern.range(1, 4), room = trickyRoom)),
        )

        assertEquals("高数\\, 上\\; 实验\\\\A\\n第二行", property(ics, "SUMMARY"))
        assertEquals("南楼\\, 305\\; B", property(ics, "LOCATION"))

        val parsed = TimetableIcs.parse(ics, TERM)!!
        assertEquals(trickyName, parsed.courses.single().name)
        assertEquals(trickyRoom, parsed.sessionsByCourse.getValue(0).single().room)
    }

    @Test
    fun `a long summary is folded at 75 octets and unfolds back to the same name`() {
        val longName = "现代物理实验（高级）".repeat(12)
        val ics = export(
            courses = listOf(course(name = longName)),
            sessions = listOf(slot(weeks = WeekPattern.range(1, 4))),
        )

        for (line in ics.split("\r\n")) {
            assertTrue(
                "line exceeds 75 octets: <$line>",
                line.toByteArray(Charsets.UTF_8).size <= 75,
            )
        }
        assertTrue("expected a folded line", ics.contains("\r\n "))

        val parsed = TimetableIcs.parse(ics, TERM)!!
        assertEquals(longName, parsed.courses.single().name)
        assertEquals(emptyList<String>(), parsed.warnings)
    }

    @Test
    fun `export skips a session that teaches no weeks`() {
        val ics = export(listOf(course()), listOf(slot(weeks = WeekPattern.EMPTY)))
        assertFalse(ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `export links sessions by course id and by index`() {
        val persisted = export(
            courses = listOf(course(id = 42L)),
            sessions = listOf(slot(courseId = 42L, weeks = WeekPattern.range(1, 4))),
        )
        assertEquals(1, eventCount(persisted))

        // Rows that have not been inserted yet carry the course's index as its id.
        val fresh = export(
            courses = listOf(course(id = 0L)),
            sessions = listOf(slot(courseId = 0L, weeks = WeekPattern.range(1, 4))),
        )
        assertEquals(1, eventCount(fresh))
    }

    @Test
    fun `export skips a session whose course is not in the list`() {
        val ics = export(
            courses = listOf(course(id = 1L)),
            sessions = listOf(slot(courseId = 99L, weeks = WeekPattern.range(1, 4))),
        )
        assertFalse(ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `UIDs are stable across two exports and unique per event`() {
        val courses = listOf(course())
        val sessions = listOf(
            slot(day = DayOfWeek.MONDAY, start = 1, end = 2, weeks = WeekPattern.range(1, 8) + WeekPattern.range(10, 16)),
            slot(day = DayOfWeek.THURSDAY, start = 3, end = 4, weeks = WeekPattern.parse("[1-17单]")),
        )
        val first = export(courses, sessions)
        val second = export(courses, sessions)

        // Byte-identical, not merely equivalent: a pure function of its arguments is
        // what makes "export, re-import, diff" a usable debugging tool.
        assertEquals(first, second)
        assertEquals(uids(first), uids(second))
        assertEquals(3, uids(first).size)
        assertEquals(3, uids(first).toSet().size)
    }

    @Test
    fun `a different slot of the same course gets a different UID`() {
        val course = course()
        val monday = export(
            listOf(course),
            listOf(slot(day = DayOfWeek.MONDAY, start = 1, end = 2, weeks = WeekPattern.range(1, 4))),
        )
        val tuesday = export(
            listOf(course),
            listOf(slot(day = DayOfWeek.TUESDAY, start = 1, end = 2, weeks = WeekPattern.range(1, 4))),
        )
        assertTrue(uids(monday).intersect(uids(tuesday).toSet()).isEmpty())
    }

    @Test
    fun `DTEND is still after DTSTART when the 作息表 states a reversed span`() {
        val broken = PeriodSchedule(
            listOf(
                Period(1, LocalTime.of(8, 0), LocalTime.of(9, 0)),
                Period(2, LocalTime.of(9, 0), LocalTime.of(10, 0)),
            ),
        )
        val ics = TimetableIcs.export(
            term = TERM,
            courses = listOf(course()),
            sessions = listOf(slot(start = 2, end = 1, weeks = WeekPattern.of(1))),
            schedule = broken,
        )

        assertEquals("20250217T090000", property(ics, "DTSTART"))
        assertEquals("20250217T094500", property(ics, "DTEND"))
    }

    // ------------------------------------------------------------------ import

    @Test
    fun `parse returns null when the text holds no VEVENT`() {
        assertNull(TimetableIcs.parse(""))
        assertNull(TimetableIcs.parse("   \r\n\r\n"))
        assertNull(TimetableIcs.parse("这不是一个日历文件"))
        assertNull(
            TimetableIcs.parse(
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Other//EN\r\nEND:VCALENDAR\r\n",
            ),
        )
        // A VEVENT with no start time is not usable either: there is no date to put it on.
        assertNull(
            TimetableIcs.parse(
                "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nSUMMARY:没有时间的课\r\n" +
                    "END:VEVENT\r\nEND:VCALENDAR\r\n",
            ),
        )
    }

    @Test
    fun `parse accepts LF only line endings and a UTF-8 BOM`() {
        val ics = "\uFEFF" + export(listOf(course()), listOf(slot(weeks = WeekPattern.range(1, 4))))
            .replace("\r\n", "\n")
        val parsed = TimetableIcs.parse(ics, TERM)!!

        assertEquals(WeekPattern.range(1, 4), parsed.sessionsByCourse.getValue(0).single().weeks)
        assertEquals(emptyList<String>(), parsed.warnings)
    }

    @Test
    fun `parse unfolds continuation lines`() {
        val ics = foreign(
            "SUMMARY:这是一个非常长的课程名称前半段",
            " 后半段",
            "DTSTART:20250217T080000",
            "DTEND:20250217T093500",
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!

        assertEquals("这是一个非常长的课程名称前半段后半段", parsed.courses.single().name)
    }

    @Test
    fun `parse treats a UTC stamp as wall clock and keeps the date`() {
        // The file states 08:00; the student is in the classroom at 08:00 local. A
        // timetable is wall-clock, so the Z is dropped rather than converted.
        val ics = foreign(
            "DTSTART:20250303T080000Z",
            "DTEND:20250303T093500Z",
            "SUMMARY:线性代数",
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!
        val back = parsed.sessionsByCourse.getValue(0).single()

        assertEquals(DayOfWeek.MONDAY, back.dayOfWeek)
        assertEquals(WeekPattern.of(3), back.weeks)
        assertEquals(1, back.startUnit)
        assertEquals(2, back.endUnit)
        assertTrue(parsed.warnings.any { it.contains("TJ课表") })
    }

    @Test
    fun `parse imports an all day VALUE DATE event with a placeholder 节 and a warning`() {
        val ics = foreign(
            "DTSTART;VALUE=DATE:20250303",
            "DTEND;VALUE=DATE:20250304",
            "SUMMARY:工程训练",
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!
        val back = parsed.sessionsByCourse.getValue(0).single()

        assertEquals(DayOfWeek.MONDAY, back.dayOfWeek)
        assertEquals(WeekPattern.of(3), back.weeks)
        // Nothing in the file says which 节 this is, so it is imported rather than
        // dropped — but the guess is reported.
        assertEquals(1, back.startUnit)
        assertEquals(1, back.endUnit)
        assertTrue(parsed.warnings.any { it.contains("没有具体的上课时间") })
    }

    @Test
    fun `parse reads a DTSTART with a TZID parameter`() {
        val ics = foreign(
            "DTSTART;TZID=Asia/Shanghai:20250303T100000",
            "DTEND;TZID=Asia/Shanghai:20250303T114000",
            "SUMMARY:大学物理",
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!
        val back = parsed.sessionsByCourse.getValue(0).single()

        assertEquals(DayOfWeek.MONDAY, back.dayOfWeek)
        assertEquals(3, back.startUnit)
        // 11:40 is not a 节 boundary; 4节 (10:50-11:35) is the last one that has begun.
        assertEquals(4, back.endUnit)
        // The start matched a period exactly, so the guess is about the end only.
        assertTrue(parsed.warnings.any { it.contains("11:40") })
    }

    @Test
    fun `parse imports a non weekly rule once and says so`() {
        val ics = foreign(
            "SUMMARY:新生讲座",
            "DTSTART:20250303T100000",
            "DTEND:20250303T114000",
            "RRULE:FREQ=DAILY;COUNT=3",
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!

        val sessions = parsed.sessionsByCourse.getValue(0)
        assertEquals(1, sessions.size)
        assertEquals(WeekPattern.of(3), sessions.single().weeks)
        assertTrue(parsed.warnings.any { it.contains("FREQ=DAILY") })
    }

    @Test
    fun `parse expands a weekly rule up to and including UNTIL`() {
        val ics = foreign(
            "SUMMARY:分析化学",
            "DTSTART:20250217T080000",
            "DTEND:20250217T093500",
            "RRULE:FREQ=WEEKLY;BYDAY=MO;UNTIL=20250303T080000",
        )
        val back = TimetableIcs.parse(ics, TERM)!!.sessionsByCourse.getValue(0).single()

        // UNTIL names the last instance, so 2025-03-03 (week 3) is still taught.
        assertEquals(listOf(1, 2, 3), back.weeks.weekNumbers)
    }

    @Test
    fun `parse caps an endless weekly rule at the width of the week mask`() {
        val ics = foreign(
            "SUMMARY:没有结束条件的课",
            "DTSTART:20250217T080000",
            "DTEND:20250217T093500",
            "RRULE:FREQ=WEEKLY;BYDAY=MO",
        )
        val parsed = TimetableIcs.parse(ics)!!
        val back = parsed.sessionsByCourse.getValue(0).single()

        // An RFC 5545 rule without COUNT or UNTIL never ends; the week mask is 64 wide,
        // so anything past that could not be stored anyway.
        assertEquals(WeekPattern.range(1, WeekPattern.MAX_WEEK), back.weeks)
        assertTrue(parsed.warnings.any { it.contains("最多 64 次课") })
    }

    @Test
    fun `parse ignores unknown properties and nested components`() {
        val ics = buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("VERSION:2.0\r\n")
            append("PRODID:-//SomeApp//EN\r\n")
            append("X-WR-CALNAME:2025春 我的课表\r\n")
            append("BEGIN:VTIMEZONE\r\n")
            append("TZID:Asia/Shanghai\r\n")
            append("BEGIN:STANDARD\r\n")
            append("DTSTART:19700101T000000\r\n")
            append("TZOFFSETFROM:+0800\r\n")
            append("TZOFFSETTO:+0800\r\n")
            append("END:STANDARD\r\n")
            append("END:VTIMEZONE\r\n")
            append("BEGIN:VEVENT\r\n")
            append("UID:abc@someapp\r\n")
            append("DTSTAMP:20250101T000000Z\r\n")
            append("DTSTART;TZID=Asia/Shanghai:20250303T100000\r\n")
            append("DTEND;TZID=Asia/Shanghai:20250303T114000\r\n")
            append("SUMMARY:线性代数\r\n")
            append("LOCATION:教学北楼 201\r\n")
            append("CATEGORIES:study\r\n")
            append("X-CUSTOM;X-PARAM=\"a;b\":whatever\r\n")
            append("SEQUENCE:3\r\n")
            append("BEGIN:VALARM\r\n")
            append("ACTION:DISPLAY\r\n")
            append("TRIGGER:-PT15M\r\n")
            append("END:VALARM\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }
        val parsed = TimetableIcs.parse(ics)!!

        // No X-TJ-* header, so the semester name comes from X-WR-CALNAME.
        assertEquals("2025春 我的课表", parsed.term.name)
        assertEquals("线性代数", parsed.courses.single().name)
        val back = parsed.sessionsByCourse.getValue(0).single()
        assertEquals("教学北楼 201", back.room)
        assertEquals(3, back.startUnit)
        assertEquals(4, back.endUnit)
    }

    @Test
    fun `parse removes the dates named by EXDATE`() {
        val ics = foreign(
            "SUMMARY:离散数学",
            "DTSTART:20250217T080000",
            "DTEND:20250217T093500",
            "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4",
            "EXDATE:20250303T080000",
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!
        val back = parsed.sessionsByCourse.getValue(0).single()

        assertEquals(listOf(1, 2, 4), back.weeks.weekNumbers)
    }

    @Test
    fun `parse removes every date of a multi valued EXDATE`() {
        val ics = foreign(
            "SUMMARY:离散数学",
            "DTSTART:20250217T080000",
            "DTEND:20250217T093500",
            "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4",
            "EXDATE:20250303T080000,20250317T080000",
        )
        val back = TimetableIcs.parse(ics, TERM)!!.sessionsByCourse.getValue(0).single()
        assertEquals(listOf(1, 2, 4), back.weeks.weekNumbers)
    }

    @Test
    fun `parse uses the nearest period start and warns when the time does not match`() {
        val ics = foreign(
            "SUMMARY:体育",
            "DTSTART:20250303T080500",
            "DTEND:20250303T093000",
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!
        val back = parsed.sessionsByCourse.getValue(0).single()

        assertEquals(1, back.startUnit)
        assertEquals(2, back.endUnit)
        assertTrue(parsed.warnings.any { it.contains("08:05") })
    }

    @Test
    fun `parse falls back to the first period for a time before the timetable starts`() {
        val ics = foreign(
            "SUMMARY:早自习",
            "DTSTART:20250303T070000",
            "DTEND:20250303T074500",
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!
        val back = parsed.sessionsByCourse.getValue(0).single()

        assertEquals(1, back.startUnit)
        assertEquals(1, back.endUnit)
        assertTrue(parsed.warnings.any { it.contains("07:00") })
    }

    @Test
    fun `parse maps a span that crosses the lunch break onto its 节 range`() {
        val ics = foreign(
            "SUMMARY:制图",
            "DTSTART:20250217T080000",
            "DTEND:20250217T113500",
        )
        val back = TimetableIcs.parse(ics, TERM)!!.sessionsByCourse.getValue(0).single()
        assertEquals(1, back.startUnit)
        assertEquals(4, back.endUnit)
        assertEquals(4, back.unitSpan)
    }

    @Test
    fun `parse groups the sessions of one course and separates the 教学班`() {
        val ics = document(
            vevent(
                "SUMMARY:大学英语",
                "DTSTART:20250217T080000",
                "DTEND:20250217T093500",
                "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=2",
                "DESCRIPTION:教师：李四\\n教学班：01班\\n节次：1-2节\\n周次：1-2周",
            ),
            vevent(
                "SUMMARY:大学英语",
                "DTSTART:20250219T080000",
                "DTEND:20250219T093500",
                "RRULE:FREQ=WEEKLY;BYDAY=WE;COUNT=4",
                "DESCRIPTION:教师：王五\\n教学班：02班\\n节次：1-2节\\n周次：1-4周",
            ),
            vevent(
                "SUMMARY:大学英语",
                "DTSTART:20250310T080000",
                "DTEND:20250310T093500",
                "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=2",
                "DESCRIPTION:教师：李四\\n教学班：01班\\n节次：1-2节\\n周次：4-5周",
            ),
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!

        // Two 教学班 are two courses to a student: different days, different teachers.
        assertEquals(2, parsed.courses.size)
        assertEquals("01班", parsed.courses[0].classCode)
        assertEquals("李四", parsed.courses[0].teacher)
        assertEquals("02班", parsed.courses[1].classCode)
        assertEquals("王五", parsed.courses[1].teacher)

        // The two Monday events of 01班 are two runs of one session, not two sessions.
        val monday = parsed.sessionsByCourse.getValue(0).single()
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(WeekPattern.range(1, 2) + WeekPattern.range(4, 5), monday.weeks)
        assertEquals(listOf(1..2, 4..5), monday.weeks.ranges())

        val wednesday = parsed.sessionsByCourse.getValue(1).single()
        assertEquals(DayOfWeek.WEDNESDAY, wednesday.dayOfWeek)
        assertEquals(WeekPattern.range(1, 4), wednesday.weeks)
        assertEquals(2, parsed.sessionCount)
    }

    @Test
    fun `parse imports every weekday of a multi day BYDAY rule`() {
        val ics = foreign(
            "SUMMARY:高等数学A",
            "DTSTART:20250217T080000",
            "DTEND:20250217T093500",
            "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4",
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!

        // COUNT=4 is four occurrences, i.e. two weeks of two days each: reading it as
        // four weeks would invent classes that never happen.
        val sessions = parsed.sessionsByCourse.getValue(0)
        assertEquals(2, sessions.size)
        assertEquals(DayOfWeek.MONDAY to listOf(1, 2), sessions[0].dayOfWeek to sessions[0].weeks.weekNumbers)
        assertEquals(DayOfWeek.WEDNESDAY to listOf(1, 2), sessions[1].dayOfWeek to sessions[1].weeks.weekNumbers)
    }

    @Test
    fun `parse applies a RECURRENCE-ID to the rule it belongs to`() {
        val ics = document(
            vevent(
                "SUMMARY:英语口语",
                "DTSTART:20250217T080000",
                "DTEND:20250217T093500",
                "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4",
                uid = "master@example.com",
            ),
            vevent(
                "RECURRENCE-ID:20250224T080000",
                "SUMMARY:英语口语",
                "DTSTART:20250226T080000",
                "DTEND:20250226T093500",
                uid = "master@example.com",
            ),
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!
        val sessions = parsed.sessionsByCourse.getValue(0)

        assertEquals(2, sessions.size)
        // The moved instance leaves the Monday rule…
        assertEquals(DayOfWeek.MONDAY, sessions[0].dayOfWeek)
        assertEquals(listOf(1, 3, 4), sessions[0].weeks.weekNumbers)
        // …and reappears on the weekday it was actually moved to.
        assertEquals(DayOfWeek.WEDNESDAY, sessions[1].dayOfWeek)
        assertEquals(listOf(2), sessions[1].weeks.weekNumbers)
        assertTrue(parsed.warnings.any { it.contains("RECURRENCE-ID") })
    }

    @Test
    fun `parse skips a cancelled event and reports it`() {
        val ics = document(
            vevent(
                "SUMMARY:已取消的课",
                "STATUS:CANCELLED",
                "DTSTART:20250217T080000",
                "DTEND:20250217T093500",
            ),
            vevent(
                "SUMMARY:正常课",
                "DTSTART:20250218T080000",
                "DTEND:20250218T093500",
            ),
        )
        val parsed = TimetableIcs.parse(ics, TERM)!!

        assertEquals(listOf("正常课"), parsed.courses.map { it.name })
        assertTrue(parsed.warnings.any { it.contains("已取消") })
    }

    @Test
    fun `parse infers a semester from the earliest class when the file carries none`() {
        val ics = foreign("SUMMARY:第一节课", "DTSTART:20250217T080000", "DTEND:20250217T093500")
        val parsed = TimetableIcs.parse(ics)!!

        // Monday of the week containing the earliest DTSTART.
        assertEquals(LocalDate.of(2025, 2, 17), parsed.term.firstWeekStart)
        assertEquals("ics", parsed.term.calendarId)
        assertTrue(parsed.warnings.any { it.contains("推算学期") })
    }

    @Test
    fun `parse spans the semester from the earliest to the latest class`() {
        val ics = document(
            vevent(
                "SUMMARY:第一节课",
                "DTSTART:20250217T080000",
                "DTEND:20250217T093500",
            ),
            vevent(
                "SUMMARY:最后一节课",
                "DTSTART:20250505T080000",
                "DTEND:20250505T093500",
            ),
        )
        val parsed = TimetableIcs.parse(ics)!!

        assertEquals(LocalDate.of(2025, 2, 17), parsed.term.firstWeekStart)
        // 2025-02-17 is week 1 and 2025-05-05 is week 12, plus the deliberate one-week
        // margin that keeps the inference from clipping the final teaching week.
        assertEquals(13, parsed.term.totalWeeks)
        assertEquals(WeekPattern.of(1), parsed.sessionsByCourse.getValue(0).single().weeks)
        assertEquals(WeekPattern.of(12), parsed.sessionsByCourse.getValue(1).single().weeks)
    }

    @Test
    fun `parse keeps every session when the file has no term at all`() {
        // No X-WR-CALNAME either: the name falls back to a generated one.
        val ics = document(
            vevent("SUMMARY:体育", "DTSTART:20250217T080000", "DTEND:20250217T093500"),
        )
        val parsed = TimetableIcs.parse(ics)!!
        assertEquals("从日历文件导入", parsed.term.name)
    }

    @Test
    fun `parse skips malformed lines instead of throwing`() {
        val ics = buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("GARBAGE WITHOUT A COLON\r\n")
            append(":\r\n")
            append(";:weird\r\n")
            append("BEGIN:VEVENT\r\n")
            append("UID:x\r\n")
            append("DTSTART:20250303T080000\r\n")
            append("DTSTART\r\n")
            append("DTEND:20250303T093500\r\n")
            append("SUMMARY:线性代数\r\n")
            // An unreadable COUNT plus a readable UNTIL: the rule still has to import
            // as a weekly one covering weeks 3-4.
            append("RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=XX;UNTIL=20250310T080000;BOGUS\r\n")
            append("EXDATE:not-a-date\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }
        val parsed = TimetableIcs.parse(ics, TERM)!!

        val back = parsed.sessionsByCourse.getValue(0).single()
        assertEquals("线性代数", parsed.courses.single().name)
        assertEquals(DayOfWeek.MONDAY, back.dayOfWeek)
        assertEquals(listOf(3, 4), back.weeks.weekNumbers)
        // The colon-less `DTSTART` line was skipped, so the readable one still wins.
        assertEquals(1, back.startUnit)
        assertEquals(2, back.endUnit)
    }

    @Test
    fun `parse imports the event of a file that was cut off mid event`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:x\r\n" +
            "DTSTART:20250303T080000\r\nDTEND:20250303T093500\r\nSUMMARY:线性代数\r\n"
        val parsed = TimetableIcs.parse(ics, TERM)!!

        assertEquals("线性代数", parsed.courses.single().name)
        assertTrue(parsed.warnings.any { it.contains("中断") })
    }

    @Test
    fun `parse reads a DURATION instead of a DTEND`() {
        val ics = foreign(
            "SUMMARY:音乐鉴赏",
            "DTSTART:20250303T100000",
            "DURATION:PT1H35M",
        )
        val back = TimetableIcs.parse(ics, TERM)!!.sessionsByCourse.getValue(0).single()

        assertEquals(3, back.startUnit)
        assertEquals(4, back.endUnit)
    }

    @Test
    fun `parse names an unnamed event instead of dropping it`() {
        val ics = foreign("DTSTART:20250303T080000", "DTEND:20250303T093500")
        val parsed = TimetableIcs.parse(ics, TERM)!!

        assertEquals("未命名课程", parsed.courses.single().name)
        assertEquals(1, parsed.sessionCount)
        assertTrue(parsed.warnings.any { it.contains("没有课程名") })
    }

    @Test
    fun `parse uses the fallback semester only when the file has no term info`() {
        val withoutHeader = document(
            vevent("SUMMARY:体育", "DTSTART:20250303T080000", "DTEND:20250303T093500"),
        )
        val fromFallback = TimetableIcs.parse(withoutHeader, TERM)!!
        assertEquals(TERM, fromFallback.term)
        assertEquals(WeekPattern.of(3), fromFallback.sessionsByCourse.getValue(0).single().weeks)

        // A file this app exported carries its own term, which must win over the
        // caller's — numbering an old file's weeks against today's semester would put
        // every class in the wrong week.
        val other = TERM.copy(calendarId = "2030-1", firstWeekStart = LocalDate.of(2030, 9, 2))
        val exported = export(listOf(course()), listOf(slot(weeks = WeekPattern.range(1, 4))))
        val fromFile = TimetableIcs.parse(exported, other)!!
        assertEquals(TERM.firstWeekStart, fromFile.term.firstWeekStart)
        assertEquals(WeekPattern.range(1, 4), fromFile.sessionsByCourse.getValue(0).single().weeks)
    }

    @Test
    fun `parse reports a missing end time and keeps the class`() {
        val ics = foreign("SUMMARY:自习", "DTSTART:20250303T080000")
        val parsed = TimetableIcs.parse(ics, TERM)!!
        val back = parsed.sessionsByCourse.getValue(0).single()

        assertEquals(1, back.startUnit)
        assertEquals(1, back.endUnit)
        assertTrue(parsed.warnings.any { it.contains("缺少下课时间") })
    }

    // ------------------------------------------------------------------ fixtures

    private fun export(
        courses: List<Course>,
        sessions: List<CourseSession>,
        adjustments: ScheduleAdjustmentSet = ScheduleAdjustmentSet.EMPTY,
        term: TermCalendar = TERM,
    ): String = TimetableIcs.export(term, courses, sessions, PeriodSchedule.TONGJI, adjustments)

    private fun course(
        name: String = "高等数学A",
        id: Long = 1L,
        classCode: String? = "10016502",
        teacher: String? = "张亚英",
    ): Course = Course(id = id, name = name, classCode = classCode, teacher = teacher)

    private fun slot(
        courseId: Long = 1L,
        day: DayOfWeek = DayOfWeek.MONDAY,
        start: Int = 1,
        end: Int = 2,
        weeks: WeekPattern,
        room: String? = "教学南楼305",
    ): CourseSession = CourseSession(
        courseId = courseId,
        dayOfWeek = day,
        startUnit = start,
        endUnit = end,
        weeks = weeks,
        room = room,
    )

    /** A minimal calendar written by some other app, carrying [lines] as one event. */
    private fun foreign(vararg lines: String): String = document(vevent(*lines))

    private fun vevent(vararg lines: String, uid: String = "foreign-@example.com"): String =
        buildString {
            append("BEGIN:VEVENT\r\n")
            append("UID:").append(uid).append("\r\n")
            append("DTSTAMP:20250101T000000Z\r\n")
            for (line in lines) append(line).append("\r\n")
            append("END:VEVENT\r\n")
        }

    /**
     * No `X-WR-CALNAME` and no `X-TJ-*`: exactly what a calendar file from another app
     * looks like, term information included.
     */
    private fun document(vararg events: String): String = buildString {
        append("BEGIN:VCALENDAR\r\n")
        append("VERSION:2.0\r\n")
        append("PRODID:-//OtherApp//EN\r\n")
        for (event in events) append(event)
        append("END:VCALENDAR\r\n")
    }

    private fun eventCount(ics: String): Int = Regex("BEGIN:VEVENT").findAll(ics).count()

    private fun uids(ics: String): List<String> =
        Regex("UID:([^\r\n]+)").findAll(ics).map { it.groupValues[1] }.toList()

    /**
     * The value of a property line, with fold continuations re-joined.
     *
     * Folding is invisible in the logical document but very visible in a naive
     * `contains` check — a 75-octet boundary can land in the middle of a Chinese word,
     * so assertions about a long value have to unfold first.
     */
    private fun property(ics: String, name: String): String? =
        logicalLines(ics).firstOrNull { it.startsWith("$name:") }?.substringAfter(":")

    private fun logicalLines(ics: String): List<String> {
        val out = ArrayList<String>()
        for (line in ics.split("\r\n")) {
            if (line.isEmpty()) continue
            if (line[0] == ' ' && out.isNotEmpty()) {
                out[out.size - 1] = out[out.size - 1] + line.substring(1)
            } else {
                out += line
            }
        }
        return out
    }

    private companion object {
        /** 2025-02-17 is a Monday, so week 1 starts on it. */
        val TERM = TermCalendar(
            calendarId = "2024-2025-2",
            name = "2024-2025学年度第2学期",
            year = 2024,
            term = 2,
            firstWeekStart = LocalDate.of(2025, 2, 17),
            endDate = LocalDate.of(2025, 6, 22),
            totalWeeks = 18,
        )

        /** `DTSTART:20250217T080000` — a floating stamp, no `Z` and no `TZID`. */
        val FLOATING = Regex("""DT(?:START|END):\d{8}T\d{6}""")
    }
}
