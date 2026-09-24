package com.ranorac.tjtimetable.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ranorac.tjtimetable.ui.components.AccentBadge
import com.ranorac.tjtimetable.ui.components.GitHubCard
import com.ranorac.tjtimetable.ui.components.GitHubPrimaryButton
import com.ranorac.tjtimetable.ui.components.GitHubSecondaryButton
import com.ranorac.tjtimetable.ui.components.PageHeader
import com.ranorac.tjtimetable.ui.components.SCREEN_HORIZONTAL_PADDING
import com.ranorac.tjtimetable.ui.components.SECTION_GAP
import com.ranorac.tjtimetable.ui.components.VGap
import com.ranorac.tjtimetable.ui.theme.LocalGitHubColors
import kotlinx.coroutines.launch

/**
 * The 导航 page: a vertical list of 常用网址, one card per site.
 *
 * Every card renders a button for **every** way it can be opened — the default one first
 * and emphasised — so the choice is always the student's. Nothing here decides silently.
 *
 * @param onOpen performs the launch. Injected rather than called directly so the screen
 *   renders (and is asserted on) without a device, and so the page never touches
 *   `Context` itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    onOpen: (NavLink, NavOpenMode) -> NavResult,
    links: List<NavLink> = NavLinks.ALL,
) {
    val gh = LocalGitHubColors.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = gh.canvasDefault,
        // Same reason as 设置/调休: PageHeader owns the status-bar inset and MainActivity's
        // bottom bar owns the navigation inset, so Scaffold's default would add the
        // navigation bar's height a second time as a dead band.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // Identical component, height and inset as the other three pages.
            PageHeader(title = "导航", subtitle = "常用网址")
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = SCREEN_HORIZONTAL_PADDING,
                end = SCREEN_HORIZONTAL_PADDING,
                top = 8.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            items(links, key = { it.id }) { link ->
                NavLinkCard(
                    link = link,
                    onOpen = { mode ->
                        val result = onOpen(link, mode)
                        result.messageFor(link, mode)?.let { text ->
                            scope.launch { snackbar.showSnackbar(text) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NavLinkCard(link: NavLink, onOpen: (NavOpenMode) -> Unit) {
    val gh = LocalGitHubColors.current
    val actions = link.actions()

    GitHubCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    link.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = gh.fgDefault,
                )
                Text(
                    link.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = gh.fgMuted,
                )
            }
            AccentBadge("默认 ${link.defaultMode.shortLabel(link)}")
        }

        VGap()

        // One row of equally weighted buttons: every supported mode, default first
        // (NavLinks lists it first) and drawn as the primary action.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEach { action ->
                Box(Modifier.weight(1f)) {
                    if (action.isDefault) {
                        GitHubPrimaryButton(
                            text = action.label,
                            onClick = { onOpen(action.mode) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        GitHubSecondaryButton(
                            text = action.label,
                            onClick = { onOpen(action.mode) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
