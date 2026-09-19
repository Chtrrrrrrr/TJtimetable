package com.ranorac.tjtimetable.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [WeekPattern], the 单双周 parser.
 *
 * Three properties matter more than any other here:
 *
 *  1. [WeekPattern.parse] must never throw. A malformed week field returned by the
 *     教务系统 may leave a course with no weeks, but it must never blow up a whole
 *     schedule import, so every garbage input is asserted to yield [WeekPattern.EMPTY].
 *  2. 单/双 are parity *modifiers*, not separators, so `"[1-17单]"`, `"1-17(单)"`,
 *     `"1-17单周"` and `"奇数周1-17"` must all resolve to the same odd-week set.
 *  3. Parity is applied **per comma-separated token**, so `"1-8单,10-16双"` keeps the
 *     odd half odd and the even half even, and a marker never reaches across a comma
 *     into a neighbouring token.
 *
 * [WeekPattern] is a `@JvmInline value class` with a private constructor, so every
 * pattern in these tests is built through the companion factories
 * ([WeekPattern.parse], [WeekPattern.of], [WeekPattern.range], [WeekPattern.fromBits]).
 */
class WeekPatternTest {

    // ------------------------------------------------------------------
    // Shapes the 教务系统 actually emits
    // ------------------------------------------------------------------

    @Test
    fun `parse comma list from the v2 week field`() {
        val p = WeekPattern.parse("1,2,3,4,5")
        assertEquals(listOf(1, 2, 3, 4, 5), p.weekNumbers)
        assertEquals(5, p.count)
        assertEquals(1, p.firstWeek)
        assertEquals(5, p.lastWeek)
        assertTrue(p.isContiguous)
        assertFalse(p.isEmpty)
        assertTrue(p.isNotEmpty)
    }

    @Test
    fun `parse bracketed range from weekNum`() {
        val p = WeekPattern.parse("[1-17]")
        assertEquals(17, p.count)
        assertEquals(1, p.firstWeek)
        assertEquals(17, p.lastWeek)
        assertTrue(p.isContiguous)
        assertEquals(WeekPattern.range(1, 17), p)
    }

    @Test
    fun `parse bare range`() {
        val p = WeekPattern.parse("1-17")
        assertEquals(17, p.count)
        assertEquals(WeekPattern.range(1, 17), p)
        assertNull(p.parityLabel)
    }

    @Test
    fun `parse 第N-M周 shape pasted from the timetable webpage`() {
        val p = WeekPattern.parse("第1-17周")
        assertEquals(17, p.count)
        assertEquals(1, p.firstWeek)
        assertEquals(17, p.lastWeek)
    }

    @Test
    fun `parse 第N至M周 shape`() {
        assertEquals(WeekPattern.range(1, 17), WeekPattern.parse("第1至17周"))
    }

    @Test
    fun `parse tilde range`() {
        assertEquals(WeekPattern.range(1, 17), WeekPattern.parse("1~17"))
    }

    @Test
    fun `parse full width tilde range`() {
        assertEquals(WeekPattern.range(1, 17), WeekPattern.parse("1～17"))
    }

    @Test
    fun `parse em dash range`() {
        assertEquals(WeekPattern.range(1, 17), WeekPattern.parse("1—17"))
    }

    @Test
    fun `parse en dash and minus sign variants`() {
        assertEquals(WeekPattern.range(1, 17), WeekPattern.parse("1–17"))
        assertEquals(WeekPattern.range(1, 17), WeekPattern.parse("1−17"))
        assertEquals(WeekPattern.range(1, 17), WeekPattern.parse("1－17"))
    }

    @Test
    fun `parse full width digits and comma`() {
        val p = WeekPattern.parse("１，２，３")
        assertEquals(listOf(1, 2, 3), p.weekNumbers)
    }

    @Test
    fun `parse full width digits inside a range`() {
        assertEquals(WeekPattern.range(1, 17), WeekPattern.parse("１－１７"))
    }

    @Test
    fun `parse CJK and punctuation list separators`() {
        assertEquals(listOf(1, 2, 3), WeekPattern.parse("1、2、3").weekNumbers)
        assertEquals(listOf(1, 2, 3), WeekPattern.parse("1；2；3").weekNumbers)
        assertEquals(listOf(1, 2, 3), WeekPattern.parse("1;2;3").weekNumbers)
        assertEquals(listOf(1, 2, 3), WeekPattern.parse("1/2/3").weekNumbers)
        assertEquals(listOf(1, 2, 3), WeekPattern.parse("1|2|3").weekNumbers)
    }

    @Test
    fun `parse tolerates surrounding and inner whitespace`() {
        assertEquals(listOf(1, 2, 3), WeekPattern.parse(" 1 , 2 ,3 ").weekNumbers)
        assertEquals(listOf(1, 2, 3), WeekPattern.parse("\t1,2,3\n").weekNumbers)
        assertEquals(WeekPattern.range(1, 17), WeekPattern.parse(" [1-17] "))
    }

    // ------------------------------------------------------------------
    // Single weeks, clamping and out of range input
    // ------------------------------------------------------------------

    @Test
    fun `parse single week`() {
        val p = WeekPattern.parse("3")
        assertEquals(1, p.count)
        assertEquals(3, p.firstWeek)
        assertEquals(3, p.lastWeek)
        assertEquals(listOf(3), p.weekNumbers)
        assertTrue(p.isContiguous)
    }

    @Test
    fun `a lone week is not labelled 单周`() {
        // One week is not an alternation, so the 单/双 chip must stay empty.
        // INTENTIONAL, not a bug: isOddOnly/isEvenOnly require count > 1 by design.
        val p = WeekPattern.parse("3")
        assertFalse(p.isOddOnly)
        assertFalse(p.isEvenOnly)
        assertNull(p.parityLabel)
        assertEquals("第3周", p.display())
    }

    @Test
    fun `parse clamps a range that runs past the bitmask width`() {
        val p = WeekPattern.parse("1-99")
        assertEquals(WeekPattern.MAX_WEEK, p.count)
        assertEquals(1, p.firstWeek)
        assertEquals(64, p.lastWeek)
        assertEquals(WeekPattern.range(1, 64), p)
    }

    @Test
    fun `parse accepts the last representable week`() {
        assertEquals(1, WeekPattern.parse("64").count)
        assertEquals(listOf(64), WeekPattern.parse("64").weekNumbers)
        assertEquals(64, WeekPattern.parse("1-64").count)
    }

    @Test
    fun `parse ignores a range whose lower bound is below one`() {
        assertTrue(WeekPattern.parse("0-5").isEmpty)
    }

    @Test
    fun `parse ignores a reversed or truncated range`() {
        assertTrue(WeekPattern.parse("5-3").isEmpty)
        assertTrue(WeekPattern.parse("1-").isEmpty)
        assertTrue(WeekPattern.parse("-5").isEmpty)
        assertTrue(WeekPattern.parse("1-2-3").isEmpty)
    }

    @Test
    fun `parse ignores a week number beyond the bitmask width`() {
        assertTrue(WeekPattern.parse("65").isEmpty)
        assertTrue(WeekPattern.parse("0").isEmpty)
    }

    @Test
    fun `of ignores out of range week numbers`() {
        assertTrue(WeekPattern.of(listOf(0, 65, -3)).isEmpty)
        assertTrue(WeekPattern.of(65).isEmpty)
        assertEquals(listOf(1, 64), WeekPattern.of(listOf(1, 64)).weekNumbers)
    }

    @Test
    fun `parse extracts digits from surrounding junk characters`() {
        // Everything that is not a digit, dash or comma is stripped within a token,
        // so letters glued to a number are silently dropped.
        // INTENTIONAL, not a bug: a fail-open timetable that shows a possibly-cancelled
        // class beats one that silently hides a real one.
        assertEquals(listOf(1), WeekPattern.parse("abc1def").weekNumbers)
    }

    // ------------------------------------------------------------------
    // 单周 (odd weeks)
    // ------------------------------------------------------------------

    @Test
    fun `parse odd-week bracket form`() {
        val p = WeekPattern.parse("[1-17单]")
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15, 17), p.weekNumbers)
        assertEquals(9, p.count)
        assertTrue(p.isOddOnly)
        assertFalse(p.isEvenOnly)
        assertEquals("单周", p.parityLabel)
    }

    @Test
    fun `parse odd-week parenthesis form`() {
        val p = WeekPattern.parse("1-17(单)")
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15, 17), p.weekNumbers)
        assertTrue(p.isOddOnly)
        assertEquals("单周", p.parityLabel)
    }

    @Test
    fun `parse odd-week 单周 suffix form`() {
        val p = WeekPattern.parse("1-17单周")
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15, 17), p.weekNumbers)
        assertTrue(p.isOddOnly)
        assertEquals("单周", p.parityLabel)
    }

    @Test
    fun `parse odd-week 奇数周 prefix form`() {
        val p = WeekPattern.parse("奇数周1-17")
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15, 17), p.weekNumbers)
        assertEquals(9, p.count)
        assertTrue(p.isOddOnly)
        assertEquals("单周", p.parityLabel)
    }

    @Test
    fun `parse odd-week plain 单 suffix form`() {
        val p = WeekPattern.parse("1-17单")
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15, 17), p.weekNumbers)
        assertTrue(p.isOddOnly)
    }

    // ------------------------------------------------------------------
    // 双周 (even weeks)
    // ------------------------------------------------------------------

    @Test
    fun `parse even-week bracket form`() {
        val p = WeekPattern.parse("[2-16双]")
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14, 16), p.weekNumbers)
        assertEquals(8, p.count)
        assertTrue(p.isEvenOnly)
        assertFalse(p.isOddOnly)
        assertEquals("双周", p.parityLabel)
    }

    @Test
    fun `parse even-week 双周 suffix form`() {
        val p = WeekPattern.parse("2-16双周")
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14, 16), p.weekNumbers)
        assertTrue(p.isEvenOnly)
        assertEquals("双周", p.parityLabel)
    }

    @Test
    fun `parse even-week parenthesis form`() {
        val p = WeekPattern.parse("2-16(双)")
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14, 16), p.weekNumbers)
        assertTrue(p.isEvenOnly)
    }

    @Test
    fun `parse even-week 偶数周 prefix form`() {
        val p = WeekPattern.parse("偶数周2-16")
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14, 16), p.weekNumbers)
        assertTrue(p.isEvenOnly)
        assertEquals("双周", p.parityLabel)
    }

    @Test
    fun `parse explicit odd enumeration is odd only`() {
        val p = WeekPattern.parse("1,3,5,7")
        assertEquals(listOf(1, 3, 5, 7), p.weekNumbers)
        assertEquals(4, p.count)
        assertTrue(p.isOddOnly)
        assertFalse(p.isEvenOnly)
        assertEquals("单周", p.parityLabel)
    }

    @Test
    fun `parse explicit even enumeration is even only`() {
        val p = WeekPattern.parse("2,4,6")
        assertEquals(listOf(2, 4, 6), p.weekNumbers)
        assertTrue(p.isEvenOnly)
        assertEquals("双周", p.parityLabel)
    }

    @Test
    fun `a mixed pattern is not labelled odd or even`() {
        val p = WeekPattern.parse("1-17")
        assertFalse(p.isOddOnly)
        assertFalse(p.isEvenOnly)
        assertNull(p.parityLabel)
    }

    @Test
    fun `a parity mask can collapse a pattern to one week and lose the badge`() {
        // "1-2单" keeps week 1 only, and a single week is never labelled 单周.
        // INTENTIONAL, not a bug: see the count > 1 rule in isOddOnly/isEvenOnly.
        val p = WeekPattern.parse("1-2单")
        assertEquals(listOf(1), p.weekNumbers)
        assertEquals(1, p.count)
        assertNull(p.parityLabel)
    }

    // ------------------------------------------------------------------
    // Multiple ranges
    // ------------------------------------------------------------------

    @Test
    fun `parse multiple ranges`() {
        val p = WeekPattern.parse("1-8,10-16")
        assertEquals(15, p.count)
        assertEquals(listOf(1..8, 10..16), p.ranges())
        assertFalse(p.isContiguous)
        assertEquals(1, p.firstWeek)
        assertEquals(16, p.lastWeek)
    }

    @Test
    fun `parse multiple ranges separated by a full width comma`() {
        assertEquals(15, WeekPattern.parse("1-8，10-16").count)
    }

    @Test
    fun `parse ranges with trailing and repeated separators`() {
        val p = WeekPattern.parse("1-8,,10-16,")
        assertEquals(15, p.count)
        assertEquals(listOf(1..8, 10..16), p.ranges())
    }

    @Test
    fun `parse mixed odd and even ranges unions both parities`() {
        // Parity is resolved per token, so the 单 run stays odd and the 双 run stays
        // even instead of one mask overwriting the other. (Earlier code held a single
        // global parity slot and silently turned the odd half into even weeks.)
        val p = WeekPattern.parse("1-8单,10-16双")
        assertEquals(listOf(1, 3, 5, 7, 10, 12, 14, 16), p.weekNumbers)
        assertEquals(8, p.count)
        assertEquals(
            listOf(1..1, 3..3, 5..5, 7..7, 10..10, 12..12, 14..14, 16..16),
            p.ranges(),
        )
        assertFalse(p.isOddOnly)
        assertFalse(p.isEvenOnly)
        assertNull(p.parityLabel)
    }

    @Test
    fun `parse applies a parity marker only to its own token`() {
        // The 单 on the second token does not reach back over the unmarked first one,
        // so "1-8,10-16单" is every week of 1-8 plus the odd weeks of 10-16.
        val p = WeekPattern.parse("1-8,10-16单")
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 11, 13, 15), p.weekNumbers)
        assertEquals(11, p.count)
        assertEquals(listOf(1..8, 11..11, 13..13, 15..15), p.ranges())
        assertNull(p.parityLabel)
    }

    @Test
    fun `parse handles an even run followed by an odd run`() {
        val p = WeekPattern.parse("1-4双,6-10单")
        assertEquals(listOf(2, 4, 7, 9), p.weekNumbers)
        assertEquals(4, p.count)
        // The two runs together are not a clean alternation, so no 单/双 badge.
        assertFalse(p.isOddOnly)
        assertFalse(p.isEvenOnly)
        assertNull(p.parityLabel)
    }

    @Test
    fun `parse a lone parity marker still masks its own token`() {
        // "1-17单" is one token, so the marker still applies to the whole range.
        val p = WeekPattern.parse("1-17单")
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15, 17), p.weekNumbers)
        assertEquals(9, p.count)
        assertTrue(p.isOddOnly)
        assertEquals("单周", p.parityLabel)
    }

    // ------------------------------------------------------------------
    // Malformed input — parse must never throw
    // ------------------------------------------------------------------

    @Test
    fun `malformed input yields an empty pattern instead of throwing`() {
        val garbage = listOf(
            "abc",
            "周",
            "第周",
            "单",
            "双",
            "奇数",
            "偶数",
            "---",
            "，，",
            "null",
            "0",
            "65",
            "1-2-3",
        )
        for (raw in garbage) {
            val p = WeekPattern.parse(raw)
            assertTrue("expected an empty pattern for <$raw>", p.isEmpty)
            assertEquals("expected zero weeks for <$raw>", 0, p.count)
        }
    }

    @Test
    fun `null yields an empty pattern`() {
        val p = WeekPattern.parse(null)
        assertTrue(p.isEmpty)
        assertEquals(WeekPattern.EMPTY, p)
        assertEquals(0, p.count)
        assertNull(p.firstWeek)
        assertNull(p.lastWeek)
    }

    @Test
    fun `empty string yields an empty pattern`() {
        val p = WeekPattern.parse("")
        assertTrue(p.isEmpty)
        assertEquals(WeekPattern.EMPTY, p)
    }

    @Test
    fun `blank whitespace yields an empty pattern`() {
        assertTrue(WeekPattern.parse("   ").isEmpty)
        assertTrue(WeekPattern.parse("\t\n").isEmpty)
        assertTrue(WeekPattern.parse("　").isEmpty)
    }

    @Test
    fun `an empty pattern reports no weeks`() {
        val p = WeekPattern.EMPTY
        assertEquals(0, p.count)
        assertEquals(emptyList<Int>(), p.weekNumbers)
        assertEquals(emptyList<IntRange>(), p.ranges())
        assertNull(p.firstWeek)
        assertNull(p.lastWeek)
        assertFalse(p.isContiguous)
        assertFalse(p.isOddOnly)
        assertFalse(p.isEvenOnly)
        assertNull(p.parityLabel)
        assertEquals("无", p.display())
    }

    // ------------------------------------------------------------------
    // canonical round trip
    // ------------------------------------------------------------------

    @Test
    fun `canonical round trips for representative patterns`() {
        val patterns = listOf(
            WeekPattern.EMPTY,
            WeekPattern.of(3),
            WeekPattern.range(1, 17),
            WeekPattern.range(1, 64),
            WeekPattern.range(1, 5) + WeekPattern.of(7, 9),
            WeekPattern.parse("[1-17单]"),
            WeekPattern.parse("[2-16双]"),
            WeekPattern.parse("1-99"),
            WeekPattern.parse("1-8，10-16"),
        )
        for (p in patterns) {
            assertEquals("round trip failed for <${p.canonical()}>", p, WeekPattern.parse(p.canonical()))
        }
    }

    @Test
    fun `canonical of an empty pattern is the empty string`() {
        assertEquals("", WeekPattern.EMPTY.canonical())
    }

    @Test
    fun `canonical lists every week ascending`() {
        assertEquals("1,2,3,4,5", WeekPattern.range(1, 5).canonical())
        assertEquals("1,3,5", WeekPattern.of(3, 1, 5).canonical())
    }

    // ------------------------------------------------------------------
    // ranges()
    // ------------------------------------------------------------------

    @Test
    fun `ranges splits maximal contiguous runs`() {
        assertEquals(listOf(1..5, 7..9), WeekPattern.parse("1-5,7-9").ranges())
        assertEquals(listOf(1..5), WeekPattern.range(1, 5).ranges())
        assertEquals(listOf(1..1, 3..3, 5..5), WeekPattern.of(1, 3, 5).ranges())
    }

    @Test
    fun `ranges of the empty pattern is empty`() {
        assertEquals(emptyList<IntRange>(), WeekPattern.EMPTY.ranges())
    }

    @Test
    fun `ranges merges weeks that became adjacent through union`() {
        assertEquals(listOf(1..10), (WeekPattern.range(1, 5) + WeekPattern.range(6, 10)).ranges())
    }

    // ------------------------------------------------------------------
    // display()
    // ------------------------------------------------------------------

    @Test
    fun `display of the empty pattern`() {
        assertEquals("无", WeekPattern.EMPTY.display())
    }

    @Test
    fun `display of a single week reads as 第N周`() {
        assertEquals("第3周", WeekPattern.of(3).display())
        assertEquals("第64周", WeekPattern.of(64).display())
    }

    @Test
    fun `display of a contiguous range`() {
        assertEquals("1-17周", WeekPattern.range(1, 17).display())
    }

    @Test
    fun `display of multiple runs`() {
        assertEquals("1-5,7-9周", WeekPattern.parse("1-5,7-9").display())
        assertEquals("1-8,10-16周", WeekPattern.parse("1-8,10-16").display())
    }

    @Test
    fun `display of odd weeks`() {
        assertEquals("1,3,5,7,9,11,13,15,17周(单)", WeekPattern.parse("[1-17单]").display())
    }

    @Test
    fun `display of even weeks`() {
        assertEquals("2,4,6,8,10,12,14,16周(双)", WeekPattern.parse("[2-16双]").display())
    }

    @Test
    fun `toString mirrors display`() {
        val p = WeekPattern.parse("1-8,10-16")
        assertEquals(p.display(), p.toString())
    }

    // ------------------------------------------------------------------
    // Set algebra
    // ------------------------------------------------------------------

    @Test
    fun `plus is union`() {
        val p = WeekPattern.range(1, 5) + WeekPattern.of(7, 9)
        assertEquals(listOf(1, 2, 3, 4, 5, 7, 9), p.weekNumbers)
        assertEquals(listOf(1..5, 7..7, 9..9), p.ranges())
        assertEquals(7, p.count)
    }

    @Test
    fun `plus keeps an overlapping week once`() {
        assertEquals(WeekPattern.range(1, 5), WeekPattern.range(1, 3) + WeekPattern.range(3, 5))
    }

    @Test
    fun `plus with the empty pattern changes nothing`() {
        assertEquals(WeekPattern.range(1, 5), WeekPattern.range(1, 5) + WeekPattern.EMPTY)
    }

    @Test
    fun `intersect keeps only shared weeks`() {
        assertEquals(WeekPattern.range(3, 5), WeekPattern.range(1, 5) intersect WeekPattern.range(3, 7))
    }

    @Test
    fun `intersect with a disjoint pattern is empty`() {
        assertTrue((WeekPattern.range(1, 3) intersect WeekPattern.range(5, 7)).isEmpty)
        assertTrue((WeekPattern.range(1, 3) intersect WeekPattern.EMPTY).isEmpty)
    }

    @Test
    fun `without removes the weeks of the other pattern`() {
        assertEquals(WeekPattern.of(1, 2, 4, 5), WeekPattern.range(1, 5) without WeekPattern.of(3))
        assertEquals(WeekPattern.of(1, 5), WeekPattern.range(1, 5) without WeekPattern.range(2, 4))
    }

    @Test
    fun `without a disjoint pattern changes nothing`() {
        assertEquals(WeekPattern.range(1, 5), WeekPattern.range(1, 5) without WeekPattern.of(9))
    }

    @Test
    fun `limitedTo truncates weeks above the limit`() {
        assertEquals(WeekPattern.range(1, 5), WeekPattern.range(1, 10).limitedTo(5))
        assertEquals(WeekPattern.of(1, 3), WeekPattern.parse("[1-17单]").limitedTo(4))
    }

    @Test
    fun `limitedTo zero or below is empty`() {
        assertTrue(WeekPattern.range(1, 10).limitedTo(0).isEmpty)
        assertTrue(WeekPattern.range(1, 10).limitedTo(-4).isEmpty)
    }

    @Test
    fun `limitedTo beyond the last week keeps everything`() {
        assertEquals(WeekPattern.range(1, 10), WeekPattern.range(1, 10).limitedTo(64))
        assertEquals(WeekPattern.range(1, 10), WeekPattern.range(1, 10).limitedTo(70))
    }

    // ------------------------------------------------------------------
    // fromApiFields
    // ------------------------------------------------------------------

    @Test
    fun `fromApiFields prefers the resolved weeks array`() {
        val p = WeekPattern.fromApiFields(weeks = listOf(1, 3, 5), weekNum = "[1-17]", week = "2-16")
        assertEquals(listOf(1, 3, 5), p.weekNumbers)
    }

    @Test
    fun `fromApiFields falls back to weekNum when the array is absent`() {
        assertEquals(WeekPattern.range(1, 17), WeekPattern.fromApiFields(null, "[1-17]", "9"))
    }

    @Test
    fun `fromApiFields falls back to weekNum when the array is empty`() {
        assertEquals(WeekPattern.range(1, 17), WeekPattern.fromApiFields(emptyList(), "[1-17]", null))
    }

    @Test
    fun `fromApiFields ignores an array whose entries are all out of range`() {
        val p = WeekPattern.fromApiFields(listOf(0, 99), "[1-17单]", null)
        assertTrue(p.isOddOnly)
        assertEquals(9, p.count)
    }

    @Test
    fun `fromApiFields falls back to the week string when weekNum is unparseable`() {
        assertEquals(WeekPattern.range(5, 8), WeekPattern.fromApiFields(null, "abc", "5-8"))
        assertEquals(WeekPattern.range(5, 8), WeekPattern.fromApiFields(null, "", "5-8"))
    }

    @Test
    fun `fromApiFields falls back to the week string when weekNum is null`() {
        assertEquals(WeekPattern.of(5), WeekPattern.fromApiFields(null, null, "5"))
    }

    @Test
    fun `fromApiFields with no input at all is empty`() {
        assertTrue(WeekPattern.fromApiFields(null, null, null).isEmpty)
        assertTrue(WeekPattern.fromApiFields(emptyList(), "", "").isEmpty)
    }

    // ------------------------------------------------------------------
    // Accessors and factories
    // ------------------------------------------------------------------

    @Test
    fun `contains is true only for member weeks`() {
        val p = WeekPattern.parse("1-5")
        assertTrue(p.contains(1))
        assertTrue(p.contains(5))
        assertFalse(p.contains(6))
        assertFalse(p.contains(0))
        assertFalse(p.contains(65))
        assertFalse(WeekPattern.EMPTY.contains(1))
    }

    @Test
    fun `firstWeek lastWeek and count describe the set`() {
        val p = WeekPattern.of(3, 4, 9)
        assertEquals(3, p.firstWeek)
        assertEquals(9, p.lastWeek)
        assertEquals(3, p.count)
        assertFalse(p.isContiguous)
    }

    @Test
    fun `weekNumbers are ascending even for unsorted input`() {
        assertEquals(listOf(1, 2, 5, 9), WeekPattern.of(9, 1, 5, 2).weekNumbers)
    }

    @Test
    fun `isContiguous is false for a gap and true for a run`() {
        assertTrue(WeekPattern.range(4, 8).isContiguous)
        assertFalse(WeekPattern.parse("4-8,10").isContiguous)
        assertFalse(WeekPattern.EMPTY.isContiguous)
    }

    @Test
    fun `of vararg and of iterable agree`() {
        assertEquals(WeekPattern.of(1, 2, 3), WeekPattern.of(listOf(1, 2, 3)))
        assertEquals(WeekPattern.of(1, 2), WeekPattern.of(1, 2, 2, 1))
        assertTrue(WeekPattern.of(emptyList()).isEmpty)
    }

    @Test
    fun `fromBits rebuilds the pattern a persisted bitmask stands for`() {
        val p = WeekPattern.parse("[1-17单]")
        assertEquals(p, WeekPattern.fromBits(p.bits))
        assertEquals(WeekPattern.range(1, 5), WeekPattern.fromBits(WeekPattern.range(1, 5).bits))
        assertEquals(WeekPattern.EMPTY, WeekPattern.fromBits(0L))
        assertTrue(WeekPattern.fromBits(0L).isEmpty)
    }

    @Test
    fun `range with first after last is empty`() {
        assertTrue(WeekPattern.range(5, 3).isEmpty)
        assertEquals(WeekPattern.of(4), WeekPattern.range(4, 4))
    }

    @Test
    fun `MAX_WEEK is the bitmask width`() {
        assertEquals(64, WeekPattern.MAX_WEEK)
    }
}
