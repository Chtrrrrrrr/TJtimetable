package com.ranorac.tjtimetable.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ranorac.tjtimetable.MainActivity
import com.ranorac.tjtimetable.R
import com.ranorac.tjtimetable.TjApplication
import com.ranorac.tjtimetable.domain.ClassOccurrence
import com.ranorac.tjtimetable.domain.Timetable
import com.ranorac.tjtimetable.domain.TimetableResolver
import com.ranorac.tjtimetable.domain.cn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * 今日课程小组件。
 *
 * 设计取舍：
 *
 *  - **数据流**：`provideGlance` 里先读一次数据库，把结果写成小组件自己的
 *    preferences（`WidgetPayload` 的 JSON），然后 `provideContent` 只从 preferences 渲染。
 *    这样重画不需要重新查库，杀进程/重建 RemoteViews 也能画出上一次的内容；
 *    数据变化通过 `updateAppWidgetState` 写回，Glance 会观察到 state 变化并重画。
 *  - **不解析调休**：今天有哪些课一律走 [TimetableResolver.occurrencesForDate]，
 *    节假日、调休补课日、单双周都由它负责，小组件不重复实现这套规则。
 *  - **不用 weight**：Glance 1.1 的 Row/Column 没有 weight，且 `fillMaxWidth`
 *    在行内是 `match_parent` 语义——它会吃掉剩余宽度并把后面的兄弟挤成 0 宽。
 *    所以每一个 Row 里 `fillMaxWidth` 只出现在最后一个子项上。
 *  - **不滚动**：一律按可用高度决定画几行，多出来的用「还有 N 门课」提示。
 */
class TimetableWidget : GlanceAppWidget() {

    /**
     * 两种密度：约 2x2 与约 4x2。Glance 会把 [LocalSize] 设成其中一个，
     * 内容据此决定行数和信息量；API 31 以下由系统给出的尺寸回退，最坏情况
     * 只是行数偏少，不会溢出。
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(WidgetSizes)

    /** 与 `updateAppWidgetState(context, glanceId) { ... }` 配套，显式声明。 */
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appContext = context.applicationContext
        try {
            // 先读库再渲染，避免有课表的同学第一次看到空状态。
            // buildPayload 返回 null 表示这次读库失败：不写 state，保留上一次的内容，
            // 否则一次瞬时故障就会把小组件覆盖成「还没有课表」，最多挂 30 分钟。
            buildPayload(appContext)?.let { writeWidgetState(appContext, id, it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // 渲染必须继续：preferences 里可能还有上一次的内容，读库失败不该让
            // 小组件变成错误占位。
            Log.w(TAG, "读取课表失败，沿用上一次的小组件内容", error)
        }
        provideContent { WidgetContent() }
    }

    companion object {

        /**
         * 重新读库并重画所有已添加的小组件。
         *
         * [android.appwidget.AppWidgetProvider.onUpdate] 的 30 分钟滴答、以及
         * 应用内导入/编辑课程之后都应该调它。
         */
        fun refresh(context: Context) {
            val appContext = context.applicationContext
            RefreshScope.launch {
                try {
                    refreshAll(appContext)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    Log.w(TAG, "刷新小组件失败", error)
                }
            }
        }

        /** 读库 + 写 state 之外，再显式 `updateAll`，保证一定会重画。 */
        suspend fun updateAllNow(context: Context) {
            val appContext = context.applicationContext
            refreshAll(appContext)
            TimetableWidget().updateAll(appContext)
        }

        /**
         * Arms a one-shot update for the next moment the widget's content changes by itself.
         *
         * Called from the launcher tick and from every in-app change, so the item is always
         * aimed at the current timetable; [ExistingWorkPolicy.REPLACE] is what keeps exactly one
         * pending item instead of a growing queue.
         */
        fun scheduleNextBoundary(context: Context) {
            val appContext = context.applicationContext
            RefreshScope.launch {
                try {
                    val application = appContext as? TjApplication ?: return@launch
                    val timetable = application.container.repository.observeTimetable().first()
                        ?: return@launch
                    val today = LocalDate.now()
                    val now = LocalTime.now()
                    val boundary = nextBoundary(
                        TimetableResolver.occurrencesForDate(timetable, today),
                        now,
                    ) ?: run {
                        // Nothing left today; cancel so a stale item cannot fire at midnight.
                        WorkManager.getInstance(appContext).cancelUniqueWork(BOUNDARY_WORK)
                        return@launch
                    }
                    val delay = Duration.between(now, boundary).toMillis().coerceAtLeast(0L)
                    WorkManager.getInstance(appContext).enqueueUniqueWork(
                        BOUNDARY_WORK,
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                            .build(),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    Log.w(TAG, "武装小组件边界刷新失败", error)
                }
            }
        }

        private suspend fun refreshAll(context: Context) {
            // null = 读库失败，保留每个小组件已有的 state（并沿用其内容重画）。
            val payload = buildPayload(context)
            val widget = TimetableWidget()
            val ids = GlanceAppWidgetManager(context).getGlanceIds(TimetableWidget::class.java)
            for (id in ids) {
                if (payload != null) writeWidgetState(context, id, payload)
                widget.update(context, id)
            }
        }
    }
}

/**
 * One-shot work that re-renders the widget the moment its contents change by themselves.
 *
 * WorkManager has no cron and `updatePeriodMillis` cannot go below 30 minutes, so the widget
 * arms a single delayed item for the next class boundary and each run arms the following one —
 * the same shape as the class reminders, for the same reason. It does not depend on the reminder
 * setting, because the widget being correct is not conditional on reminders being enabled.
 */
class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        TimetableWidget.refresh(applicationContext)
        TimetableWidget.scheduleNextBoundary(applicationContext)
        return Result.success()
    }
}

/**
 * Fired by the launcher's 30-minute tick and by every in-app change.
 *
 * The base class re-renders the stored state, which is not enough on its own: "still to come" and
 * "next class" both move with the clock, so the payload is rebuilt and the next boundary armed.
 */
class TimetableWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = TimetableWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        TimetableWidget.refresh(context)
        TimetableWidget.scheduleNextBoundary(context)
    }
}

/**
 * 点刷新图标时重新读库。
 *
 * Glance 通过类名反射实例化它，所以必须是 public 且有无参构造；Glance 的
 * consumer proguard 规则（`-keep public class * extends ...ActionCallback`）
 * 会保证它在 release 混淆后依然存在。
 */
class TimetableRefreshAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val appContext = context.applicationContext
        try {
            // 同 provideGlance：读库失败时不动已存的内容。
            buildPayload(appContext)?.let {
                writeWidgetState(appContext, glanceId, it)
                TimetableWidget().update(appContext, glanceId)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "手动刷新小组件失败", error)
        }
    }
}

// --------------------------------------------------------------------- 状态

private const val TAG = "TimetableWidget"

/** 一天最多带几行到小组件里，多出来的用数量提示。 */
private const val MAX_PAYLOAD_ROWS = 8

/** Unique WorkManager name for the next class-boundary re-render. */
private const val BOUNDARY_WORK = "tj_widget_boundary"

/**
 * The sizes the widget will render at.
 *
 * `SizeMode.Responsive` picks the **closest** declared size that fits the space the launcher
 * gave it, so declaring more of them is what lets the student use a 5x2 or a 2x3 as well as
 * the default 2x2. Roughly one launcher cell is 70dp, hence the widths.
 *
 * The layout itself is density-driven rather than per-size: row count comes from the measured
 * height and the detailed layout from the measured width, so these entries only need to cover
 * the range, not each hand-design a variant.
 */
private val WidgetSizes = setOf(
    // 2x1 / 4x1 / 5x1 — one line of information
    DpSize(width = 110.dp, height = 40.dp),
    DpSize(width = 250.dp, height = 40.dp),
    DpSize(width = 320.dp, height = 40.dp),
    // 2x2 / 4x2 / 5x2 — the common cases
    DpSize(width = 110.dp, height = 110.dp),
    DpSize(width = 250.dp, height = 110.dp),
    DpSize(width = 320.dp, height = 110.dp),
    // 2x3 / 4x3 / 5x3 — room for more of the day
    DpSize(width = 110.dp, height = 180.dp),
    DpSize(width = 250.dp, height = 180.dp),
    DpSize(width = 320.dp, height = 180.dp),
    // 4x4 / 5x4 — a full day at a glance
    DpSize(width = 250.dp, height = 250.dp),
    DpSize(width = 320.dp, height = 250.dp),
)

/** 每个小组件自己的持久化状态；[KEY_REVISION] 用来强制重画。 */
private val KEY_PAYLOAD = stringPreferencesKey("widget_payload")
private val KEY_REVISION = intPreferencesKey("widget_revision")

private val WIDGET_JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 小组件要画的全部内容。
 *
 * 存字符串而不是直接存 `Timetable`：`Timetable` 不是 Parcelable/Serializable，
 * 而 preferences 只能放基本类型；渲染需要的也只是一天的若干行文字。
 */
@Serializable
internal data class WidgetPayload(
    val state: String,
    /** 例如 `第 5 周`，学期外为 null。 */
    val weekLabel: String? = null,
    /** 例如 `周二`。 */
    val dayLabel: String = "",
    /** 例如 `3 门课` / `无课`。 */
    val countLabel: String? = null,
    /** 空状态主文案。 */
    val headline: String? = null,
    /** 空状态副文案 / 下一节课提示。 */
    val subline: String? = null,
    /** 今天课都上完之后，下一节课（可能是别的日子）的提示。 */
    val footer: String? = null,
    val rows: List<WidgetRow> = emptyList(),
    /** 今天总共有几门课，用于「还有 N 门课」。 */
    val total: Int = 0,
    /** 生成时的日期，用于识别过期数据（睡眠/关机导致滴答没跑）。 */
    val epochDay: Long = 0L,
) {
    companion object {
        const val STATE_LOADING = ""
        const val STATE_CLASSES = "classes"
        const val STATE_NO_CLASS = "no_class"
        const val STATE_NO_TIMETABLE = "no_timetable"

        /** 首次渲染、数据还没写进来时的占位。 */
        fun loading(): WidgetPayload = WidgetPayload(state = STATE_LOADING)
    }
}

/** 小组件里的一行课。时间已经格式化成文字，避免渲染时再算时区。 */
@Serializable
internal data class WidgetRow(
    val name: String,
    /** `08:00–09:35`，作息表缺该节时退化为 `5 节`。 */
    val time: String,
    /** `08:00`，用于「下一节」。 */
    val start: String,
    val room: String? = null,
    val teacher: String? = null,
    /** 单周 / 双周，非单双周为 null。 */
    val parity: String? = null,
    val makeup: Boolean = false,
    val colorIndex: Int = 0,
    /** 当天零点起的分钟数，-1 表示作息表没有这一节。 */
    val startMinute: Int = -1,
    val endMinute: Int = -1,
)

// ------------------------------------------------------------------- 颜色

/**
 * Primer token，取值与 `ui/theme/Color.kt` 一致。
 *
 * 这里重新声明而不是 import：那边同时带着 Compose runtime 的
 * `CompositionLocal` 机制，小组件不需要；两处改动必须同步。
 */
private object Primer {
    /** canvas.default */
    val canvas = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF0D1117))

    /** canvas.subtle */
    val canvasSubtle = ColorProvider(day = Color(0xFFF6F8FA), night = Color(0xFF161B22))

    /** fg.default */
    val fg = ColorProvider(day = Color(0xFF1F2328), night = Color(0xFFE6EDF3))

    /** fg.muted */
    val muted = ColorProvider(day = Color(0xFF656D76), night = Color(0xFF8B949E))

    /** accent.fg */
    val accent = ColorProvider(day = Color(0xFF0969DA), night = Color(0xFF2F81F7))
}

/** 补课标记：attention 系（浅色 attentionSubtle / 深色用 CourseHues[3] 的容器色）。 */
private val AttentionChipBackground =
    ColorProvider(day = Color(0xFFFFF1E0), night = Color(0xFF3B2A11))
private val AttentionChipForeground =
    ColorProvider(day = Color(0xFF9A6700), night = Color(0xFFFFC98A))

/** 进行中标记：accent 系（浅色 accentSubtle / 深色用 CourseHues[0] 的容器色）。 */
private val AccentChipBackground =
    ColorProvider(day = Color(0xFFDDF4FF), night = Color(0xFF12304F))

/**
 * 课程色条：`ui/theme/Color.kt` 里十个 [com.ranorac.tjtimetable.ui.theme.CourseHue] 的
 * 浓缩版——浅色取 on-container（深色饱和），深色取 dark on-container（浅色明亮），
 * 这两种都是「在白/黑画布上像一道彩色标签」的那一档。
 *
 * [com.ranorac.tjtimetable.domain.Course.effectiveColorIndex] 按 0..9 索引本表，
 * 所以顺序不能改，且必须和 `CourseHues` 保持同步。
 */
private val CourseStripes = listOf(
    ColorProvider(day = Color(0xFF0A3069), night = Color(0xFFA5D6FF)), // blue
    ColorProvider(day = Color(0xFF0F5323), night = Color(0xFF9EE9B0)), // green
    ColorProvider(day = Color(0xFF3E1F79), night = Color(0xFFD8B9FF)), // purple
    ColorProvider(day = Color(0xFF7A3E00), night = Color(0xFFFFC98A)), // orange
    ColorProvider(day = Color(0xFF82071E), night = Color(0xFFFFB3AD)), // red
    ColorProvider(day = Color(0xFF0B4F48), night = Color(0xFF8DE5DA)), // teal
    ColorProvider(day = Color(0xFF5C4400), night = Color(0xFFF2DC8A)), // yellow
    ColorProvider(day = Color(0xFF7D1F52), night = Color(0xFFFFB0D6)), // pink
    ColorProvider(day = Color(0xFF22307A), night = Color(0xFFB4C2FF)), // indigo
    ColorProvider(day = Color(0xFF3F5205), night = Color(0xFFCBE79A)), // olive
)

private fun stripeFor(colorIndex: Int) =
    CourseStripes[colorIndex.coerceIn(0, CourseStripes.lastIndex)]

private enum class ChipTone { NEUTRAL, ATTENTION, ACCENT }

/** 进程内共享的刷新作用域；小组件刷新很短，不需要每个都新建。 */
private val RefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private val CardRadius = 18.dp

// --------------------------------------------------------------- 读取与计算

/** 读当前课表的结果，区分「没有课表」与「读失败」——两者要画的东西完全不同。 */
private sealed interface TimetableRead {
    /** 读到了（可能是一个空学期）。 */
    data class Ok(val timetable: Timetable) : TimetableRead

    /** 确实没有当前学期：学生还没导入。 */
    data object NoTimetable : TimetableRead

    /** 读库本身失败，例如数据库打不开；调用方必须沿用上一次的内容。 */
    data class Failed(val error: Throwable) : TimetableRead
}

/**
 * 读当前课表。
 *
 * 三种结局必须分开：早期版本把「读失败」也返回 null，于是 [buildPayload] 会把它
 * 写成「未导入课表」空状态，把上一次画好的内容覆盖掉——正是下面注释里说要避免的事，
 * 而 `provideGlance` 的 try/catch 永远不会触发，因为没有异常抛出。
 */
private suspend fun loadTimetable(context: Context): TimetableRead {
    val application = context.applicationContext as? TjApplication
        ?: return TimetableRead.Failed(IllegalStateException("application is not TjApplication"))
    return try {
        when (val timetable = application.container.repository.observeTimetable().first()) {
            null -> TimetableRead.NoTimetable
            else -> TimetableRead.Ok(timetable)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Log.w(TAG, "读取课表失败", error)
        TimetableRead.Failed(error)
    }
}

/**
 * 组装要写进小组件 preferences 的内容。
 *
 * 读库失败时返回 null，调用方跳过写入：宁可继续显示上一次的快照，也不要让小组件
 * 因为一次瞬时故障变成「还没有课表」——`updatePeriodMillis` 的最小值是 30 分钟，
 * 那个错误状态会挂满整个间隔。
 */
private suspend fun buildPayload(context: Context): WidgetPayload? {
    val timetable = when (val read = loadTimetable(context)) {
        is TimetableRead.Failed -> return null
        TimetableRead.NoTimetable -> return notImportedPayload(context)
        is TimetableRead.Ok -> read.timetable
    }
    // 学期存在但一门课都没有：大多是导入没跑完，或者选的学期是空的。
    if (timetable.sessions.isEmpty()) return emptyTermPayload(context)

    val today = LocalDate.now()
    val now = LocalTime.now()
    val allToday = TimetableResolver.occurrencesForDate(timetable, today)
    val week = timetable.term.weekOf(today)

    // Only what is still relevant: the class in progress and everything after it. A widget is
    // glanced at, not read — three lines spent on lectures that already finished push the one
    // thing the student opened it for off the bottom, and by the afternoon the widget was
    // entirely historical. A class counts as finished once it has ENDED, so it stays visible
    // for its whole duration (and shifts up to "进行中" rather than vanishing at its start).
    val occurrences = upcomingOnly(allToday, now)
    val rows = occurrences.take(MAX_PAYLOAD_ROWS).map { it.toWidgetRow() }

    if (rows.isEmpty()) {
        // Two situations that used to be reported identically, and one of them was simply wrong:
        //
        //  - classes WERE scheduled today and every one of them has finished → 今天的课上完了;
        //  - there was nothing scheduled today at all → 今天没有课.
        //
        // Saying 今天没有课 while `countLabel` said 无课, to a student who has just walked out of
        // a lecture, reads as the widget having lost the timetable. `allToday` is the unfiltered
        // list, so whether it is empty is exactly the distinction.
        return idlePayload(context, timetable, today, now, week, allToday)
    }

    // When classes remain today they are already on screen, and the renderer names the running or
    // next one from the rows themselves — a footer would only repeat it. So the footer is left
    // for the case the rows cannot cover: nothing left today, where the next class is on another
    // day and would otherwise be invisible. `null` here means "the rows say it".
    val nextToday = occurrences.firstOrNull { occurrence ->
        val start = occurrence.startTime
        start != null && LocalDateTime.of(occurrence.date, start).isAfter(LocalDateTime.of(today, now))
    }
    val nextAfterToday = if (nextToday != null) {
        null
    } else {
        TimetableResolver.nextOccurrence(
            timetable = timetable,
            from = today.plusDays(1),
            now = LocalTime.MIDNIGHT,
        )
    }

    return WidgetPayload(
        state = WidgetPayload.STATE_CLASSES,
        weekLabel = week?.let { context.getString(R.string.widget_week, it) },
        dayLabel = "周${today.dayOfWeek.cn()}",
        countLabel = context.getString(R.string.widget_count_courses, occurrences.size),
        footer = nextAfterToday?.let {
            context.getString(R.string.widget_next_later, describeNext(it, today))
        },
        rows = rows,
        total = occurrences.size,
        epochDay = today.toEpochDay(),
    )
}

/**
 * Drops the classes that have already finished, keeping the one in progress and everything after.
 *
 * A class with no known clock time cannot be ordered against `now`, and hiding it would be a
 * silent omission — the failure mode this app avoids everywhere else — so it is kept.
 *
 * Kept `internal` and pure so the rule is pinned by a plain JVM test rather than by reading the
 * widget on a phone.
 */
internal fun upcomingOnly(
    occurrences: List<ClassOccurrence>,
    now: LocalTime,
): List<ClassOccurrence> = occurrences.filter { occurrence ->
    val end = occurrence.endTime ?: return@filter true
    !end.isBefore(now)
}

/**
 * The next moment the widget's content changes on its own.
 *
 * Filtering finished classes makes the widget **time-critical**: left to the 30-minute
 * `updatePeriodMillis` tick, a lecture that ended at 09:35 would still be listed at 10:05, which
 * is the very thing the filter exists to prevent. The two moments that matter are a class ending
 * (it must disappear) and the next one starting (it becomes 进行中), so this returns whichever
 * comes first — and the widget arms a one-shot update for exactly then.
 *
 * Bounded to today: past the last class there is nothing to tick for, and a term-length horizon
 * would keep a work item alive for months. The daily tick and every in-app change re-arm it.
 *
 * Pure and `internal` so the arithmetic is unit-tested; `null` means "nothing left to wait for".
 */
internal fun nextBoundary(
    occurrences: List<ClassOccurrence>,
    now: LocalTime,
): LocalTime? = occurrences
    .flatMap { listOfNotNull(it.startTime, it.endTime) }
    // A boundary must still be ahead, or arming it would spin.
    .filter { it.isAfter(now) }
    .minOrNull()

private fun notImportedPayload(context: Context) = WidgetPayload(
    state = WidgetPayload.STATE_NO_TIMETABLE,
    headline = context.getString(R.string.widget_state_not_imported),
    subline = context.getString(R.string.widget_state_not_imported_hint),
    epochDay = LocalDate.now().toEpochDay(),
)

private fun emptyTermPayload(context: Context) = WidgetPayload(
    state = WidgetPayload.STATE_NO_TIMETABLE,
    dayLabel = "周${LocalDate.now().dayOfWeek.cn()}",
    headline = context.getString(R.string.widget_state_no_courses),
    subline = context.getString(R.string.widget_state_no_courses_hint),
    epochDay = LocalDate.now().toEpochDay(),
)

/**
 * 今天没有可上的课。
 *
 * 两种情形必须分开写，这是明确要求的：
 *  - [todayClasses] 非空 → 今天有课、但都上完了：「今天的课上完了」，数量写「已上完 N 门」；
 *  - [todayClasses] 为空 → 今天本来就没课（假期/周末/停课/学期外）：「今天没有课」，数量写「无课」。
 *
 * 把第一种写成「今天没有课 / 无课」会让学生以为课程表丢了——他刚下课，明明上过课。
 *
 * 无论哪种情况都尽量把「下一节课」说出来——这是空状态下最有用的信息。
 */
private fun idlePayload(
    context: Context,
    timetable: Timetable,
    today: LocalDate,
    now: LocalTime,
    week: Int?,
    /** 今天**未经过滤**的全部课程；用来区分「上完了」与「本来就没课」。 */
    todayClasses: List<ClassOccurrence>,
): WidgetPayload {
    val term = timetable.term
    val lastTeachingDay = term.weekEnd(term.totalWeeks)
    val notStarted = today.isBefore(term.firstWeekStart)

    val state = idleState(today, term, week, todayClasses)

    // The next class must still be in the FUTURE: `nextOccurrence` takes `now` and skips a class
    // whose start has passed, so a finished morning does not make this point backwards.
    val next = TimetableResolver.nextOccurrence(timetable, today, now)

    return WidgetPayload(
        state = WidgetPayload.STATE_NO_CLASS,
        weekLabel = week?.let { context.getString(R.string.widget_week, it) },
        dayLabel = "周${today.dayOfWeek.cn()}",
        // 「已上完 N 门」 rather than 「无课」: the day was not empty.
        countLabel = when (state) {
            IdleState.CLASSES_DONE -> context.getString(R.string.widget_count_done, todayClasses.size)
            else -> context.getString(R.string.widget_count_none)
        },
        headline = context.getString(
            when (state) {
                IdleState.NOT_STARTED -> R.string.widget_state_not_started
                IdleState.TERM_OVER -> R.string.widget_state_term_over
                IdleState.VACATION -> R.string.widget_state_vacation
                IdleState.CLASSES_DONE -> R.string.widget_state_classes_done
                IdleState.NO_CLASS -> R.string.widget_state_no_class
            },
        ),
        subline = when {
            next != null -> context.getString(R.string.widget_next_later, describeNext(next, today))
            notStarted -> context.getString(
                R.string.widget_days_to_start,
                ChronoUnit.DAYS.between(today, term.firstWeekStart).coerceAtLeast(0),
            )
            else -> context.getString(R.string.widget_no_upcoming)
        },
        rows = emptyList(),
        total = todayClasses.size,
        epochDay = today.toEpochDay(),
    )
}

/**
 * Why the widget has nothing to show today.
 *
 * Split out as a value so the distinction between [CLASSES_DONE] and [NO_CLASS] is testable
 * rather than buried in a `when` inside a composable — the previous version conflated them, and
 * telling a student who has just finished a lecture that 今天没有课 (with a 无课 count) reads as
 * the timetable having been lost.
 */
internal enum class IdleState { NOT_STARTED, TERM_OVER, VACATION, CLASSES_DONE, NO_CLASS }

/**
 * Decides which empty state applies.
 *
 * [CLASSES_DONE] requires three things: classes really were scheduled today, today is inside the
 * teaching term, and today belongs to a teaching week. Outside those, the classes in
 * [todayClasses] are not today's — so it stays [NO_CLASS].
 */
internal fun idleState(
    today: LocalDate,
    term: com.ranorac.tjtimetable.domain.TermCalendar,
    week: Int?,
    todayClasses: List<ClassOccurrence>,
): IdleState = when {
    today.isBefore(term.firstWeekStart) -> IdleState.NOT_STARTED
    today.isAfter(term.weekEnd(term.totalWeeks)) -> IdleState.TERM_OVER
    week == null -> IdleState.VACATION
    todayClasses.isNotEmpty() -> IdleState.CLASSES_DONE
    else -> IdleState.NO_CLASS
}

private fun ClassOccurrence.toWidgetRow(): WidgetRow = WidgetRow(
    name = course.name,
    time = timeLabel ?: unitLabel,
    start = startTime?.hhmm() ?: unitLabel,
    room = (room ?: session.building)?.takeIf { it.isNotBlank() },
    teacher = (session.teacher ?: course.teacher)?.takeIf { it.isNotBlank() },
    parity = session.weeks.parityLabel,
    makeup = isMakeup,
    colorIndex = course.effectiveColorIndex,
    startMinute = startTime?.let { it.hour * 60 + it.minute } ?: -1,
    endMinute = endTime?.let { it.hour * 60 + it.minute } ?: -1,
)

/** 例如 `明天 08:00 · 高等数学 A201`。 */
private fun describeNext(occurrence: ClassOccurrence, today: LocalDate): String {
    val day = when (occurrence.date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.plusDays(2) -> "后天"
        else -> "${occurrence.date.monthValue}月${occurrence.date.dayOfMonth}日" +
            " 周${occurrence.date.dayOfWeek.cn()}"
    }
    val time = occurrence.startTime?.hhmm() ?: occurrence.unitLabel
    val room = occurrence.room?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    return "$day $time · ${occurrence.course.name}$room"
}

private fun LocalTime.hhmm(): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

private suspend fun writeWidgetState(context: Context, glanceId: GlanceId, payload: WidgetPayload) {
    val json = WIDGET_JSON.encodeToString(WidgetPayload.serializer(), payload)
    updateAppWidgetState(context, glanceId) { preferences ->
        preferences[KEY_PAYLOAD] = json
        // 即使 payload 一字未改也要把 revision 加一：进行中/下一节这些标记依赖
        // 当前时间，周期性的更新必须强制重画一次。
        preferences[KEY_REVISION] = (preferences[KEY_REVISION] ?: 0) + 1
    }
}

private fun decodePayload(raw: String?): WidgetPayload? {
    if (raw.isNullOrBlank()) return null
    return try {
        WIDGET_JSON.decodeFromString(WidgetPayload.serializer(), raw)
    } catch (error: Throwable) {
        Log.w(TAG, "小组件缓存损坏，按未导入处理", error)
        null
    }
}

// ------------------------------------------------------------------- 渲染

@Composable
private fun WidgetContent() {
    val context = LocalContext.current
    val preferences = currentState<Preferences>()

    // 读一下 revision：这既把本次 composition 挂到小组件的 state 上（写入即重画），
    // 也是「有人写过数据」的凭据。
    @Suppress("UNUSED_VARIABLE")
    val revision = preferences[KEY_REVISION] ?: 0
    val payload = decodePayload(preferences[KEY_PAYLOAD]) ?: WidgetPayload.loading()

    val widgetSize = LocalSize.current
    val widthDp = widgetSize.width.value
    val heightDp = widgetSize.height.value
    val measured = !widthDp.isNaN() && !heightDp.isNaN()
    val wide = measured && widthDp >= 200f
    // 一行课大约 32dp，头部/尾部/内边距大约 40dp；尺寸未知时按 3 行保守处理。
    // 上限跟随载体行数，这样 4x4 / 5x4 这类高尺寸能真的用满高度，而不是被 6 行卡住。
    val rowBudget = if (measured) {
        ((heightDp - 40f) / 32f).toInt().coerceIn(1, MAX_PAYLOAD_ROWS)
    } else {
        3
    }

    val minuteNow = LocalTime.now().let { it.hour * 60 + it.minute }
    val stale = payload.epochDay != 0L && payload.epochDay != LocalDate.now().toEpochDay()

    val visible = payload.rows.take(rowBudget)
    val hidden = (payload.total - visible.size).coerceAtLeast(0)
    val runningIndex = visible.indexOfFirst {
        it.startMinute >= 0 && it.startMinute <= minuteNow && minuteNow < it.endMinute
    }
    val nextIndex = visible.indexOfFirst { it.startMinute >= 0 && it.startMinute > minuteNow }

    val title = if (payload.state == WidgetPayload.STATE_NO_TIMETABLE) {
        context.getString(R.string.app_name)
    } else {
        buildList {
            if (wide) payload.weekLabel?.let { add(it) }
            if (payload.dayLabel.isNotEmpty()) add(payload.dayLabel)
            payload.countLabel?.let { add(it) }
        }.joinToString(" · ").ifEmpty { context.getString(R.string.app_name) }
    }

    val footer = when {
        payload.state != WidgetPayload.STATE_CLASSES -> null
        runningIndex >= 0 -> context.getString(
            R.string.widget_running_remaining,
            visible[runningIndex].endMinute - minuteNow,
        )
        nextIndex >= 0 -> context.getString(
            R.string.widget_next_today,
            "${visible[nextIndex].start} · ${visible[nextIndex].name}",
        )
        else -> payload.footer
    }
    val footerText = if (footer == null) {
        null
    } else if (hidden > 0) {
        context.getString(R.string.widget_overflow, hidden) + " · " + footer
    } else {
        footer
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            // 圆角背景直接用「圆角 + 1dp 描边」的 drawable，而不是先铺一层方形颜色、再盖一张
            // 圆角图。那层方形颜色平时看不出来，只因为启动器替小组件裁了圆角；长按进入移动/编辑
            // 模式时启动器不再裁剪，方色就从四个圆角外面漏出来变成直角——也就是看到的那个 bug。
            // drawable 自带夜间版本（drawable-night/），比 ColorProvider 更省事，也不再需要
            // 一张“兜底”的方色。
            .background(ImageProvider(R.drawable.widget_background))
            .cornerRadius(CardRadius)
            // 整块可点：打开应用（查看完整课表 / 导入）。
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Header(
                title = title,
                stale = stale,
                refreshLabel = context.getString(R.string.widget_refresh_action),
            )

            Spacer(modifier = GlanceModifier.height(4.dp))

            if (visible.isEmpty()) {
                Text(
                    text = payload.headline ?: context.getString(R.string.widget_state_loading),
                    style = TextStyle(
                        color = Primer.fg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 2,
                )
                payload.subline?.let { subline ->
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Text(
                        text = subline,
                        style = TextStyle(color = Primer.muted, fontSize = 9.sp),
                        maxLines = 2,
                    )
                }
            } else {
                visible.forEachIndexed { index, row ->
                    if (index > 0) Spacer(modifier = GlanceModifier.height(3.dp))
                    ClassRow(
                        row = row,
                        running = index == runningIndex,
                        next = index == nextIndex,
                        wide = wide,
                    )
                }
            }

            if (footerText != null) {
                Spacer(modifier = GlanceModifier.height(3.dp))
                Text(
                    text = footerText,
                    style = TextStyle(
                        color = if (runningIndex >= 0) Primer.accent else Primer.muted,
                        fontSize = 9.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 标题栏。
 *
 * 刷新图标放在行首、标题用 `fillMaxWidth` 收尾：Glance 的 Row 在 AppWidget 侧是
 * 横向 LinearLayout，`fillMaxWidth` 会让该子项先吃掉剩余宽度，排在它后面的兄弟
 * 会被压成 0 宽。
 */
@Composable
private fun Header(title: String, stale: Boolean, refreshLabel: String) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_refresh),
            contentDescription = refreshLabel,
            modifier = GlanceModifier
                .size(14.dp)
                .clickable(actionRunCallback<TimetableRefreshAction>()),
            // 数据不是今天的就点亮图标，提示「值得点一下」。
            colorFilter = ColorFilter.tint(if (stale) Primer.accent else Primer.muted),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = title,
            modifier = GlanceModifier.fillMaxWidth(),
            style = TextStyle(
                color = Primer.fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun ClassRow(row: WidgetRow, running: Boolean, next: Boolean, wide: Boolean) {
    val context = LocalContext.current

    // 窄的时候只留一枚标记，剩下的事实（单双周）并进副标题文字里。
    val chipLimit = if (wide) 2 else 1
    val chips = ArrayList<Pair<String, ChipTone>>(2).apply {
        if (running) add(context.getString(R.string.widget_chip_running) to ChipTone.ACCENT)
        if (row.makeup) add(context.getString(R.string.widget_chip_makeup) to ChipTone.ATTENTION)
        if (row.parity != null && size < chipLimit) add(row.parity to ChipTone.NEUTRAL)
    }.take(chipLimit)

    val parityAsChip = chips.any { it.second == ChipTone.NEUTRAL }
    val meta = buildString {
        val parity = row.parity
        if (parity != null && !parityAsChip) append(parity).append(" · ")
        append(row.time)
        row.room?.let { append(" · ").append(it) }
        if (wide) row.teacher?.let { append(" · ").append(it) }
    }

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .width(3.dp)
                .height(26.dp)
                .background(stripeFor(row.colorIndex))
                .cornerRadius(1.5.dp),
        ) {}
        Spacer(modifier = GlanceModifier.width(7.dp))

        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = row.name,
                style = TextStyle(
                    color = if (running) Primer.accent else Primer.fg,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.height(1.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                chips.forEach { (label, tone) ->
                    Chip(label = label, tone = tone)
                    Spacer(modifier = GlanceModifier.width(4.dp))
                }
                Text(
                    text = meta,
                    modifier = GlanceModifier.fillMaxWidth(),
                    style = TextStyle(
                        color = if (next && !running) Primer.accent else Primer.muted,
                        fontSize = 9.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, tone: ChipTone) {
    // 名字不能叫 background/color：那会遮蔽 androidx.glance 的同名修饰符。
    val chipBackground = when (tone) {
        ChipTone.NEUTRAL -> Primer.canvasSubtle
        ChipTone.ATTENTION -> AttentionChipBackground
        ChipTone.ACCENT -> AccentChipBackground
    }
    val chipForeground = when (tone) {
        ChipTone.NEUTRAL -> Primer.muted
        ChipTone.ATTENTION -> AttentionChipForeground
        ChipTone.ACCENT -> Primer.accent
    }
    Text(
        text = label,
        modifier = GlanceModifier
            .background(chipBackground)
            .cornerRadius(3.dp)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        style = TextStyle(
            color = chipForeground,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
}
