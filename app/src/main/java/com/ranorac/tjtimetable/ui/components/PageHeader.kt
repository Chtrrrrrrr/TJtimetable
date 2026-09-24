package com.ranorac.tjtimetable.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
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
 * Widest a page's *content* is allowed to get before it simply stops growing.
 *
 * Without this a 设置 card on a tablet stretches the full width of the screen: the text
 * inside then runs to 100+ characters per line, which is far past the comfortable reading
 * measure, and the buttons sit marooned at the far left of an enormous empty card. The
 * cap plus centring keeps every page looking like the phone layout, just with margins.
 *
 * 640dp is wide enough that a landscape phone and a small tablet are unaffected — it only
 * starts to matter on a large tablet, which is exactly where the bug was reported.
 */
val SCREEN_MAX_CONTENT_WIDTH: Dp = 640.dp

/**
 * Constrains page content to [SCREEN_MAX_CONTENT_WIDTH], filling the parent when narrower.
 *
 * Order matters: `widthIn` must come first so it tightens the incoming constraints, and
 * `fillMaxWidth` then fills whatever survived (the cap, or the parent). The other order
 * would ask for the full parent width first and the cap would lose.
 */
fun Modifier.pageContentWidth(): Modifier =
    this.widthIn(max = SCREEN_MAX_CONTENT_WIDTH).fillMaxWidth()

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
        // The inner row is capped (and therefore narrower than the screen on a tablet);
        // centring here keeps the title on the same left edge as the centred page content
        // below it, instead of stranding it against the screen edge.
        horizontalArrangement = Arrangement.Center,
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
