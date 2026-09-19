package com.ranorac.tjtimetable.domain

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PeriodSchedule], the 作息时间表 that maps 节 numbers onto clock times.
 *
 * [PeriodSchedule.TONGJI] is the published 同济大学 schedule: 11 节, 上午 1-4,
 * 下午 5-8, 晚上 9-11.
 */
class PeriodScheduleTest {

    private val tongji = PeriodSchedule.TONGJI

    // ------------------------------------------------------------------
    // The built in 同济 schedule
    // ------------------------------------------------------------------

    @Test
    fun `tongji has eleven periods numbered one to eleven`() {
        assertEquals(11, tongji.periods.size)
        assertEquals((1..11).toList(), tongji.periods.map { it.index })
    }

    @Test
    fun `tongji period 1 starts at 08 00`() {
        assertEquals(LocalTime.of(8, 0), tongji.startOf(1))
        assertEquals(LocalTime.of(8, 45), tongji.endOf(1))
    }

    @Test
    fun `tongji period 11 ends at 20 55`() {
        assertEquals(LocalTime.of(20, 10), tongji.startOf(11))
        assertEquals(LocalTime.of(20, 55), tongji.endOf(11))
        assertEquals(LocalTime.of(20, 55), tongji.periods.last().end)
    }

    @Test
    fun `tongji period start times strictly increase`() {
        val starts = tongji.periods.map { it.start }
        for (i in 1 until starts.size) {
            assertTrue(
                "period ${i + 1} must start after period $i",
                starts[i].isAfter(starts[i - 1]),
            )
        }
    }

    @Test
    fun `every tongji period ends after it starts`() {
        for (p in tongji.periods) {
            assertTrue("period ${p.index} must end after it starts", p.end.isAfter(p.start))
        }
    }

    @Test
    fun `tongji periods do not overlap`() {
        val sorted = tongji.periods.sortedBy { it.start }
        for (i in 1 until sorted.size) {
            assertTrue(
                "period ${sorted[i].index} overlaps the previous one",
                !sorted[i].start.isBefore(sorted[i - 1].end),
            )
        }
    }

    @Test
    fun `tongji splits into morning afternoon and evening blocks`() {
        assertEquals(LocalTime.of(8, 0), tongji.startOf(1))
        assertEquals(LocalTime.of(11, 35), tongji.endOf(4))
        assertEquals(LocalTime.of(13, 30), tongji.startOf(5))
        assertEquals(LocalTime.of(17, 5), tongji.endOf(8))
        assertEquals(LocalTime.of(18, 30), tongji.startOf(9))
        assertEquals(LocalTime.of(20, 55), tongji.endOf(11))
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    @Test
    fun `period lookup by unit returns the matching row`() {
        assertEquals(Period(5, LocalTime.of(13, 30), LocalTime.of(14, 15)), tongji.period(5))
        assertEquals(Period(10, LocalTime.of(19, 20), LocalTime.of(20, 5)), tongji.period(10))
    }

    @Test
    fun `period lookup returns null for an unknown unit`() {
        assertNull(tongji.period(0))
        assertNull(tongji.period(12))
        assertNull(tongji.period(-1))
    }

    @Test
    fun `startOf and endOf return null for an unknown unit`() {
        assertNull(tongji.startOf(0))
        assertNull(tongji.startOf(12))
        assertNull(tongji.endOf(99))
    }

    // ------------------------------------------------------------------
    // spanOf
    // ------------------------------------------------------------------

    @Test
    fun `spanOf covers a two period class`() {
        assertEquals(LocalTime.of(8, 0) to LocalTime.of(9, 35), tongji.spanOf(1, 2))
        assertEquals(LocalTime.of(10, 0) to LocalTime.of(11, 35), tongji.spanOf(3, 4))
        assertEquals(LocalTime.of(18, 30) to LocalTime.of(20, 55), tongji.spanOf(9, 11))
    }

    @Test
    fun `spanOf covers a four period class across a break`() {
        assertEquals(LocalTime.of(8, 0) to LocalTime.of(11, 35), tongji.spanOf(1, 4))
        assertEquals(LocalTime.of(13, 30) to LocalTime.of(17, 5), tongji.spanOf(5, 8))
    }

    @Test
    fun `spanOf of a single period is that period`() {
        assertEquals(LocalTime.of(8, 0) to LocalTime.of(8, 45), tongji.spanOf(1, 1))
        assertEquals(tongji.startOf(7) to tongji.endOf(7), tongji.spanOf(7, 7))
        assertEquals(LocalTime.of(20, 10) to LocalTime.of(20, 55), tongji.spanOf(11, 11))
    }

    @Test
    fun `spanOf is null when either unit is unknown`() {
        assertNull(tongji.spanOf(0, 1))
        assertNull(tongji.spanOf(1, 0))
        assertNull(tongji.spanOf(1, 12))
        assertNull(tongji.spanOf(12, 12))
    }

    @Test
    fun `spanOf rejects a reversed range`() {
        // A backwards span used to be returned as-is, which silently produced a
        // negative-length calendar event; a reversed range is now rejected outright.
        assertNull(tongji.spanOf(2, 1))
        assertNull(tongji.spanOf(5, 4))
        assertNull(tongji.spanOf(11, 1))
        assertNull(tongji.spanOf(11, 10))
    }

    // ------------------------------------------------------------------
    // maxUnit
    // ------------------------------------------------------------------

    @Test
    fun `maxUnit of the tongji schedule is eleven`() {
        assertEquals(11, tongji.maxUnit)
    }

    @Test
    fun `maxUnit of an empty schedule is zero`() {
        val empty = PeriodSchedule(emptyList())
        assertEquals(0, empty.maxUnit)
        assertTrue(empty.periods.isEmpty())
        assertNull(empty.period(1))
        assertNull(empty.startOf(1))
        assertNull(empty.endOf(1))
        assertNull(empty.spanOf(1, 1))
    }

    @Test
    fun `maxUnit does not assume sorted rows`() {
        val custom = PeriodSchedule(
            listOf(
                Period(3, LocalTime.of(9, 0), LocalTime.of(9, 45)),
                Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
                Period(7, LocalTime.of(15, 0), LocalTime.of(15, 45)),
            ),
        )
        assertEquals(7, custom.maxUnit)
        assertEquals(3, custom.periods.size)
    }

    @Test
    fun `spanOf works on an edited schedule`() {
        val custom = PeriodSchedule(
            listOf(
                Period(1, LocalTime.of(7, 30), LocalTime.of(8, 15)),
                Period(2, LocalTime.of(8, 20), LocalTime.of(9, 5)),
            ),
        )
        assertEquals(LocalTime.of(7, 30) to LocalTime.of(9, 5), custom.spanOf(1, 2))
        assertEquals(2, custom.maxUnit)
        assertNull(custom.spanOf(1, 11))
    }

    @Test
    fun `an edited schedule can be built from the tongji default`() {
        val edited = PeriodSchedule(
            tongji.periods.map { if (it.index == 1) it.copy(start = LocalTime.of(8, 30)) else it },
        )
        assertEquals(11, edited.maxUnit)
        assertEquals(LocalTime.of(8, 30), edited.startOf(1))
        assertEquals(LocalTime.of(9, 35), edited.endOf(2))
    }
}
