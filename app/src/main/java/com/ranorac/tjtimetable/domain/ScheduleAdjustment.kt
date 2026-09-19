package com.ranorac.tjtimetable.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the school calendar says about one date (`v1/rt/onetongji/calendar`). */
enum class CalendarDayKind(val apiCode: String, val label: String) {
    /** 1 = 节假日 */
    HOLIDAY("1", "节假日"),

    /** 2 = 工作日（正常上班 + 调休补课日） */
    WORKDAY("2", "工作日"),

    /** 3 = 周末 */
    WEEKEND("3", "周末"),

    /** 4 = 寒假 */
    WINTER_BREAK("4", "寒假"),

    /** 5 = 暑期 */
    SUMMER_BREAK("5", "暑期"),

    UNKNOWN("", "未录入");

    /** True when no classes ever run, regardless of weekday. */
    val isNonTeaching: Boolean
        get() = this == HOLIDAY || this == WINTER_BREAK || this == SUMMER_BREAK

    companion object {
        fun fromApi(code: String?): CalendarDayKind =
            entries.firstOrNull { it.apiCode == code } ?: UNKNOWN
    }
}

/** Where an adjustment came from. Manual always wins. */
enum class AdjustmentSource(val label: String) {
    /** Straight from the API's day-type map. */
    API("校历"),

    /** Derived locally from holiday/workday blocks — best effort, shown as 推测. */
    INFERRED("推测"),

    /** Set by the student. */
    MANUAL("手动"),
}

/**
 * A per-date override of which course schedule actually runs.
 *
 * This is how 调休串休 is modelled. The school calendar API only reports *whether*
 * a date is a teaching day; it never says *which weekday's* timetable a 补课日
 * follows. That mapping is published only in prose, in the 教务处 notice, so it is
 * stored explicitly here and can be inferred, hand-edited, or parsed from the
 * notice text via [ScheduleAdjustmentSet.parseNotice].
 */
data class DayAdjustment(
    val date: LocalDate,
    val kind: CalendarDayKind = CalendarDayKind.UNKNOWN,
    /**
     * The weekday whose schedule runs on [date].
     *
     * `null` means "use the date's own weekday" — the normal case.
     */
    val followsWeekday: DayOfWeek? = null,
    /** Force no classes (e.g. 运动会, 临时停课). Beats [kind]. */
    val noClasses: Boolean = false,
    val source: AdjustmentSource = AdjustmentSource.API,
    val note: String? = null,
) {
    /**
     * True when this is a 补课日, i.e. it runs a different weekday's schedule.
     * A date explicitly stopped ([noClasses]) is never a 补课日, whatever weekday
     * it was previously pointed at.
     */
    val isMakeup: Boolean
        get() = !noClasses && followsWeekday != null && followsWeekday != date.dayOfWeek
}

/**
 * The resolved 调休串休 state for one semester.
 *
 * Resolution order for "what runs on this date?":
 *  1. [DayAdjustment.noClasses] — nothing runs.
 *  2. [DayAdjustment.followsWeekday] — that weekday's timetable runs.
 *  3. [DayAdjustment.kind] being a holiday/break — nothing runs.
 *  4. Otherwise the date's own weekday, so genuine weekend classes still show.
 */
class ScheduleAdjustmentSet(private val byDate: Map<LocalDate, DayAdjustment>) {

    val size: Int get() = byDate.size

    fun get(date: LocalDate): DayAdjustment? = byDate[date]

    /** The weekday timetable that runs on [date], or null when no classes run. */
    fun effectiveWeekday(date: LocalDate): DayOfWeek? {
        val adj = byDate[date] ?: return date.dayOfWeek
        if (adj.noClasses) return null
        adj.followsWeekday?.let { return it }
        if (adj.kind.isNonTeaching) return null
        return date.dayOfWeek
    }

    fun isTeachingDay(date: LocalDate): Boolean = effectiveWeekday(date) != null

    /** Every date in `[from, to]` on which no classes run. */
    fun nonTeachingDates(from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = from
        while (!d.isAfter(to)) {
            if (!isTeachingDay(d)) out.add(d)
            d = d.plusDays(1)
        }
        return out
    }

    /** Returns a new set with [adjustment] applied (manual entries replace inferred ones). */
    fun with(adjustment: DayAdjustment): ScheduleAdjustmentSet {
        val existing = byDate[adjustment.date]
        // A manual edit must always win, even over an API-sourced row.
        if (existing != null && existing.source == AdjustmentSource.MANUAL &&
            adjustment.source != AdjustmentSource.MANUAL
        ) return this
        return ScheduleAdjustmentSet(byDate + (adjustment.date to adjustment))
    }

    fun withAll(additions: Iterable<DayAdjustment>): ScheduleAdjustmentSet =
        additions.fold(this) { acc, a -> acc.with(a) }

    fun without(date: LocalDate): ScheduleAdjustmentSet =
        ScheduleAdjustmentSet(byDate - date)

    /** Just the hand-edited rows, which are the ones worth persisting. */
    fun manualEntries(): List<DayAdjustment> =
        byDate.values.filter { it.source == AdjustmentSource.MANUAL }.sortedBy { it.date }

    val all: List<DayAdjustment> get() = byDate.values.sortedBy { it.date }

    companion object {
        val EMPTY = ScheduleAdjustmentSet(emptyMap())

        /**
         * Builds the base set from the `v1/rt/onetongji/calendar` payload, which
         * is a map of `"yyyy-MM-dd" -> "1".."5"`.
         */
        fun fromApiCalendar(raw: Map<String, String>): ScheduleAdjustmentSet {
            val map = HashMap<LocalDate, DayAdjustment>(raw.size)
            for ((dateText, code) in raw) {
                val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: continue
                map[date] = DayAdjustment(
                    date = date,
                    kind = CalendarDayKind.fromApi(code),
                    source = AdjustmentSource.API,
                )
            }
            return ScheduleAdjustmentSet(map)
        }

        /**
         * Best-effort inference of which weekday a 补课日 follows.
         *
         * IMPORTANT — this is a heuristic, and it is labelled [AdjustmentSource.INFERRED]
         * precisely because it can be wrong. The official mapping is only ever
         * published in prose, so the app never pretends to know it: the UI shows
         * inferred rows as 推测 and lets the student correct them, and
         * [parseNotice] can extract the authoritative mapping from the notice text.
         *
         * The rule applied is the common one: weekend days marked as workdays take
         * the schedule of the weekdays swallowed by the adjacent holiday block,
         * counted outwards-in, because that is how 国务院/学校 usually pair them.
         */
        fun inferMakeupDays(base: ScheduleAdjustmentSet): ScheduleAdjustmentSet {
            if (base.byDate.isEmpty()) return base

            val dates = base.byDate.keys.sorted()
            val from = dates.first()
            val to = dates.last()

            var result = base

            // Walk maximal runs of same-kind days.
            var runStart = from
            while (!runStart.isAfter(to)) {
                val kind = base.byDate[runStart]?.kind ?: CalendarDayKind.UNKNOWN
                var runEnd = runStart
                while (runEnd.plusDays(1) <= to &&
                    (base.byDate[runEnd.plusDays(1)]?.kind ?: CalendarDayKind.UNKNOWN) == kind
                ) runEnd = runEnd.plusDays(1)

                if (kind == CalendarDayKind.HOLIDAY) {
                    // Weekdays the holiday swallowed, ascending. LocalDate is not a
                    // progression, so the range has to be walked by hand.
                    val swallowed = ArrayList<LocalDate>()
                    var cursor = runStart
                    while (!cursor.isAfter(runEnd)) {
                        if (cursor.dayOfWeek.value <= 5) swallowed.add(cursor)
                        cursor = cursor.plusDays(1)
                    }
                    if (swallowed.isNotEmpty()) {
                        result = assignMakeupDays(result, swallowed, runEnd, from, to)
                    }
                }
                runStart = runEnd.plusDays(1)
            }
            return result
        }

        /**
         * Pairs the weekend-workday days surrounding a holiday block with the
         * weekdays it swallowed. Days after the block are taken last-first, days
         * before it first-last, which mirrors the usual pairing.
         */
        private fun assignMakeupDays(
            set: ScheduleAdjustmentSet,
            swallowed: List<LocalDate>,
            holidayEnd: LocalDate,
            termFrom: LocalDate,
            termTo: LocalDate,
        ): ScheduleAdjustmentSet {
            var out = set

            fun isMakeupCandidate(d: LocalDate): Boolean {
                if (d < termFrom || d > termTo) return false
                if (d.dayOfWeek.value <= 5) return false // only weekend makeup days matter
                val adj = set.byDate[d] ?: return false
                if (adj.source == AdjustmentSource.MANUAL) return false
                return adj.kind == CalendarDayKind.WORKDAY
            }

            // After the block, nearest first.
            val after = ArrayList<LocalDate>()
            var d = holidayEnd.plusDays(1)
            while (after.size < swallowed.size && !d.isAfter(termTo)) {
                if (!isMakeupCandidate(d)) break // the run must be contiguous
                after.add(d)
                d = d.plusDays(1)
            }
            after.asReversed().forEachIndexed { i, date ->
                val src = swallowed[swallowed.size - 1 - i]
                out = out.with(
                    DayAdjustment(
                        date = date,
                        kind = CalendarDayKind.WORKDAY,
                        followsWeekday = src.dayOfWeek,
                        source = AdjustmentSource.INFERRED,
                        note = "推测补 ${src.monthValue}/${src.dayOfMonth}（周${src.dayOfWeek.cn()}）的课",
                    ),
                )
            }

            // Before the block, nearest first.
            val before = ArrayList<LocalDate>()
            val startOfBlock = swallowed.first()
            d = startOfBlock.minusDays(1)
            while (before.size < swallowed.size && !d.isBefore(termFrom)) {
                if (!isMakeupCandidate(d)) break
                before.add(d)
                d = d.minusDays(1)
            }
            before.asReversed().forEachIndexed { i, date ->
                val src = swallowed[i]
                out = out.with(
                    DayAdjustment(
                        date = date,
                        kind = CalendarDayKind.WORKDAY,
                        followsWeekday = src.dayOfWeek,
                        source = AdjustmentSource.INFERRED,
                        note = "推测补 ${src.monthValue}/${src.dayOfMonth}（周${src.dayOfWeek.cn()}）的课",
                    ),
                )
            }

            return out
        }

        /**
         * Extracts 调休 rules from the prose of an 教务处 notice, so a student can
         * paste the official text instead of hand-assigning weekdays.
         *
         * Handles the shapes these notices actually use:
         *  - `5月6日（周六）补5月3日（周三）的课`
         *  - `4月23日（星期日）上4月25日（星期二）的课程`
         *  - `5月1日至5月5日放假`
         *  - `5月6日（周六）按周三课表上课`
         *
         * @param year the calendar year the notice's 月/日 refer to.
         */
        fun parseNotice(text: String, year: Int): List<DayAdjustment> {
            if (text.isBlank()) return emptyList()
            val out = LinkedHashMap<LocalDate, DayAdjustment>()
            val flat = text.replace("（", "(").replace("）", ")").replace("　", " ")

            // 1) Explicit source date: "X月Y日...补...A月B日", with an optional
            //    trailing "（周Z）" label on that second date.
            val explicit = Regex(
                "(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日[^。;；\\n]{0,12}?" +
                    "(?:补|上|按照|按)\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日" +
                    "(?:\\s*\\(\\s*(?:周|星期)\\s*([一二三四五六日天])\\s*\\))?",
            )
            for (m in explicit.findAll(flat)) {
                val target = dateOf(year, m.groupValues[1], m.groupValues[2]) ?: continue
                val source = dateOf(year, m.groupValues[3], m.groupValues[4]) ?: continue
                // The notice's own "（周三）" label outranks the weekday derived
                // from the date. Callers usually pass the *current* year while the
                // notice may be for another one, and in that case the computed
                // weekday is simply wrong — trusting it would silently move a
                // 补课 to the wrong day. The label states the intent directly.
                val labelled = dayOfWeekOf(m.groupValues[5])
                val weekday = labelled ?: source.dayOfWeek
                val yearMismatch = labelled != null && labelled != source.dayOfWeek
                out[target] = DayAdjustment(
                    date = target,
                    kind = CalendarDayKind.WORKDAY,
                    followsWeekday = weekday,
                    source = AdjustmentSource.MANUAL,
                    note = if (yearMismatch) {
                        "补周${weekday.cn()}的课（公告写作 ${source.monthValue}/${source.dayOfMonth}，" +
                            "与所给年份不符，已按公告的周${weekday.cn()}处理）"
                    } else {
                        "补 ${source.monthValue}/${source.dayOfMonth}（周${source.dayOfWeek.cn()}）的课"
                    },
                )
            }

            // 2) Weekday-only form: "5月6日（周六）补周三的课".
            //    Only fills dates rule 1 did not already resolve, since rule 1 is
            //    the more specific reading.
            val byWeekday = Regex(
                "(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日[^。;；\\n]{0,12}?" +
                    "(?:补|上|按|按照)\\s*(?:周|星期)\\s*([一二三四五六日天])",
            )
            for (m in byWeekday.findAll(flat)) {
                val target = dateOf(year, m.groupValues[1], m.groupValues[2]) ?: continue
                if (out.containsKey(target)) continue
                val dow = dayOfWeekOf(m.groupValues[3]) ?: continue
                out[target] = DayAdjustment(
                    date = target,
                    kind = CalendarDayKind.WORKDAY,
                    followsWeekday = dow,
                    source = AdjustmentSource.MANUAL,
                    note = "补周${dow.cn()}的课",
                )
            }

            // 3) Holiday spans: "5月1日至5月5日放假"
            val span = Regex(
                "(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*(?:至|到|-|—|~)\\s*" +
                    "(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日[^。;；\\n]{0,6}?(?:放假|假期|休息)",
            )
            for (m in span.findAll(flat)) {
                val a = dateOf(year, m.groupValues[1], m.groupValues[2]) ?: continue
                val b = dateOf(year, m.groupValues[3], m.groupValues[4]) ?: continue
                if (b < a) continue
                var d = a
                while (!d.isAfter(b)) {
                    // A makeup rule for the same date is more specific: keep it.
                    if (out[d] == null) {
                        out[d] = DayAdjustment(
                            date = d,
                            kind = CalendarDayKind.HOLIDAY,
                            source = AdjustmentSource.MANUAL,
                            note = "放假",
                        )
                    }
                    d = d.plusDays(1)
                }
            }

            return out.values.sortedBy { it.date }
        }

        private fun dateOf(year: Int, month: String, day: String): LocalDate? {
            val mo = month.toIntOrNull() ?: return null
            val da = day.toIntOrNull() ?: return null
            return runCatching { LocalDate.of(year, mo, da) }.getOrNull()
        }

        private fun dayOfWeekOf(cn: String): DayOfWeek? = when (cn) {
            "一" -> DayOfWeek.MONDAY
            "二" -> DayOfWeek.TUESDAY
            "三" -> DayOfWeek.WEDNESDAY
            "四" -> DayOfWeek.THURSDAY
            "五" -> DayOfWeek.FRIDAY
            "六" -> DayOfWeek.SATURDAY
            "日", "天" -> DayOfWeek.SUNDAY
            else -> null
        }
    }
}

/** 中文 weekday, e.g. `MONDAY` -> `一`. */
fun DayOfWeek.cn(): String = when (this) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}

/** 中文 weekday label, e.g. `周一`. */
fun DayOfWeek.cnLabel(): String = "周${cn()}"
