package com.ranorac.tjtimetable.widget

import com.ranorac.tjtimetable.domain.ClassOccurrence
import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.WeekPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * The widget shows what is still relevant today, not the whole day.
 *
 * The behaviour was asked for in as many words: a glance at the home screen in the afternoon used
 * to show the morning's finished lectures and bury the class that is actually next. This is
 * pinned here rather than on a device because the rule is one comparison — and because getting it
 * subtly wrong (dropping the class in progress, or hiding a class whose clock times are unknown)
 * is invisible until someone is standing in the wrong building.
 */
class WidgetUpcomingTest {

    private val monday = LocalDate.of(2026, 3, 2)

    private fun occurrence(
        name: String,
        start: LocalTime?,
        end: LocalTime?,
        startUnit: Int = 1,
        endUnit: Int = 2,
    ): ClassOccurrence {
        val course = Course(id = 1, name = name)
        val session = CourseSession(
            id = 1,
            courseId = 1,
            dayOfWeek = DayOfWeek.MONDAY,
            startUnit = startUnit,
            endUnit = endUnit,
            weeks = WeekPattern.range(1, 18),
            room = "南101",
        )
        return ClassOccurrence(
            course = course,
            session = session,
            date = monday,
            week = 1,
            startTime = start,
            endTime = end,
        )
    }

    private val finished = occurrence("高等数学", LocalTime.of(8, 0), LocalTime.of(9, 35))
    private val running = occurrence("大学英语", LocalTime.of(10, 0), LocalTime.of(11, 35), 3, 4)
    private val later = occurrence("大学物理", LocalTime.of(13, 30), LocalTime.of(15, 5), 5, 6)

    @Test
    fun `finished classes are dropped and the rest kept in order`() {
        val visible = upcomingOnly(listOf(finished, running, later), LocalTime.of(10, 30))
        assertEquals(listOf("大学英语", "大学物理"), visible.map { it.course.name })
    }

    @Test
    fun `the class in progress stays visible for its whole duration`() {
        // At its very start it is running, not finished.
        assertEquals(
            listOf("大学英语", "大学物理"),
            upcomingOnly(listOf(running, later), LocalTime.of(10, 0)).map { it.course.name },
        )
        // And at its very end it has not yet been dropped — the student may still be walking out
        // of it and would rather see it than a blank row where it was.
        assertEquals(
            listOf("大学英语", "大学物理"),
            upcomingOnly(listOf(running, later), LocalTime.of(11, 35)).map { it.course.name },
        )
        // One minute later it is over.
        assertEquals(
            listOf("大学物理"),
            upcomingOnly(listOf(running, later), LocalTime.of(11, 36)).map { it.course.name },
        )
    }

    @Test
    fun `a class whose clock times are unknown is kept`() {
        // No 作息 entry means there is no instant to compare against. Hiding it would be a silent
        // omission, which is the one thing this project refuses to do.
        val unknown = occurrence("体育", start = null, end = null)
        val visible = upcomingOnly(listOf(unknown, finished), LocalTime.of(20, 0))
        assertEquals(listOf("体育"), visible.map { it.course.name })
    }

    @Test
    fun `once the teaching day is over nothing is left`() {
        val visible = upcomingOnly(listOf(finished, running, later), LocalTime.of(18, 0))
        assertTrue("every class has ended", visible.isEmpty())
    }

    @Test
    fun `the filter preserves the order the resolver produced`() {
        // Ordering is the resolver's job (chronological, then 节); filtering must not reshuffle,
        // or the widget's first row would stop being the next class. At 09:45 only 高等数学 has
        // finished, so the two that remain must still come out in their original order.
        val visible = upcomingOnly(listOf(finished, running, later), LocalTime.of(9, 45))
        assertEquals(listOf("大学英语", "大学物理"), visible.map { it.course.name })
    }

    @Test
    fun `an empty day stays empty`() {
        assertTrue(upcomingOnly(emptyList(), LocalTime.NOON).isEmpty())
    }

    // ------------------------------------------------------------ boundary timing

    @Test
    fun `the next boundary is the soonest start or end still ahead`() {
        // 09:35 ends 高等数学, so that is what the widget waits for first...
        assertEquals(
            LocalTime.of(9, 35),
            nextBoundary(listOf(finished, running, later), LocalTime.of(9, 0)),
        )
        // ... then 10:00 starts 大学英语, which changes it from "next" to "进行中".
        assertEquals(
            LocalTime.of(10, 0),
            nextBoundary(listOf(finished, running, later), LocalTime.of(9, 40)),
        )
        // Inside a class the next thing that happens is its own end.
        assertEquals(
            LocalTime.of(11, 35),
            nextBoundary(listOf(finished, running, later), LocalTime.of(10, 30)),
        )
    }

    @Test
    fun `a boundary already past is not re-armed`() {
        // Arming a moment in the past would make the worker fire immediately and then... arm it
        // again. Every returned boundary must be strictly in the future.
        val boundary = nextBoundary(listOf(finished, running, later), LocalTime.of(10, 30))
        assertTrue("returned boundary must be ahead of now", boundary!!.isAfter(LocalTime.of(10, 30)))
    }

    @Test
    fun `nothing to wait for once the day is over`() {
        assertNull(nextBoundary(listOf(finished, running, later), LocalTime.of(18, 0)))
        assertNull("no classes at all", nextBoundary(emptyList(), LocalTime.NOON))
    }

    @Test
    fun `classes without clock times contribute no boundary`() {
        // There is no instant to wake up at, so the widget must not schedule one.
        val unknown = occurrence("体育", start = null, end = null)
        assertNull(nextBoundary(listOf(unknown), LocalTime.NOON))
    }
}
