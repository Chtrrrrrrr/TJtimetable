package com.ranorac.tjtimetable.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ranorac.tjtimetable.domain.ClassOccurrence
import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.cnLabel
import com.ranorac.tjtimetable.ui.components.AccentBadge
import com.ranorac.tjtimetable.ui.components.AttentionBadge
import com.ranorac.tjtimetable.ui.components.GitHubCard
import com.ranorac.tjtimetable.ui.components.GitHubDivider
import com.ranorac.tjtimetable.ui.components.GitHubSecondaryButton
import com.ranorac.tjtimetable.ui.components.MutedText
import com.ranorac.tjtimetable.ui.components.NeutralBadge
import com.ranorac.tjtimetable.ui.components.VGap
import com.ranorac.tjtimetable.ui.theme.CourseHues
import com.ranorac.tjtimetable.ui.theme.LocalGitHubColors
import kotlinx.coroutines.launch

/**
 * Course detail, shown as a bottom sheet when a class block is tapped.
 *
 * The sheet is keyed on the course *id* rather than holding a [Course] snapshot,
 * so that changing the colour or the note repaints immediately instead of showing
 * stale values until the sheet is reopened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: Course,
    /** The block the student tapped, for "this occurrence" context. */
    occurrence: ClassOccurrence?,
    /** Every meeting slot of this course, for the 全部时段 list. */
    sessions: List<CourseSession>,
    onDismiss: () -> Unit,
    onSetColor: (Int?) -> Unit,
    onSetHidden: (Boolean) -> Unit,
    onSetNote: (String?) -> Unit,
) {
    val gh = LocalGitHubColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hue = CourseHues[course.effectiveColorIndex % CourseHues.size]

    // Dismissal must NOT drive the sheet's own animation, and that is the fix for a total freeze.
    //
    // The previous revision routed every exit through `sheetState.hide()` inside a
    // `rememberCoroutineScope().launch`. That scope belongs to this composition and is cancelled
    // the moment the parent stops composing the sheet — so the second half of the path (the
    // `onDismiss()` that clears the parent's selection) could simply never run. The sheet then
    // stayed in the hierarchy with its modal layer attached: an invisible scrim swallowing every
    // touch on the page. Whole-screen freeze, no crash, nothing in the log to grep for.
    //
    // Material3 animates the dismissal itself: when the scrim is tapped, back is pressed, or the
    // sheet is dragged down, it plays the hide animation and only then calls `onDismissRequest`.
    // So that callback is the single, non-suspending way out, and the parent reacts by clearing
    // its state — which removes this composable and disposes the dialog window in the same frame.
    val dismiss: () -> Unit = onDismiss

    // Seeded per course; re-seeding on every recomposition would fight typing.
    var note by remember(course.id) { mutableStateOf(course.note.orEmpty()) }
    val noteDirty = note != course.note.orEmpty()

    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
        containerColor = gh.canvasDefault,
        contentColor = gh.fgDefault,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // ------------------------------------------------------ header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 4.dp, height = 38.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(hue.container(gh.isDark)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        course.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = gh.fgDefault,
                    )
                    val subtitle = listOfNotNull(
                        course.teacher,
                        course.className,
                        course.campus,
                    ).joinToString(" · ")
                    if (subtitle.isNotBlank()) MutedText(subtitle)
                }
            }

            occurrence?.let {
                VGap()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccentBadge("第 ${it.week} 周")
                    it.session.weeks.parityLabel?.let { label -> NeutralBadge(label) }
                    if (it.isMakeup) AttentionBadge("补课")
                }
            }

            VGap(2)

            // ------------------------------------------- this week's slot
            if (occurrence != null) {
                GitHubCard {
                    InfoRow("日期", "${occurrence.date.monthValue} 月 ${occurrence.date.dayOfMonth} 日 ${occurrence.date.dayOfWeek.cnLabel()}")
                    if (occurrence.isMakeup) {
                        InfoRow(
                            "说明",
                            "本日执行周${occurrence.date.dayOfWeek.cnLabel().removePrefix("周")}以外" +
                                "（周${occurrence.session.dayOfWeek.cnLabel().removePrefix("周")}）的课表",
                        )
                    }
                    InfoRow("节次", occurrence.unitLabel)
                    occurrence.timeLabel?.let { InfoRow("时间", it) }
                    InfoRow("地点", occurrence.room?.takeIf { it.isNotBlank() } ?: "未排教室")
                }
                VGap(3)
            }

            // ------------------------------------------------- 全部时段
            // Shown for every course, not just multi-slot or 单双周 ones: a course with
            // a single all-term slot still has to say when and where it meets, and the
            // old `size > 1 || parityLabel != null` gate hid the section for exactly
            // that (the most common) case.
            Text("全部时段", style = MaterialTheme.typography.titleMedium, color = gh.fgDefault)
            VGap()
            GitHubCard {
                val ordered = sessions.sortedWith(
                    compareBy({ it.dayOfWeek.value }, { it.startUnit }),
                )
                if (ordered.isEmpty()) {
                    MutedText("暂无排课时段信息")
                } else {
                    ordered.forEachIndexed { index, session ->
                        if (index > 0) GitHubDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${session.dayOfWeek.cnLabel()} ${session.startUnit}-${session.endUnit} 节",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = gh.fgDefault,
                                )
                                Text(
                                    session.weeks.display() +
                                        (session.room?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = gh.fgMuted,
                                )
                            }
                            session.weeks.parityLabel?.let { NeutralBadge(it) }
                        }
                    }
                }
            }
            VGap(3)

            // ------------------------------------------------- 课程信息
            Text("课程信息", style = MaterialTheme.typography.titleMedium, color = gh.fgDefault)
            VGap()
            GitHubCard {
                InfoRow("课程代码", course.courseCode ?: "—")
                InfoRow("教学班", listOfNotNull(course.classCode, course.className).joinToString(" ").ifBlank { "—" })
                course.credits?.let { InfoRow("学分", trimNumber(it)) }
                course.department?.let { InfoRow("开课学院", it) }
                course.assessmentMode?.let { InfoRow("考核方式", it) }
                course.teachingWay?.let { InfoRow("授课方式", it) }
            }

            VGap(3)

            // --------------------------------------------------- 配色
            Text("配色", style = MaterialTheme.typography.titleMedium, color = gh.fgDefault)
            VGap()
            GitHubCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CourseHues.forEachIndexed { index, candidate ->
                        val selected = course.effectiveColorIndex % CourseHues.size == index &&
                            course.colorIndex != null
                        Swatch(
                            color = candidate.container(gh.isDark),
                            selected = selected,
                            onClick = { onSetColor(index) },
                        )
                    }
                }
                VGap()
                GitHubSecondaryButton(
                    text = "按课程名自动配色",
                    enabled = course.colorIndex != null,
                    onClick = { onSetColor(null) },
                )
            }

            VGap(3)

            // --------------------------------------------------- 备注
            Text("我的备注", style = MaterialTheme.typography.titleMedium, color = gh.fgDefault)
            VGap()
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("例如：第 3 周交实验报告", style = MaterialTheme.typography.bodySmall) },
                minLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = gh.fgDefault,
                    unfocusedTextColor = gh.fgDefault,
                    focusedBorderColor = gh.accentFg,
                    unfocusedBorderColor = gh.borderDefault,
                    cursorColor = gh.accentFg,
                ),
            )
            if (noteDirty) {
                VGap()
                GitHubSecondaryButton(text = "保存备注", onClick = { onSetNote(note.ifBlank { null }) })
            }

            VGap(3)

            // --------------------------------------------------- 操作
            GitHubDivider()
            VGap()
            GitHubSecondaryButton(
                text = if (course.hidden) "恢复显示这门课" else "隐藏这门课",
                onClick = {
                    onSetHidden(!course.hidden)
                    // Hiding drops the course from the grid, so the sheet has nothing left to
                    // describe and closes. Straight through `dismiss`, with no suspension in the
                    // path: the parent clears its selection and this composable — dialog window
                    // and all — goes away with it.
                    if (!course.hidden) dismiss()
                },
            )
            VGap()
            MutedText(
                "隐藏只是不在课表里显示，课程仍保留在本机，因此下次从教务导入时不会被重新加回来。" +
                    "想彻底删除请使用「重新导入」。",
            )
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val gh = LocalGitHubColors.current
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) Modifier.border(2.dp, gh.accentFg, CircleShape) else Modifier,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    val gh = LocalGitHubColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = gh.fgMuted,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = gh.fgDefault,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Renders `2.0` as `2` but keeps `2.5` intact — 学分 are usually whole. */
private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
