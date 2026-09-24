package com.ranorac.tjtimetable.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ranorac.tjtimetable.domain.AdjustmentSource
import com.ranorac.tjtimetable.domain.cnLabel
import com.ranorac.tjtimetable.ui.components.AccentBadge
import com.ranorac.tjtimetable.ui.components.AttentionBadge
import com.ranorac.tjtimetable.ui.components.GitHubCard
import com.ranorac.tjtimetable.ui.components.GitHubDivider
import com.ranorac.tjtimetable.ui.components.GitHubSecondaryButton
import com.ranorac.tjtimetable.ui.components.GitHubTextField
import com.ranorac.tjtimetable.ui.components.MutedText
import com.ranorac.tjtimetable.ui.components.NeutralBadge
import com.ranorac.tjtimetable.ui.components.PageHeader
import com.ranorac.tjtimetable.ui.components.SCREEN_HORIZONTAL_PADDING
import com.ranorac.tjtimetable.ui.components.SECTION_GAP
import com.ranorac.tjtimetable.ui.components.SectionHeader
import com.ranorac.tjtimetable.ui.components.SelectableRow
import com.ranorac.tjtimetable.ui.components.SnackbarMessageEffect
import com.ranorac.tjtimetable.ui.components.VGap
import com.ranorac.tjtimetable.ui.components.pageContentWidth
import com.ranorac.tjtimetable.ui.theme.LocalGitHubColors
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustmentScreen(
    state: AdjustmentUiState,
    onSetNormal: (java.time.LocalDate) -> Unit,
    onSetNoClasses: (java.time.LocalDate) -> Unit,
    onSetFollows: (java.time.LocalDate, DayOfWeek) -> Unit,
    onResetToAuto: (java.time.LocalDate) -> Unit,
    onRefresh: () -> Unit,
    onApplyNotice: (String) -> Unit,
    onConsumeMessage: () -> Unit,
) {
    val gh = LocalGitHubColors.current
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<DayRow?>(null) }
    var notice by remember { mutableStateOf("") }

    SnackbarMessageEffect(
        message = state.message,
        host = snackbar,
        onConsumed = onConsumeMessage,
    )

    Scaffold(
        containerColor = gh.canvasDefault,
        // Same reason as 设置: the bottom bar (in MainActivity) and PageHeader already own
        // the bottom / top insets, so Scaffold's default `systemBars` would add the
        // navigation bar's height again as a dead band above the bar. On this page it also
        // pushed the no-term message visibly off centre.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // Same header component, same 64dp, same 16dp inset as 课表 and 设置.
            PageHeader(title = "调休与校历", subtitle = state.term?.name)
        },
    ) { padding ->
        if (state.term == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                MutedText("请先在设置中导入课表，以获得学期信息")
            }
            return@Scaffold
        }

        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.pageContentWidth().fillMaxHeight(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = SCREEN_HORIZONTAL_PADDING,
                    end = SCREEN_HORIZONTAL_PADDING,
                    top = 8.dp,
                    bottom = 32.dp,
                ),
                // 16dp between cards, matching the other list pages.
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
            ) {
                item {
                    SummaryCard(state)
                }
                item {
                    CalendarSection(
                        busy = state.busy,
                        notice = notice,
                        onNoticeChange = { notice = it },
                        onRefresh = onRefresh,
                        onApplyNotice = {
                            onApplyNotice(notice)
                            notice = ""
                        },
                    )
                }
                item {
                    MutedText(
                        "校历只说明某天是否上课，不会说明「补哪一天的课」——那个口径只存在于教务处通知里。" +
                            "因此自动判断会标注为「推测」，点任意一天即可改正。",
                    )
                }
                items(state.weeks, key = { it.week }) { week ->
                    WeekCard(week = week, onDayClick = { editing = it })
                }
            }
        }
    }

    editing?.let { row ->
        DayEditorDialog(
            row = row,
            onDismiss = { editing = null },
            onNormal = { onSetNormal(row.date); editing = null },
            onNoClasses = { onSetNoClasses(row.date); editing = null },
            onFollows = { dow -> onSetFollows(row.date, dow); editing = null },
            onReset = { onResetToAuto(row.date); editing = null },
        )
    }
}

@Composable
private fun SummaryCard(state: AdjustmentUiState) {
    val gh = LocalGitHubColors.current
    GitHubCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SummaryStat("${state.term?.totalWeeks ?: 0}", "教学周", Modifier.weight(1f))
            SummaryStat("${state.offCount}", "停课日", Modifier.weight(1f))
            SummaryStat("${state.manualCount}", "手动", Modifier.weight(1f))
            SummaryStat("${state.inferredCount}", "推测", Modifier.weight(1f))
        }
        if (state.inferredCount > 0) {
            VGap()
            GitHubDivider()
            VGap()
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttentionBadge("推测")
                Spacer(Modifier.width(8.dp))
                MutedText("有 ${state.inferredCount} 天是自动推测的补课，建议核对教务处通知")
            }
        }
    }
}

/**
 * The 校历与调休 controls, moved here from 设置.
 *
 * They belong on this screen: the day rows below are the *result* of these two actions,
 * so a student who spots a wrong 推测补课 is already one tap away from correcting it, and
 * the paste box sits next to the rows it rewrites instead of in a different tab.
 *
 * The paste box starts collapsed on purpose. Fully open it is ~300dp — most of a phone
 * screen — and this page's job is the day list underneath, so an always-open form would
 * push the very rows the student came for out of sight. 刷新校历与调休 stays visible
 * because it is the action taken without reading anything.
 */
@Composable
private fun CalendarSection(
    busy: Boolean,
    notice: String,
    onNoticeChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onApplyNotice: () -> Unit,
) {
    // rememberSaveable rather than remember: the section lives in a LazyColumn item, and
    // plain `remember` state is dropped the moment the item scrolls out of the window.
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        SectionHeader("校历与调休")
        VGap()
        GitHubCard {
            MutedText("校历与调休无需登录即可获取。粘贴教务处通知可自动识别「补课」与「放假」安排。")
            VGap()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GitHubSecondaryButton(
                    text = "刷新校历与调休",
                    enabled = !busy,
                    onClick = onRefresh,
                )
                GitHubSecondaryButton(
                    text = if (expanded) "收起" else "粘贴通知",
                    onClick = { expanded = !expanded },
                )
            }
            if (expanded) {
                VGap()
                GitHubTextField(
                    label = "粘贴通知原文",
                    value = notice,
                    onValueChange = onNoticeChange,
                    singleLine = false,
                    minLines = 3,
                )
                VGap()
                GitHubSecondaryButton(
                    text = "识别并应用",
                    enabled = notice.isNotBlank() && !busy,
                    onClick = onApplyNotice,
                )
                VGap()
                MutedText(
                    "示例：5月6日（周六）补5月3日（周三）的课；5月1日至5月5日放假。" +
                        "自动推测的补课安排会标注为「推测」，可点下方任意一天修正。",
                )
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String, modifier: Modifier = Modifier) {
    val gh = LocalGitHubColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = gh.fgDefault,
            fontWeight = FontWeight.SemiBold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = gh.fgMuted)
    }
}

@Composable
private fun WeekCard(week: WeekRow, onDayClick: (DayRow) -> Unit) {
    val gh = LocalGitHubColors.current
    Column {
        SectionHeader("第 ${week.week} 周")
        MutedText(week.label)
        VGap()
        GitHubCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
            week.days.forEachIndexed { index, day ->
                if (index > 0) GitHubDivider()
                DayRowItem(day = day, onClick = { onDayClick(day) })
            }
        }
    }
}

@Composable
private fun DayRowItem(day: DayRow, onClick: () -> Unit) {
    val gh = LocalGitHubColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A dot marks teaching days so the whole month is scannable at a glance.
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !day.isTeaching -> gh.borderDefault
                        day.isMakeup -> gh.attentionFg
                        else -> gh.successFg
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${day.date.monthValue}/${day.date.dayOfMonth}  ${day.dayLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (day.isTeaching) gh.fgDefault else gh.fgMuted,
            )
            Text(
                describe(day),
                style = MaterialTheme.typography.labelSmall,
                color = gh.fgMuted,
            )
        }
        day.source?.let { source ->
            when (source) {
                AdjustmentSource.MANUAL -> AccentBadge("手动")
                AdjustmentSource.INFERRED -> AttentionBadge("推测")
                AdjustmentSource.API -> NeutralBadge(kindLabel(day))
            }
        }
    }
}

private fun kindLabel(day: DayRow): String =
    day.adjustment?.kind?.label ?: "校历"

/** One-line explanation of what actually runs on the date. */
private fun describe(day: DayRow): String = when {
    !day.isTeaching -> "停课（${day.adjustment?.kind?.label ?: "手动"}）"
    day.isMakeup -> "按周${day.effectiveWeekday!!.cnLabel().removePrefix("周")}课表上课"
    day.effectiveWeekday != day.dayOfWeek ->
        "按周${day.effectiveWeekday!!.cnLabel().removePrefix("周")}课表"
    else -> "正常上课"
}

@Composable
private fun DayEditorDialog(
    row: DayRow,
    onDismiss: () -> Unit,
    onNormal: () -> Unit,
    onNoClasses: () -> Unit,
    onFollows: (DayOfWeek) -> Unit,
    onReset: () -> Unit,
) {
    val gh = LocalGitHubColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = gh.canvasDefault,
        titleContentColor = gh.fgDefault,
        textContentColor = gh.fgMuted,
        title = {
            Text("${row.date.monthValue} 月 ${row.date.dayOfMonth} 日 · ${row.dayLabel}")
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MutedText("当前：${describe(row)}")
                VGap()
                SelectableRow("正常上课（按当天星期）", onClick = onNormal)
                SelectableRow("放假 / 停课", onClick = onNoClasses)
                VGap()
                MutedText("按其他星期的课表上课（补课）")
                VGap()
                // Seven explicit rows beat a chip grid here: each choice needs a
                // full-width tap target and the label reads as one sentence.
                for (index in 1..7) {
                    val dow = DayOfWeek.of(index)
                    val selected = row.isTeaching && row.effectiveWeekday == dow
                    SelectableRow(
                        text = dow.cnLabel(),
                        selected = selected,
                        onClick = { onFollows(dow) },
                    )
                }
                VGap()
                GitHubDivider()
                SelectableRow("恢复为校历自动判断", onClick = onReset)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = gh.accentFg)
            }
        },
    )
}
