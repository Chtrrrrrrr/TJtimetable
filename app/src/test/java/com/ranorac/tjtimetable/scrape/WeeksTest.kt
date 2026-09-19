package com.ranorac.tjtimetable.scrape

import com.ranorac.tjtimetable.domain.WeekPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the reference's `dotnet/TjtCore.Tests/WeeksTests.cs`, plus the reconciliation
 * between their `Weeks.cs` and this app's [WeekPattern].
 *
 * 这些断言不是「随手编的分支覆盖」，而是与既有 Python 实现（`fmt_weeks`）和真实教务数据绑定的
 * 黄金约定（`65535` = 第 1-16 周、`0x5555` = 单周、`0xaaaa` = 双周）—— 移植过程中任何一处
 * 语义偏差都会在这里暴露。
 */
class WeeksTest {

    @Test
    fun weekListsAndMasksRoundTrip() {
        assertEquals(0b111L, Weeks.fromWeeks(listOf(1, 2, 3)).bits)
        assertEquals(1L shl 15, Weeks.fromWeeks(listOf(16)).bits)
        assertEquals(listOf(1, 2, 3), Weeks.toWeeks(Weeks.fromWeeks(listOf(1, 2, 3)), 16))
        assertEquals(listOf(16), Weeks.toWeeks(Weeks.fromWeeks(listOf(16)), 16))
        assertTrue(Weeks.fromWeeks(listOf(1, 2, 3)).contains(1))
        assertFalse(Weeks.fromWeeks(listOf(1, 2, 3)).contains(4))
    }

    @Test
    fun illegalWeeksAreIgnored() {
        // 越界（<1 或 >32）一律忽略；这正是参考实现 uint 宽度的边界。
        assertEquals(Weeks.fromWeeks(listOf(3)), Weeks.fromWeeks(listOf(0, -1, 33, 3)))
        assertEquals(WeekPattern.EMPTY, Weeks.fromWeeks(emptyList()))
        assertEquals(0, Weeks.highest(Weeks.fromWeeks(emptyList())))
    }

    @Test
    fun weekThirtyTwoIsStillRepresentable() {
        assertEquals(0x8000_0000L, Weeks.fromWeeks(listOf(32)).bits)
        assertEquals(listOf(32), Weeks.toWeeks(Weeks.fromWeeks(listOf(32)), 32))
        // WeekPattern itself is wider (64 bits) — that is a deliberate divergence, see below.
        assertEquals(listOf(33), WeekPattern.of(33).weekNumbers)
        assertEquals(WeekPattern.EMPTY, Weeks.fromWeeks(listOf(33)))
    }

    @Test
    fun theFullTermMaskIs0xFFFF() {
        assertEquals(65535L, Weeks.fullMask(16).bits)
        assertEquals(16, Weeks.count(Weeks.fullMask(16)))
        assertTrue(Weeks.isAll(Weeks.fullMask(16), 16))
        assertFalse(Weeks.isAll(Weeks.fullMask(16), 17))
        // Their IsAll is "covers exactly the whole term", which WeekPattern has no equivalent of:
        // this is what a "全部周上课" badge needs, and `count == totalWeeks` is the closest
        // expression available on WeekPattern alone.
        assertEquals(Weeks.fullMask(16).count, 16)
        assertTrue(Weeks.isAll(WeekPattern.fromBits(65535), 16))
        assertFalse(Weeks.isAll(WeekPattern.EMPTY, 16))
    }

    @Test
    fun weekLabelsMatchTheExistingPythonImplementation() {
        assertEquals("1-16", Weeks.formatLabel(WeekPattern.fromBits(65535)))
        assertEquals(
            "1, 3, 5, 7, 9, 11, 13, 15",
            Weeks.formatLabel(Weeks.fromWeeks(listOf(1, 3, 5, 7, 9, 11, 13, 15))),
        )
        assertEquals(
            "2, 4, 6, 8, 10, 12, 14, 16",
            Weeks.formatLabel(Weeks.fromWeeks(listOf(2, 4, 6, 8, 10, 12, 14, 16))),
        )
        assertEquals("11-14", Weeks.formatLabel(Weeks.fromWeeks(listOf(11, 12, 13, 14))))
        assertEquals("9, 16", Weeks.formatLabel(Weeks.fromWeeks(listOf(9, 16))))
        assertEquals("-", Weeks.formatLabel(WeekPattern.EMPTY))
    }

    @Test
    fun weekLabelsDifferFromWeekPatternDisplayByDesign() {
        val odd = Weeks.fromWeeks(listOf(1, 3, 5, 7, 9, 11, 13, 15))
        val single = WeekPattern.of(3)

        // Weeks.formatLabel matches the existing Python `fmt_weeks` golden values…
        assertEquals("1, 3, 5, 7, 9, 11, 13, 15", Weeks.formatLabel(odd, 16))
        assertEquals("3", Weeks.formatLabel(single, 16))
        // …while the app's own display() adds the 周 suffix, the 单/双 label and commas the UI wants
        // (display() lists individual weeks rather than the reference's "a-b, c-d" ranges).
        assertEquals("1,3,5,7,9,11,13,15周(单)", odd.display())
        assertEquals("第3周", single.display())
        assertEquals("单周", odd.parityLabel)
        // Weeks.ranges() is the reference's FormatLabel as data, and it agrees on the runs.
        assertEquals(listOf(1..1, 3..3, 5..5, 7..7, 9..9, 11..11, 13..13, 15..15), odd.ranges())
        assertEquals(listOf(3..3), single.ranges())
    }

    @Test
    fun bitsAboveTheTermsLengthStillExpandFully() {
        val mask = Weeks.fromWeeks(listOf(17, 18, 19))
        assertEquals(19, Weeks.highest(mask))
        assertEquals(listOf(17, 18, 19), Weeks.toWeeks(mask, 16))
        assertEquals("17-19", Weeks.formatLabel(mask, 16))
    }

    @Test
    fun oddAndEvenMasksAreGeneratedFromTheTermLengthNotHardCoded() {
        assertEquals(0x5555L, Weeks.oddMask(16).bits)
        assertEquals(0xaaaaL, Weeks.evenMask(16).bits)
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), Weeks.toWeeks(Weeks.oddMask(15), 15))
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14), Weeks.toWeeks(Weeks.evenMask(15), 15))
        // 20 周学期：硬编码 0x5555 会截断，泛化实现不会
        assertEquals(
            listOf(1, 3, 5, 7, 9, 11, 13, 15, 17, 19),
            Weeks.toWeeks(Weeks.oddMask(20), 20),
        )
        // 单双周在 WeekPattern 里的等价表达就是 isOddOnly / isEvenOnly / parityLabel。
        assertTrue(Weeks.oddMask(16).isOddOnly)
        assertTrue(Weeks.evenMask(16).isEvenOnly)
        assertEquals("单周", Weeks.oddMask(16).parityLabel)
        assertEquals("双周", Weeks.evenMask(16).parityLabel)
    }

    @Test
    fun filtersAndOverlapWork() {
        assertNull(Weeks.resolveFilter(WeekFilter.ALL, 16))
        assertEquals(0x5555L, Weeks.resolveFilter(WeekFilter.ODD, 16)!!.bits)
        assertEquals(0xaaaaL, Weeks.resolveFilter(WeekFilter.EVEN, 16)!!.bits)
        assertTrue(Weeks.overlap(Weeks.fromWeeks(listOf(1, 3)), Weeks.fromWeeks(listOf(2, 3))))
        assertFalse(Weeks.overlap(Weeks.fromWeeks(listOf(1, 3)), Weeks.fromWeeks(listOf(2, 4))))
        // WeekPattern spells the same test as an intersection.
        assertTrue((Weeks.fromWeeks(listOf(1, 3)) intersect Weeks.fromWeeks(listOf(2, 3))).isNotEmpty)
    }

    @Test
    fun theDefaultSlotTableMatchesTheTongjiCalendar() {
        assertEquals(11, TimetableDefaults.TONGJI_SLOTS.size)
        assertEquals("08:00", TimetableDefaults.TONGJI_SLOTS[0].begin)
        assertEquals("20:55", TimetableDefaults.TONGJI_SLOTS[10].end)
        // 不打乱调用方传入的集合，并且按下标排序，非正下标被丢掉
        val sorted = TimetableDefaults.slotsFromList(listOf(Slot(3, "c", "d"), Slot(1, "a", "b"), Slot(0, "x", "y")))
        assertEquals(listOf(1, 3), sorted.map { it.index })
        // 11 个节次的默认表就是同济表本身；别处则生成编号 1..n 的空时刻
        assertEquals(TimetableDefaults.TONGJI_SLOTS, TimetableDefaults.makeDefaultSlots())
        assertEquals(listOf(Slot(1, "", ""), Slot(2, "", "")), TimetableDefaults.makeDefaultSlots(2))
    }

    @Test
    fun timeHelpersKeepTheReferenceSemantics() {
        // 毫秒时间戳按 +08:00 归日：1789315200000 = 2026-09-14（周一）。
        assertEquals("2026-09-14", TongjiTime.msToIsoDate(1789315200000L))
        assertEquals("2026-09-14", TongjiTime.mondayOf("2026-09-14"))
        assertEquals("2026-09-14", TongjiTime.mondayOf("2026-09-16"))
        assertEquals("2026-09-07", TongjiTime.mondayOf("2026-09-13"))
        assertEquals("2026-09-21", TongjiTime.addDays("2026-09-14", 7))
        // Prefix parse tolerates trailing characters, like the reference (and like JS).
        assertEquals("2026-09-14", TongjiTime.mondayOf("2026-09-14T00:00"))
        // JS Date.UTC carries overflowing months/days.
        assertEquals("2026-03-02", TongjiTime.isoDateOrNull("2026-02-30").toString())
        assertNull(TongjiTime.mondayOf("not a date"))
        assertNull(TongjiTime.mondayOf(null))
    }
}
