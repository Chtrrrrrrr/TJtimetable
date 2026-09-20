package com.ranorac.tjtimetable.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ranorac.tjtimetable.data.prefs.AppSettings
import com.ranorac.tjtimetable.data.prefs.SettingsStore
import com.ranorac.tjtimetable.data.repo.TimetableRepository
import com.ranorac.tjtimetable.domain.ClassOccurrence
import com.ranorac.tjtimetable.domain.Timetable
import com.ranorac.tjtimetable.domain.TimetableResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Everything the timetable screen renders. */
data class TimetableUiState(
    val loading: Boolean = true,
    val timetable: Timetable? = null,
    /** The week currently displayed. */
    val week: Int = 1,
    /** Teaching week containing today, or null during holidays/between terms. */
    val currentWeek: Int? = null,
    /** True when the student is looking at a week other than the current one. */
    val isBrowsingOtherWeek: Boolean = false,
    val settings: AppSettings = AppSettings(),
    /**
     * Today's date, refreshed while the screen is on.
     *
     * Carried in the state rather than read with `LocalDate.now()` inside the composables,
     * because those reads are `remember`ed: the app is designed to be left running, so a
     * student who resumed it the next morning kept looking at yesterday's highlighted
     * column, yesterday's column order under 「从今天开始显示」, and last night's week.
     */
    val today: LocalDate = LocalDate.now(),
    /** One-shot message for a snackbar. */
    val message: String? = null,
) {
    val hasData: Boolean get() = timetable?.isEmpty == false
}

/**
 * How often [TimetableViewModel.today] is re-read.
 *
 * A minute is far more often than the value changes, and that is deliberate: the tick is
 * what makes the roll-over happen *while the screen is being looked at* rather than at
 * whatever moment the next database or settings emission happens to arrive. The flow is
 * cold and only runs while the UI state is subscribed.
 */
private const val TODAY_TICK_MILLIS = 60_000L

/**
 * Drives the timetable screen.
 *
 * The displayed week is held here rather than in the composable so that
 * configuration changes and process death do not lose the student's place, and
 * so that "follow today" can be expressed as `null` rather than a sentinel int.
 *
 * The week's *occurrences* are deliberately NOT part of this state: the screen derives
 * them per page so that, mid-transition, the page sliding out keeps drawing its own week
 * (see `TimetableScreen`).
 */
class TimetableViewModel(
    private val repository: TimetableRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    /** null means "follow today", which is what the student expects on launch. */
    private val pinnedWeek = MutableStateFlow<Int?>(null)
    private val message = MutableStateFlow<String?>(null)

    /**
     * Emits today's date now and then every minute for as long as it is collected.
     *
     * Without a ticking source the whole `uiState` only recomputed when the timetable or
     * the settings changed, so a process that survived midnight kept yesterday's date —
     * and therefore yesterday's highlighted column, column order and "current week".
     */
    private val todayFlow: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(TODAY_TICK_MILLIS)
        }
    }

    val uiState: StateFlow<TimetableUiState> = combine(
        repository.observeTimetable(),
        settingsStore.settings,
        pinnedWeek,
        message,
        todayFlow,
    ) { timetable, settings, pinned, msg, today ->
        if (timetable == null) {
            return@combine TimetableUiState(
                loading = false,
                message = msg,
                settings = settings,
                today = today,
            )
        }
        val term = timetable.term
        val currentWeek = term.weekOf(today)
        val week = (pinned ?: currentWeek ?: 1).coerceIn(1, term.totalWeeks.coerceAtLeast(1))
        TimetableUiState(
            loading = false,
            timetable = timetable,
            week = week,
            currentWeek = currentWeek,
            isBrowsingOtherWeek = pinned != null && pinned != currentWeek,
            settings = settings,
            today = today,
            message = msg,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TimetableUiState(),
    )

    fun selectWeek(week: Int) {
        pinnedWeek.value = week.coerceAtLeast(1)
    }

    fun stepWeek(delta: Int) {
        val max = uiState.value.timetable?.term?.totalWeeks ?: return
        val next = (uiState.value.week + delta).coerceIn(1, max)
        pinnedWeek.value = next
    }

    /** Returns to following today's week. */
    fun jumpToToday() {
        pinnedWeek.value = null
    }

    /**
     * Overrides a course's palette entry. `null` restores the colour derived from
     * the course name.
     */
    fun setCourseColor(courseId: Long, colorIndex: Int?) {
        viewModelScope.launch { repository.setCourseColor(courseId, colorIndex) }
    }

    /**
     * Hides or restores a course.
     *
     * Hiding is offered instead of deleting because a 教务 import would simply
     * bring a deleted course back, so "delete" would look like it silently failed.
     * A hidden course stays in the database and survives the next import.
     */
    fun setCourseHidden(courseId: Long, hidden: Boolean, courseName: String) {
        viewModelScope.launch {
            repository.setCourseHidden(courseId, hidden)
            message.value = if (hidden) "已隐藏「$courseName」，重新导入不会恢复" else "已恢复「$courseName」"
        }
    }

    fun setCourseNote(courseId: Long, note: String?) {
        viewModelScope.launch {
            repository.setCourseNote(courseId, note)
            message.value = "备注已保存"
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    class Factory(
        private val repository: TimetableRepository,
        private val settingsStore: SettingsStore,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return TimetableViewModel(repository, settingsStore) as T
        }
    }
}
