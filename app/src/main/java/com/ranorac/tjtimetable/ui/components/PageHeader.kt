package com.ranorac.tjtimetable.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ranorac.tjtimetable.ui.theme.LocalGitHubColors

/**
 * The horizontal inset every screen's content uses.
 *
 * One constant rather than a literal per screen: the three pages are switched between
 * dozens of times a minute, and a title that starts at 4dp on one page and 16dp on the
 * next reads as a layout bug. The timetable *grid* is the deliberate exception — it is a
 * data table and needs the full width — but its header is not.
 */
val SCREEN_HORIZONTAL_PADDING: Dp = 16.dp

/**
 * Vertical gap between two sections/cards.
 *
 * 设置 used 24dp between sections and 调休 12dp; both now use this, so the two list pages
 * scroll with the same rhythm.
 */
val SECTION_GAP: Dp = 16.dp

/** Height of a page header's content, excluding the status-bar inset. */
val PAGE_HEADER_HEIGHT: Dp = 64.dp

/**
 * Lets page content use the width it is given.
 *
 * This used to cap content at 640dp, which was simply wrong: it did not make the layout
 * adaptive, it made it *narrow* — on a tablet the content sat in a phone-width column with
 * dead space either side. Content is meant to grow with the screen.
 */
fun Modifier.pageContentWidth(): Modifier = this.fillMaxWidth()

/**
 * The header every page opens with: a title, an optional subtitle, and optional actions.
 *
 * All three screens used to build their own — 设置 and 调休 with a Material3 `TopAppBar`,
 * the timetable with a hand-rolled week row at a different height, padding and type size.
 * Switching tabs therefore moved the title up or down by ~10dp and in or out by 12dp, which
 * is exactly the "拼在一起" feeling it produced. One composable means the title of every
 * page sits at the same place on the same baseline, and a change to it lands everywhere.
 *
 * The status-bar inset lives in here because these headers are used as a `Scaffold`
 * `topBar` on two of the three screens; when an ancestor has already consumed it (the
 * timetable wraps its whole screen in `statusBarsPadding()`), it resolves to zero and does
 * not double up.
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val gh = LocalGitHubColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // Inset before height: the 64dp is the content, the bar sits above it.
            .heightIn(min = PAGE_HEADER_HEIGHT)
            .padding(horizontal = SCREEN_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            modifier = Modifier.pageContentWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = gh.fgDefault,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = gh.fgMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions?.invoke(this)
        }
    }
}
