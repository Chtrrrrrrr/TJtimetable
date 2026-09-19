package com.ranorac.tjtimetable.domain

/**
 * The set of teaching weeks in which a course session takes place.
 *
 * This is the single most important value type in the app, because 同济's
 * 教务系统 expresses week information in several mutually incompatible shapes,
 * and 单双周 (odd/even week alternation) is encoded differently in each of them.
 * Everything funnels through [parse], which never throws:
 *
 * | source                                                  | shape                      |
 * |---------------------------------------------------------|----------------------------|
 * | `v2/dc/teaching_info/student_timetable` → `week`         | `"1,2,3,4,17"`             |
 * | `v1/rt/onetongji/student_timetable` → `timeTableList.weeks` | `[1, 2, 3, ...]`        |
 * | `v1/rt/onetongji/student_timetable` → `timeTableList.weekNum` | `"[1-17]"`, `"[1-17单]"` |
 * | 教务系统网页复制粘贴                                      | `"1-8周,10-16周(双)"`, `"第1至17周"` |
 *
 * Weeks live in a bitmask (bit 0 is week 1) so union and intersection are single
 * instructions, and Room can persist a whole pattern as one `Long`.
 *
 * Weeks outside `1..64` are ignored rather than rejected: a malformed field from
 * the server must not lose the rest of the schedule.
 */
@JvmInline
value class WeekPattern private constructor(val bits: Long) {

    val isEmpty: Boolean get() = bits == 0L
    val isNotEmpty: Boolean get() = bits != 0L

    /** Ascending week numbers, e.g. `[1, 3, 5, 7]`. */
    val weekNumbers: List<Int>
        get() {
            if (bits == 0L) return emptyList()
            val out = ArrayList<Int>(java.lang.Long.bitCount(bits))
            for (w in 1..MAX_WEEK) if (bits and bit(w) != 0L) out.add(w)
            return out
        }

    val count: Int get() = java.lang.Long.bitCount(bits)
    val firstWeek: Int? get() = if (bits == 0L) null else java.lang.Long.numberOfTrailingZeros(bits) + 1
    val lastWeek: Int? get() = if (bits == 0L) null else MAX_WEEK - java.lang.Long.numberOfLeadingZeros(bits)

    fun contains(week: Int): Boolean = bits and bit(week) != 0L

    operator fun plus(other: WeekPattern): WeekPattern = WeekPattern(bits or other.bits)
    infix fun intersect(other: WeekPattern): WeekPattern = WeekPattern(bits and other.bits)
    infix fun without(other: WeekPattern): WeekPattern = WeekPattern(bits and other.bits.inv())

    /** The weeks this pattern covers, restricted to `1..[upTo]`. */
    fun limitedTo(upTo: Int): WeekPattern = WeekPattern(bits and maskBelow(upTo))

    // ---------------------------------------------------------------- parity

    /**
     * True when the session runs on odd weeks only and genuinely alternates,
     * i.e. there is more than one week. A plain single-week session is not
     * "单周" in any meaningful sense, so the UI must not label it that way.
     */
    val isOddOnly: Boolean get() = count > 1 && weekNumbers.all { it % 2 == 1 }

    /** Mirror of [isOddOnly] for 双周. */
    val isEvenOnly: Boolean get() = count > 1 && weekNumbers.all { it % 2 == 0 }

    /** True when every week from [firstWeek] to [lastWeek] is present. */
    val isContiguous: Boolean
        get() {
            if (bits == 0L) return false
            val first = firstWeek!!
            val last = lastWeek!!
            return count == last - first + 1
        }

    /**
     * Short label for 单/双 badges, or `null` when the pattern is not a clean
     * alternation. This is what drives the "单周"/"双周" chip on a course card.
     */
    val parityLabel: String?
        get() = when {
            isOddOnly -> "单周"
            isEvenOnly -> "双周"
            else -> null
        }

    // --------------------------------------------------------------- display

    /** Maximal contiguous runs, e.g. `[1..5, 7..9]`. */
    fun ranges(): List<IntRange> {
        if (bits == 0L) return emptyList()
        val out = ArrayList<IntRange>()
        var start = -1
        var prev = -1
        for (w in 1..MAX_WEEK) {
            if (bits and bit(w) == 0L) continue
            if (start == -1) { start = w; prev = w; continue }
            if (w == prev + 1) { prev = w; continue }
            out.add(start..prev)
            start = w; prev = w
        }
        if (start != -1) out.add(start..prev)
        return out
    }

    /** Compact human text, e.g. `1-17周`, `1-17单周`, `1-5,7-9周`. */
    fun display(): String {
        if (bits == 0L) return "无"
        val body = ranges().joinToString(",") { r ->
            if (r.first == r.last) "${r.first}" else "${r.first}-${r.last}"
        }
        val suffix = when {
            isOddOnly -> "周(单)"
            isEvenOnly -> "周(双)"
            else -> "周"
        }
        // A single week reads better as "第3周".
        if (count == 1) return "第${firstWeek}周"
        return body + suffix
    }

    /** Stable, re-parseable form used as the persisted column value. */
    fun canonical(): String = weekNumbers.joinToString(",")

    override fun toString(): String = display()

    companion object {
        /** Bitmask width. Tongji semesters run at most ~27 weeks, so 64 is ample. */
        const val MAX_WEEK = 64

        private const val ODD_MASK = 0x5555555555555555L  // weeks 1,3,5,...
        // Not written as a literal: 0xAAAA... is not representable as a positive
        // Long, and inverting the odd mask states the intent exactly.
        private val EVEN_MASK = ODD_MASK.inv()             // weeks 2,4,6,...

        val EMPTY: WeekPattern = WeekPattern(0L)

        internal fun bit(week: Int): Long =
            if (week in 1..MAX_WEEK) 1L shl (week - 1) else 0L

        private fun maskBelow(upTo: Int): Long =
            if (upTo <= 0) 0L
            else if (upTo >= MAX_WEEK) -1L
            else (1L shl upTo) - 1L

        fun of(weeks: Iterable<Int>): WeekPattern {
            var bits = 0L
            for (w in weeks) bits = bits or bit(w)
            return WeekPattern(bits)
        }

        /**
         * Rebuilds a pattern from a persisted bitmask (Room stores it as a `Long`).
         * Bits outside `1..64` are simply never set, so no validation is needed.
         */
        fun fromBits(bits: Long): WeekPattern = WeekPattern(bits)

        fun of(vararg weeks: Int): WeekPattern = of(weeks.asIterable())

        /** Every week from [first] to [last] inclusive. */
        fun range(first: Int, last: Int): WeekPattern =
            if (first > last) EMPTY else of(first..last)

        /**
         * Parses every week shape 同济 emits, plus the shapes students paste out
         * of the 教务网页. Returns [EMPTY] for anything unparseable — callers get
         * a session with no weeks rather than an exception mid-import.
         */
        fun parse(raw: String?): WeekPattern {
            if (raw.isNullOrBlank()) return EMPTY
            val s = normalize(raw)
            if (s.isEmpty()) return EMPTY

            // Parity is resolved PER TOKEN, not once for the whole string.
            //
            // The 教务网页 emits mixed shapes like "1-8单,10-16双". A single global
            // parity flag let the later 双 overwrite the earlier 单, silently
            // turning odd weeks into even ones — a wrong timetable with no error.
            // Each token therefore carries its own 单/双 marker.
            var bits = 0L
            for (token in s.split(',')) {
                if (token.isBlank()) continue

                val wantsOdd = token.contains('单') || token.contains("奇数")
                val wantsEven = token.contains('双') || token.contains("偶数")

                // Strip every non-numeric, non-separator character, which also
                // removes brackets, 第/周 and the parity markers themselves.
                val body = token.filter { it.isDigit() || it == '-' }
                if (body.isEmpty()) continue

                var tokenBits = 0L
                val dash = body.indexOf('-')
                if (dash < 0) {
                    body.toIntOrNull()?.let { tokenBits = tokenBits or bit(it) }
                } else {
                    val lo = body.substring(0, dash).toIntOrNull()
                    val hi = body.substring(dash + 1).toIntOrNull()
                    // "5-3", "0-5" and "1-2-3" are malformed; drop just the token.
                    if (lo != null && hi != null && lo >= 1 && hi >= lo) {
                        // Clamp rather than bail: "1-99" should still yield 1..64.
                        for (w in lo..minOf(hi, MAX_WEEK)) tokenBits = tokenBits or bit(w)
                    }
                }

                tokenBits = when {
                    wantsOdd && !wantsEven -> tokenBits and ODD_MASK
                    wantsEven && !wantsOdd -> tokenBits and EVEN_MASK
                    else -> tokenBits
                }
                bits = bits or tokenBits
            }
            return WeekPattern(bits)
        }

        /**
         * Folds the several week fields the API may return into one pattern,
         * preferring the most explicit representation available.
         *
         * `v1/rt/onetongji` returns both a resolved `weeks` array and a raw
         * `weekNum` string, so the array wins when it is non-empty.
         */
        fun fromApiFields(weeks: List<Int>?, weekNum: String?, week: String?): WeekPattern {
            if (!weeks.isNullOrEmpty()) {
                val p = of(weeks)
                if (p.isNotEmpty) return p
            }
            val fromNum = parse(weekNum)
            if (fromNum.isNotEmpty) return fromNum
            return parse(week)
        }

        /** Normalises full-width characters, CJK separators and dash variants. */
        private fun normalize(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (ch in raw) {
                val c = when (ch) {
                    '，', '、', '；', ';', '/', '|' -> ','
                    '～', '~', '—', '–', '−', '－', 'ー', '至' -> '-'
                    else -> ch
                }
                // '０'..'９' -> '0'..'9'
                sb.append(if (c in '０'..'９') ('0' + (c - '０')) else c)
            }
            return sb.toString()
        }
    }
}
