package com.ranorac.tjtimetable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ranorac.tjtimetable.ui.theme.LocalGitHubColors

/**
 * Height of the bottom bar's content row, excluding the system navigation inset.
 *
 * Every destination renders exactly ONE line — the current tab shows its name, the others
 * show only their icon — so the bar is a single row of content rather than Material3
 * `NavigationBar`'s icon-over-label stack (80dp). 44dp leaves the icon room to breathe
 * without putting the two-line layout back; it is also within 4dp of Android's 48dp
 * minimum touch target, which is as close as a single-line bar can reasonably get.
 *
 * Exposed so the render test can pin the value rather than restate it.
 */
val BOTTOM_BAR_HEIGHT: Dp = 44.dp

/** Test handle for the bar itself, so its height can be asserted rather than assumed. */
const val BOTTOM_BAR_TAG = "appBottomBar"

/** One destination in the bottom bar. */
private data class BottomTab(val label: String, val icon: ImageVector)

private val BOTTOM_TABS = listOf(
    BottomTab("课表", Icons.Filled.CalendarMonth),
    BottomTab("调休", Icons.AutoMirrored.Filled.EventNote),
    BottomTab("导航", Icons.Filled.Explore),
    BottomTab("设置", Icons.Filled.Settings),
)

/**
 * The app's four-destination bottom bar: one line per tab, so it stays short.
 *
 * The current destination is shown as its **name**, the others as their **icon**. That is
 * what lets one row carry both pieces of information — a bar that shows an icon *and* a
 * label for the current tab needs two rows, and two rows is exactly the height this bar
 * exists to give back to the timetable. Nothing is hidden: every destination is still on
 * screen, and the icon is the same one the tab shows when it is not current.
 *
 * Written by hand instead of using `NavigationBar` because the Material3 component's
 * height is a token, not a parameter. Replacing it also keeps the tab semantics explicit
 * (`selectable` + [Role.Tab]) rather than implied.
 */
@Composable
fun AppBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val gh = LocalGitHubColors.current
    Column(Modifier.fillMaxWidth().background(gh.canvasSubtle)) {
        // A hairline keeps a thin bar from melting into the canvas behind it.
        Box(Modifier.fillMaxWidth().height(1.dp).background(gh.borderMuted))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(BOTTOM_BAR_TAG)
                // Padding is applied BEFORE the height, so the fixed 36dp is the content
                // row and the system navigation inset is added on the outside. The other
                // order would let the inset eat into the content and clip the labels on
                // three-button navigation devices.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(BOTTOM_BAR_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BOTTOM_TABS.forEachIndexed { index, tab ->
                BottomBarItem(
                    tab = tab,
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gh = LocalGitHubColors.current
    Column(
        modifier = modifier
            .fillMaxHeight()
            // `selectable` (rather than `clickable`) is what tells TalkBack these are tabs
            // and which one is current — the semantics Material3's own bar provided.
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (selected) {
            // The name, at the label size the two-line version already used: the point of
            // the redesign is to save the icon's row, not to shrink the text.
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = gh.accentFg,
                maxLines = 1,
            )
        } else {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = gh.fgMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
