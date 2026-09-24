package com.ranorac.tjtimetable.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ranorac.tjtimetable.R
import com.ranorac.tjtimetable.ui.components.GitHubCard
import com.ranorac.tjtimetable.ui.components.PageHeader
import com.ranorac.tjtimetable.ui.components.SCREEN_HORIZONTAL_PADDING
import com.ranorac.tjtimetable.ui.components.SECTION_GAP
import com.ranorac.tjtimetable.ui.components.pageContentWidth
import com.ranorac.tjtimetable.ui.theme.LocalGitHubColors
import kotlinx.coroutines.launch

/**
 * The 导航 page: a vertical list of 常用网址, one card per site.
 *
 * Each card is a single row — name and URL on the left, one icon button per way of opening
 * it on the right — so the page stays scannable. Tapping the **name** opens the link with
 * that site's default method; the icons are the explicit per-method routes.
 *
 * @param onOpen performs the launch. Injected rather than called directly so the screen
 *   renders (and is asserted on) without a device.
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
        // PageHeader owns the status-bar inset and MainActivity's bottom bar owns the
        // navigation inset, so Scaffold's default would add the latter twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // Identical component, height and inset as the other pages.
            PageHeader(title = "导航", subtitle = "常用网址")
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.pageContentWidth().fillMaxHeight(),
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
}

@Composable
private fun NavLinkCard(link: NavLink, onOpen: (NavOpenMode) -> Unit) {
    val gh = LocalGitHubColors.current

    // The whole card is the default-open target.
    //
    // The press animation has to be applied HERE, through `modifier`, rather than via
    // GitHubCard(onClick = ...): that path puts `.scale()` after `.background()`, so only
    // the card's *contents* shrink while the card surface stays put — at 0.985 that is
    // invisible. Passing the scale as the outermost modifier wraps the background and
    // border too, so the entire card visibly presses in.
    //
    // The icon buttons are children, so they still win the gesture where they sit and
    // tapping an icon never falls through to the card.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "navCardPress",
    )

    GitHubCard(
        modifier = Modifier
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null) {
                onOpen(link.defaultMode)
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = link.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = gh.fgDefault,
                )
                Text(
                    // A long URL would otherwise push the icons off the card, so it gets
                    // exactly one line and an ellipsis.
                    text = link.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = gh.fgMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                link.actions().forEach { action ->
                    NavModeIconButton(
                        mode = action.mode,
                        contentDescription = action.label,
                        // The default method is tinted, not labelled — the earlier
                        // 「默认 X」 badge is gone, but the shortcut stays discoverable.
                        emphasized = action.isDefault,
                        onClick = { onOpen(action.mode) },
                    )
                }
            }
        }
    }
}

/** One way of opening the link, as a bordered square icon button. */
@Composable
private fun NavModeIconButton(
    mode: NavOpenMode,
    contentDescription: String,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    val gh = LocalGitHubColors.current
    val tint = if (emphasized) Color.White else gh.fgDefault
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (emphasized) gh.accentFg else gh.canvasSubtle)
            .border(
                width = 1.dp,
                color = if (emphasized) gh.accentFg else gh.borderDefault,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (mode) {
            // WeChat and WeCom get their own brand marks: a generic speech bubble does not
            // tell the student which app is behind the button.
            NavOpenMode.WECHAT -> Icon(
                painter = painterResource(R.drawable.ic_brand_wechat),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            NavOpenMode.WECOM -> Icon(
                painter = painterResource(R.drawable.ic_brand_wecom),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            NavOpenMode.BROWSER -> Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            NavOpenMode.EXTERNAL_APP -> Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
