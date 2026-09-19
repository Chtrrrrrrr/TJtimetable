package com.ranorac.tjtimetable.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ranorac.tjtimetable.data.repo.TimetableRepository
import com.ranorac.tjtimetable.domain.AdjustmentSource
import com.ranorac.tjtimetable.domain.DayAdjustment
import com.ranorac.tjtimetable.domain.ScheduleAdjustmentSet
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.cnLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

/** One date in the 调休 editor. */
data class DayRow(
    val date: LocalDate,
    val adjustment: DayAdjustment?,
    /** The weekday's timetable that actually runs, or null when nothing runs. */
    val effectiveWeekday: DayOfWeek?,
) {
    val dayOfWeek: DayOfWeek get() = date.dayOfWeek
    val isTeaching: Boolean get() = effectiveWeekday != null
    val isMakeup: Boolean get() = adjustment?.isMakeup == true
    val source: AdjustmentSource? get() = adjustment?.source

    /** The 星期 label shown on the right of the date. */
    val dayLabel: String get() = dayOfWeek.cnLabel()
}

data class WeekRow(val week: Int, val label: String, val days: List<DayRow>)

data class AdjustmentUiState(
    val loading: Boolean = true,
    val term: TermCalendar? = null,
    val weeks: List<WeekRow> = emptyList(),
    val manualCount: Int = 0,
    val inferredCount: Int = 0,
    val offCount: Int = 0,
    /** True while a refresh/notice is being applied, so the buttons can disable. */
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * Backs the 调休串休 editor.
 *
 * This screen exists because the automatic 调休 resolution is deliberately
 * best-effort: the official "which weekday does a 补课日 follow" rule is only
 * ever published in prose, so [ScheduleAdjustmentSet.inferMakeupDays] guesses and
 * labels its guesses 推测. Without a way to correct them the feature would be
 * unusable whenever the guess is wrong.
 */
class AdjustmentViewModel(
    private val repository: TimetableRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)

    val uiState: StateFlow<AdjustmentUiState> = combine(
        repository.observeAdjustmentOverview(),
        message,
        busy,
    ) { overview, msg, isBusy ->
        if (overview == null) {
            AdjustmentUiState(loading = false, busy = isBusy, message = msg)
        } else {
            val term = overview.term
            val adjustments = overview.adjustments
            val weeks = buildWeeks(term, adjustments)
            AdjustmentUiState(
                loading = false,
                term = term,
                weeks = weeks,
                manualCount = adjustments.all.count { it.source == AdjustmentSource.MANUAL },
                inferredCount = adjustments.all.count { it.source == AdjustmentSource.INFERRED },
                offCount = weeks.sumOf { w -> w.days.count { !it.isTeaching } },
                busy = isBusy,
                message = msg,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdjustmentUiState())

    /**
     * Forces the date to run its own weekday's timetable.
     *
     * `followsWeekday` is set explicitly rather than left null on purpose: on a
     * date the 校历 marks as 节假日, a null [DayAdjustment.followsWeekday] resolves
     * to "no classes", so "正常上课" would silently do nothing. Naming the weekday
     * states the intent and overrides the calendar.
     */
    fun setNormal(date: LocalDate) = write(date) { day ->
        DayAdjustment(
            date = day,
            kind = currentKind(day),
            followsWeekday = day.dayOfWeek,
            noClasses = false,
            source = AdjustmentSource.MANUAL,
            note = "手动设为正常上课",
        )
    }

    /** Forces the date to have no classes at all. */
    fun setNoClasses(date: LocalDate) = write(date) { day ->
        DayAdjustment(
            date = day,
            kind = currentKind(day),
            followsWeekday = null,
            noClasses = true,
            source = AdjustmentSource.MANUAL,
            note = "手动设为停课",
        )
    }

    /** Forces the date to run [weekday]'s timetable — the 补课 case. */
    fun setFollows(date: LocalDate, weekday: DayOfWeek) = write(date) { day ->
        DayAdjustment(
            date = day,
            kind = currentKind(day),
            followsWeekday = weekday,
            noClasses = false,
            source = AdjustmentSource.MANUAL,
            note = "手动设为按周${weekday.cnLabel().removePrefix("周")}课表",
        )
    }

    /**
     * Drops the manual override and re-derives the date from the 校历.
     *
     * Clearing alone is not enough: the manual row was overwriting the derived
     * one, and [TimetableRepository.refreshAdjustments] skips any date that still
     * has a manual row — so the refresh has to run after the delete.
     */
    fun resetToAuto(date: LocalDate) {
        val term = uiState.value.term ?: return
        viewModelScope.launch {
            busy.value = true
            repository.clearAdjustment(term.calendarId, date)
            repository.refreshAdjustments(term)
            busy.value = false
            message.value = "已恢复为校历自动判断"
        }
    }

    fun refreshFromSchoolCalendar() {
        val term = uiState.value.term ?: return
        viewModelScope.launch {
            busy.value = true
            val count = repository.refreshAdjustments(term)
            busy.value = false
            message.value =
                if (count > 0) "已从校历更新（$count 天有特殊安排）" else "校历暂无可更新数据"
        }
    }

    /**
     * Applies 调休 rules parsed out of a pasted 教务处 notice.
     *
     * Lives here rather than in settings because the rows it rewrites are on this
     * screen — the student can paste the notice and immediately see which days moved.
     */
    fun applyNotice(text: String, year: Int = LocalDate.now().year) {
        val term = uiState.value.term ?: return
        viewModelScope.launch {
            busy.value = true
            val count = repository.applyNotice(term.calendarId, text, year)
            busy.value = false
            message.value =
                if (count > 0) "已根据通知设置 $count 天调休安排" else "未能从文本中识别出调休安排"
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun write(date: LocalDate, build: (LocalDate) -> DayAdjustment) {
        val term = uiState.value.term ?: return
        viewModelScope.launch {
            repository.putAdjustment(term.calendarId, build(date))
            message.value = "已更新 ${date.monthValue}/${date.dayOfMonth}"
        }
    }

    /** Keeps the 校历's own classification so the row still shows why it was special. */
    private fun currentKind(date: LocalDate): com.ranorac.tjtimetable.domain.CalendarDayKind =
        uiState.value.weeks
            .asSequence()
            .flatMap { it.days.asSequence() }
            .firstOrNull { it.date == date }
            ?.adjustment
            ?.kind
            ?: com.ranorac.tjtimetable.domain.CalendarDayKind.UNKNOWN

    private fun buildWeeks(term: TermCalendar, adjustments: ScheduleAdjustmentSet): List<WeekRow> =
        (1..term.totalWeeks).map { week ->
            val days = (1..7).map { dayIndex ->
                val dayOfWeek = DayOfWeek.of(dayIndex)
                val date = term.dateOf(week, dayOfWeek)
                DayRow(
                    date = date,
                    adjustment = adjustments.get(date),
                    effectiveWeekday = adjustments.effectiveWeekday(date),
                )
            }
            WeekRow(week = week, label = term.weekLabel(week), days = days)
        }

    class Factory(private val repository: TimetableRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AdjustmentViewModel(repository) as T
        }
    }
}
