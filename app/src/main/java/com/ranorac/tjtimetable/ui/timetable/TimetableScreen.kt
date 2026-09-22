package com.ranorac.tjtimetable.ui.timetable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ranorac.tjtimetable.domain.ClassOccurrence
import com.ranorac.tjtimetable.domain.PeriodSchedule
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.Timetable
import com.ranorac.tjtimetable.domain.TimetableResolver
import com.ranorac.tjtimetable.domain.todayColumn
import com.ranorac.tjtimetable.domain.weekdayColumns
import com.ranorac.tjtimetable.ui.components.AccentBadge
import com.ranorac.tjtimetable.ui.components.AttentionBadge
import com.ranorac.tjtimetable.ui.components.GitHubPrimaryButton
import com.ranorac.tjtimetable.ui.components.MutedText
import com.ranorac.tjtimetable.ui.components.PageHeader
import com.ranorac.tjtimetable.ui.components.SnackbarMessageEffect
import com.ranorac.tjtimetable.ui.theme.CourseHues
import com.ranorac.tjtimetable.ui.theme.LocalGitHubColors
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Bounds for one 节 row's height, as a fraction of the timetable's own width.
 *
 * The row height is not a constant: [WeekPage] divides the height left over after the week
 * row and the weekday header. Its **ceiling** is relative too — one row may grow to
 * [MAX_UNIT_HEIGHT_PER_COLUMN] × the width of a single day column, so cells stay roughly
 * square on any screen instead of turning into ribbons on a tablet (where the columns get
 * wide but a fixed cap would leave the grid short) or into slabs on a tall phone.
 *
 * [MIN_UNIT_HEIGHT] stays absolute on purpose: it is a legibility floor (the smallest row
 * that still fits a 节 number and its times), not a position.
 */
private val MIN_UNIT_HEIGHT: Dp = 34.dp
private const val MAX_UNIT_HEIGHT_PER_COLUMN = 1.5f

/**
 * Width of the 节次/时间 gutter, as a fraction of the page width.
 *
 * Relative for the same reason: 46dp is right on a phone but a sliver next to tablet-wide
 * day columns. The bounds keep it from becoming a huge empty strip or from cramping the
 * "08:00" label on the narrowest phones.
 */
private const val GUTTER_WIDTH_FRACTION = 0.13f
private val MIN_GUTTER_WIDTH: Dp = 44.dp
private val MAX_GUTTER_WIDTH: Dp = 76.dp

/**
 * Height a course block needs before the teacher's line is added.
 *
 * 课名(最多两行) + 教室 + 老师 + 内边距 ≈ 58dp. Below it the teacher is omitted rather than
 * allowed to squeeze the course name, which is the one line that must never disappear.
 */
private val TEACHER_MIN_BLOCK_HEIGHT: Dp = 58.dp

/**
 * Horizontal travel, in pixels, before a swipe is treated as "change week".
 *
 * Large enough that a sloppy tap never pages the grid, small enough that a deliberate flick
 * does not have to cross the whole screen.
 */
private const val SWIPE_THRESHOLD_PX = 96f

/** Weekday labels, indexed 1..7. */
private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    state: TimetableUiState,
    onStepWeek: (Int) -> Unit,
    onJumpToToday: () -> Unit,
    onImport: () -> Unit,
    onConsumeMessage: () -> Unit,
    onCourseClick: (ClassOccurrence) -> Unit,
) {
    val gh = LocalGitHubColors.current
    val snackbar = remember { SnackbarHostState() }

    SnackbarMessageEffect(
        message = state.message,
        host = snackbar,
        onConsumed = onConsumeMessage,
    )

    Scaffold(
        containerColor = gh.canvasDefault,
        snackbarHost = { SnackbarHost(snackbar) },
        // Insets are taken here **exactly once**: the Scaffold's default `systemBars` would
        // also fold the status-bar inset into `padding`, so pairing it with
        // statusBarsPadding() counted the same height twice — which pushed the header down
        // and left an empty strip of canvas under the status bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Consumed here for the empty state (which has no header). The header's own
                // statusBarsPadding() then resolves to zero instead of counting twice.
                .statusBarsPadding(),
        ) {
            val timetable = state.timetable
            if (timetable == null || !state.hasData) {
                EmptyState(onImport = onImport)
            } else {
                Column(Modifier.fillMaxSize()) {
                    WeekSelector(
                        week = state.week,
                        totalWeeks = timetable.term.totalWeeks,
                        termName = timetable.term.name,
                        isBrowsingOtherWeek = state.isBrowsingOtherWeek,
                        onStepWeek = onStepWeek,
                        onJumpToToday = onJumpToToday,
                        onImport = onImport,
                    )
                    // Column order is computed once and shared by the header and
                    // the grid, so the two can never disagree about which weekday
                    // sits in which column.
                    val days = weekdayColumns(
                        showWeekend = state.settings.showWeekend,
                        startOnToday = state.settings.startOnToday,
                        today = state.today,
                    )
                    val schedule = timetable.periodSchedule ?: PeriodSchedule.TONGJI
                    // One animation over the WHOLE week page — the date row and the grid
                    // together — so a week switch reads as one sheet sliding sideways
                    // rather than as a grid that jumps while its dates fade.
                    AnimatedContent(
                        targetState = state.week,
                        modifier = Modifier
                            .fillMaxSize()
                            // Swiping anywhere on the page changes week, which is how a
                            // timetable is expected to page. Horizontal drags are consumed
                            // here; vertical ones are left alone so the grid still scrolls,
                            // and taps still reach the course blocks.
                            .pointerInput(state.week) {
                                var travelled = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { travelled = 0f },
                                    onDragEnd = { travelled = 0f },
                                    onDragCancel = { travelled = 0f },
                                    onHorizontalDrag = { _, delta ->
                                        travelled += delta
                                        // Dragging right reveals the previous week, dragging
                                        // left the next — the same direction the slide moves.
                                        if (travelled > SWIPE_THRESHOLD_PX) {
                                            travelled = 0f
                                            onStepWeek(-1)
                                        } else if (travelled < -SWIPE_THRESHOLD_PX) {
                                            travelled = 0f
                                            onStepWeek(1)
                                        }
                                    },
                                )
                            },
                        transitionSpec = {
                            // Direction follows the week delta so paging left/right feels
                            // spatial rather than like a cross-fade. Kept short: the whole
                            // page is composed twice while it runs, so its length is the
                            // window in which a mid-range phone can drop frames.
                            val forward = targetState > initialState
                            val distance = if (forward) 1 else -1
                            (slideInHorizontally(tween(180)) { w -> distance * w / 3 } +
                                fadeIn(tween(120)))
                                .togetherWith(
                                    slideOutHorizontally(tween(180)) { w -> -distance * w / 3 } +
                                        fadeOut(tween(90)),
                                )
                        },
                        label = "week",
                    ) { week ->
                        WeekPage(
                            week = week,
                            term = timetable.term,
                            timetable = timetable,
                            schedule = schedule,
                            days = days,
                            // Passed down rather than re-read with LocalDate.now(): a
                            // `remember`ed date never rolls over, so a resumed app highlighted
                            // yesterday's column all morning.
                            today = state.today,
                            includeInactive = state.settings.dimInactiveCourses,
                            onCourseClick = onCourseClick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Header actions have to fit four buttons plus the title on a phone, so they are 40dp
 * rather than Material3's 48dp — still comfortably tappable, and 32dp narrower in total.
 */
private val HEADER_ACTION_SIZE: Dp = 40.dp

@Composable
private fun WeekSelector(
    week: Int,
    totalWeeks: Int,
    termName: String,
    isBrowsingOtherWeek: Boolean,
    onStepWeek: (Int) -> Unit,
    onJumpToToday: () -> Unit,
    onImport: () -> Unit,
) {
    val gh = LocalGitHubColors.current
    // The week number is the page title and the semester its subtitle, exactly like
    // "调休与校历" over the term name on the next page — same height, same baseline, same
    // 16dp inset, so switching tabs no longer shifts the title around.
    PageHeader(
        title = "第 $week 周",
        subtitle = termName,
        actions = {
            // The slot is ALWAYS reserved, even when the button is absent: letting it appear
            // mid-row shoved the next-week button inward the moment the student was about to
            // press it.
            Box(Modifier.size(HEADER_ACTION_SIZE), contentAlignment = Alignment.Center) {
                if (isBrowsingOtherWeek) {
                    IconButton(
                        onClick = onJumpToToday,
                        modifier = Modifier.size(HEADER_ACTION_SIZE),
                    ) {
                        Icon(
                            // A home icon, by request. It also reads better than the back arrow
                            // it replaces: the action goes *home* to the current week rather than
                            // back one step, and the chevron pair beside it already owns
                            // "back one week / forward one week" — so an arrow here meant two
                            // different things depending on which button was pressed.
                            Icons.Filled.Home,
                            contentDescription = "回到本周",
                            tint = gh.accentFg,
                        )
                    }
                }
            }
            IconButton(
                onClick = { onStepWeek(-1) },
                enabled = week > 1,
                modifier = Modifier.size(HEADER_ACTION_SIZE),
            ) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = "上一周",
                    tint = if (week > 1) gh.fgDefault else gh.fgSubtle,
                )
            }
            IconButton(
                onClick = { onStepWeek(1) },
                enabled = week < totalWeeks,
                modifier = Modifier.size(HEADER_ACTION_SIZE),
            ) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "下一周",
                    tint = if (week < totalWeeks) gh.fgDefault else gh.fgSubtle,
                )
            }
            // The import entry point. Kept in the header because it is the one action a
            // student with no timetable needs, and the empty state has its own button.
            IconButton(onClick = onImport, modifier = Modifier.size(HEADER_ACTION_SIZE)) {
                Icon(
                    Icons.Filled.CloudDownload,
                    contentDescription = "登录教务系统导入",
                    tint = gh.accentFg,
                )
            }
        },
    )
}

@Composable
private fun WeekdayHeader(
    term: TermCalendar,
    week: Int,
    days: List<DayOfWeek>,
    /** Same width the grid uses, so the dates stay over their own columns on any screen. */
    gutterWidth: Dp,
    /** Today, from the UI state, so the dot follows the date across a midnight roll-over. */
    today: LocalDate,
) {
    val gh = LocalGitHubColors.current

    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Spacer(Modifier.width(gutterWidth))
        for (dow in days) {
            val date = term.dateOf(week, dow)
            val isToday = date == today
            val foreground by animateColorAsState(
                targetValue = if (isToday) gh.accentFg else gh.fgMuted,
                animationSpec = tween(220),
                label = "dayFg",
            )
            Column(
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    DAY_LABELS[dow.value - 1],
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                    color = foreground,
                )
                Text(
                    "${date.monthValue}/${date.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground,
                )
                Spacer(Modifier.height(3.dp))
                // A dot rather than a filled cell: today stands out without
                // shouting, and its appearance animates instead of popping.
                val dotAlpha by animateFloatAsState(
                    targetValue = if (isToday) 1f else 0f,
                    animationSpec = tween(220),
                    label = "todayDot",
                )
                Box(
                    Modifier
                        .size(4.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(gh.accentFg),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(gh.borderMuted))
}

/**
 * One week, as a single sliding page: the date row on top, the 节次 grid under it.
 *
 * The week's classes are derived from [week] rather than read from shared UI state, and
 * that is load-bearing rather than tidy: while a switch animates, BOTH weeks are on screen,
 * so reading one shared list meant the page sliding out was redrawn with the incoming
 * week's courses. The student saw the grid contents swap instantly while only the frame
 * slid — the "flash" — instead of one sheet of paper moving across another.
 */
@Composable
private fun WeekPage(
    week: Int,
    term: TermCalendar,
    timetable: Timetable,
    schedule: PeriodSchedule,
    days: List<DayOfWeek>,
    today: LocalDate,
    includeInactive: Boolean,
    onCourseClick: (ClassOccurrence) -> Unit,
) {
    val gh = LocalGitHubColors.current
    val maxUnit = schedule.maxUnit

    val occurrences = remember(timetable, week, includeInactive) {
        TimetableResolver.weekGrid(timetable, week, includeInactive)
    }
    // Bucket the week's classes by weekday ONCE. Doing this inside the column loop meant
    // seven scans of the week plus seven brand-new lists on every recomposition — and a
    // new list instance is also what defeats each column's `remember` for lane packing.
    val byDay = remember(occurrences, days) {
        val buckets = days.associateWith { mutableListOf<ClassOccurrence>() }
        for (occurrence in occurrences) {
            // A 补课日 resolves to a different weekday, so match on the date's weekday
            // rather than the session's.
            buckets[occurrence.date.dayOfWeek]?.add(occurrence)
        }
        buckets
    }
    val packed = remember(byDay) { byDay.mapValues { (_, items) -> layoutDay(items) } }
    // One ScrollState per page: the gutter and the seven day columns live inside a single
    // scrollable Row, so they physically cannot drift apart.
    val scroll = rememberScrollState()

    // Two measurement passes, each asking the question it can answer:
    //  - the outer one knows the page WIDTH, which decides the gutter and the row ceiling;
    //  - the inner one knows the height left under the weekday row, which decides the rows.
    // Both are relative, so a phone and a tablet get the same layout with different numbers
    // rather than a phone layout stretched over a tablet.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutterWidth = (maxWidth * GUTTER_WIDTH_FRACTION)
            .coerceIn(MIN_GUTTER_WIDTH, MAX_GUTTER_WIDTH)

        Column(Modifier.fillMaxSize()) {
            WeekdayHeader(term = term, week = week, days = days, gutterWidth = gutterWidth, today = today)

            BoxWithConstraints(Modifier.fillMaxSize()) {
                // All 11 节 fit instead of assuming a fixed 58dp row and scrolling past the
                // last few; the ceiling is tied to the column width so cells keep a sane
                // shape whatever the screen.
                val columnWidth = ((maxWidth - gutterWidth) / days.size.coerceAtLeast(1))
                val maxUnitHeight = columnWidth * MAX_UNIT_HEIGHT_PER_COLUMN
                val unitHeight = (maxHeight / maxUnit)
                    .coerceIn(MIN_UNIT_HEIGHT, maxUnitHeight.coerceAtLeast(MIN_UNIT_HEIGHT))
                val gridHeight = unitHeight * maxUnit
                // The column to tint is the one whose DATE is today — see [todayColumn] for why
                // matching on the weekday tinted that weekday in every week and made the
                // highlight meaningless. -1 (another week, or a hidden column) tints nothing.
                val highlightedColumn = todayColumn(term, week, days, today)

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        // Kept as a safety valve: the division above fits every real phone,
                        // but a window shorter than MIN_UNIT_HEIGHT * maxUnit (a split-screen
                        // sliver) still has to be reachable rather than silently clipped.
                        .verticalScroll(scroll),
                ) {
                    PeriodGutter(
                        schedule = schedule,
                        maxUnit = maxUnit,
                        unitHeight = unitHeight,
                        gridHeight = gridHeight,
                        width = gutterWidth,
                    )

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(gridHeight)
                            // Today's column tint and the 节 rules in ONE draw pass, painted
                            // in that order. Both used to be separate layers, and the tint —
                            // being a background *of* each column — ended up ABOVE the rules,
                            // so the highlighted column lost its grid lines and read as a hole
                            // in the timetable. As individual 1dp Boxes the rules were also 84
                            // extra layout nodes to measure, place and draw on every frame.
                            .drawBehind {
                                drawGrid(
                                    highlight = gh.canvasSubtle,
                                    line = gh.borderMuted,
                                    highlightColumn = highlightedColumn,
                                    unitHeight = unitHeight,
                                    maxUnit = maxUnit,
                                    columns = days.size,
                                )
                            },
                    ) {
                        for (dow in days) {
                            DayColumn(
                                modifier = Modifier.weight(1f),
                                dayOfWeek = dow,
                                week = week,
                                term = term,
                                placed = packed[dow].orEmpty(),
                                unitHeight = unitHeight,
                                gridHeight = gridHeight,
                                onCourseClick = onCourseClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draws the grid: today's column tint first, then the 节 rules and column separators.
 *
 * Order matters. The tint has to be UNDER the rules — when the tint was each column's own
 * background it covered them, so the highlighted column lost its grid lines and read as a
 * hole in the timetable.
 */
private fun DrawScope.drawGrid(
    highlight: Color,
    line: Color,
    highlightColumn: Int,
    unitHeight: Dp,
    maxUnit: Int,
    columns: Int,
) {
    val lineWidth = 1.dp.toPx()
    val unit = unitHeight.toPx()
    val column = if (columns > 0) size.width / columns else size.width

    if (highlightColumn in 0 until columns) {
        drawRect(
            color = highlight,
            topLeft = Offset(column * highlightColumn, 0f),
            size = Size(column, size.height),
        )
    }
    for (i in 0 until maxUnit) {
        drawRect(line, topLeft = Offset(0f, unit * i), size = Size(size.width, lineWidth))
    }
    for (i in 0 until columns) {
        drawRect(line, topLeft = Offset(column * i, 0f), size = Size(lineWidth, size.height))
    }
}

@Composable
private fun PeriodGutter(
    schedule: PeriodSchedule,
    maxUnit: Int,
    unitHeight: Dp,
    gridHeight: Dp,
    width: Dp,
) {
    val gh = LocalGitHubColors.current
    // 节次 + 开始 + 结束 = 16 + 13 + 13 = 42dp of text, so the times appear only when the row
    // can hold them without clipping: a clipped "10:0" is worse than no time at all, and the
    // 节次 number is the anchor that always stays.
    val showStartTime = unitHeight >= 40.dp
    val showEndTime = unitHeight >= 48.dp

    Column(modifier = Modifier.width(width).height(gridHeight)) {
        for (unit in 1..maxUnit) {
            Column(
                // fillMaxWidth is what makes the centring below mean anything: without it the
                // row only wraps the widest label (~30dp inside a ~47dp gutter), so centring
                // happened inside that narrow box and the whole group sat visibly left.
                modifier = Modifier.fillMaxWidth().height(unitHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Centred as a block inside the row rather than pinned to its top edge, so
                // the three lines sit in the middle of the cell they describe.
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "$unit",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = gh.fgMuted,
                )
                if (showStartTime) {
                    schedule.startOf(unit)?.let {
                        Text(
                            "%02d:%02d".format(it.hour, it.minute),
                            style = MaterialTheme.typography.labelSmall,
                            color = gh.fgSubtle,
                        )
                    }
                }
                if (showEndTime) {
                    schedule.endOf(unit)?.let {
                        Text(
                            "%02d:%02d".format(it.hour, it.minute),
                            style = MaterialTheme.typography.labelSmall,
                            color = gh.fgSubtle,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    modifier: Modifier,
    dayOfWeek: DayOfWeek,
    week: Int,
    term: TermCalendar,
    /** This weekday's occurrences, already lane-packed by [WeekPage]. */
    placed: List<Placed>,
    unitHeight: Dp,
    gridHeight: Dp,
    onCourseClick: (ClassOccurrence) -> Unit,
) {
    val date = term.dateOf(week, dayOfWeek)
    // No background and no rules here: today's tint and the grid lines are drawn once for
    // the whole grid (see drawGrid), which is both cheaper and the only way to keep the
    // lines visible inside the highlighted column.
    Box(modifier = modifier.height(gridHeight)) {
        for (p in placed) {
            val top = unitHeight * (p.occurrence.startUnit - 1)
            val height = unitHeight * p.occurrence.unitSpan
            // Weight-based slicing: lane 0 of 2 takes the left half, and so on.
            // Using weights avoids needing the column's measured width in dp.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .offset(y = top)
                    .padding(horizontal = 1.dp, vertical = 1.dp),
            ) {
                if (p.lane > 0) Spacer(Modifier.weight(p.lane.toFloat()))
                // Keyed by session: paging to the next week updates this list in place, and
                // a class that runs in both weeks keeps its node instead of being torn down
                // and rebuilt — which is most of what a week switch used to cost.
                key(p.occurrence.session.id) {
                    CourseBlock(
                        occurrence = p.occurrence,
                        unitHeight = unitHeight,
                        modifier = Modifier.weight(1f),
                        onClick = { onCourseClick(p.occurrence) },
                    )
                }
                val trailing = p.lanes - p.lane - 1
                if (trailing > 0) Spacer(Modifier.weight(trailing.toFloat()))
            }
        }
    }
}

@Composable
private fun CourseBlock(
    occurrence: ClassOccurrence,
    unitHeight: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val gh = LocalGitHubColors.current
    val hue = CourseHues[occurrence.course.effectiveColorIndex % CourseHues.size]
    // Off-week sessions stay visible but recede, so 单双周 is readable at a glance.
    val targetAlpha = if (occurrence.isActive) 1f else 0.32f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(240),
        label = "blockAlpha",
    )
    val shape = RoundedCornerShape(5.dp)

    // A squeezed row trades detail for legibility: at the minimum row height a single-节
    // block only has room for one line of the course name, and a second line would push
    // the name itself out of the card. Blocks spanning two or more 节 always have room.
    val compact = unitHeight < 46.dp
    val nameLines = if (compact && occurrence.unitSpan < 2) 1 else 2

    Column(
        modifier = modifier
            .fillMaxHeight()
            .alpha(alpha)
            .clip(shape)
            .background(hue.container(gh.isDark))
            .border(1.dp, hue.onContainer(gh.isDark).copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = if (compact) 1.dp else 3.dp),
    ) {
        Text(
            occurrence.course.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = hue.onContainer(gh.isDark),
            maxLines = nameLines,
            overflow = TextOverflow.Ellipsis,
        )
        occurrence.room?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = hue.onContainer(gh.isDark).copy(alpha = 0.78f),
                maxLines = if (occurrence.unitSpan >= 3) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 授课老师. Judged by the height the block actually has, not by how many 节 it spans:
        // the three lines (course name up to two, 教室, teacher) need ~58dp, and at that
        // height even a single-节 class has room. When there is not enough, the teacher is
        // dropped rather than allowed to push the course name out of the card — it is still
        // in the course detail sheet.
        val teacher = (occurrence.session.teacher ?: occurrence.course.teacher)
            ?.takeIf { it.isNotBlank() }
        if (teacher != null && unitHeight * occurrence.unitSpan >= TEACHER_MIN_BLOCK_HEIGHT) {
            Text(
                teacher,
                style = MaterialTheme.typography.labelSmall,
                color = hue.onContainer(gh.isDark).copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (occurrence.unitSpan >= 3) {
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (occurrence.isMakeup) AttentionBadge("补课")
                occurrence.session.weeks.parityLabel?.let { AccentBadge(it) }
            }
        }
    }
}

@Composable
private fun EmptyState(onImport: () -> Unit) {
    val gh = LocalGitHubColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "还没有课表",
            style = MaterialTheme.typography.headlineSmall,
            color = gh.fgDefault,
        )
        Spacer(Modifier.height(8.dp))
        MutedText("点下面的按钮登录教务系统；登录后进入「我的课表」即可导入")
        Spacer(Modifier.height(20.dp))
        GitHubPrimaryButton(text = "登录教务系统", onClick = onImport)
    }
}

// ------------------------------------------------------------------ layout

/** An occurrence together with the column slot it should occupy. */
private data class Placed(val occurrence: ClassOccurrence, val lane: Int, val lanes: Int)

/**
 * Assigns side-by-side lanes to overlapping occurrences.
 *
 * Overlaps are real: 单双周 can put two different courses in the same slot, and
 * a 补课 can stack on top of a regular class. Occurrences are grouped into
 * transitively-overlapping clusters, then greedily packed so that a cluster of
 * two splits the column 50/50 while an isolated class takes the full width.
 */
private fun layoutDay(items: List<ClassOccurrence>): List<Placed> {
    if (items.isEmpty()) return emptyList()
    val sorted = items.sortedWith(
        compareBy({ it.startUnit }, { it.endUnit }, { it.course.name }),
    )
    val result = ArrayList<Placed>(sorted.size)

    var clusterStart = 0
    while (clusterStart < sorted.size) {
        // Grow the cluster while the next item overlaps anything already in it.
        var clusterEnd = clusterStart
        var maxEnd = sorted[clusterStart].endUnit
        var i = clusterStart + 1
        while (i < sorted.size && sorted[i].startUnit <= maxEnd) {
            maxEnd = maxOf(maxEnd, sorted[i].endUnit)
            clusterEnd = i
            i++
        }

        // Greedy first-fit lane packing within the cluster.
        val laneEnds = ArrayList<Int>()
        val laneOf = IntArray(clusterEnd - clusterStart + 1)
        for (idx in clusterStart..clusterEnd) {
            val occ = sorted[idx]
            var lane = -1
            for (l in laneEnds.indices) {
                if (laneEnds[l] < occ.startUnit) {
                    lane = l
                    break
                }
            }
            if (lane == -1) {
                laneEnds.add(occ.endUnit)
                lane = laneEnds.size - 1
            } else {
                laneEnds[lane] = occ.endUnit
            }
            laneOf[idx - clusterStart] = lane
        }
        val lanes = laneEnds.size
        for (idx in clusterStart..clusterEnd) {
            result.add(Placed(sorted[idx], laneOf[idx - clusterStart], lanes))
        }
        clusterStart = clusterEnd + 1
    }
    return result
}
