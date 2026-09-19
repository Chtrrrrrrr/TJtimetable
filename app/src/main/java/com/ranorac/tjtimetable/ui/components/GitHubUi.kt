package com.ranorac.tjtimetable.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ranorac.tjtimetable.ui.theme.LocalGitHubColors

/**
 * GitHub-flavoured UI primitives.
 *
 * These exist so screens describe *what* they show rather than re-deriving
 * Primer's border/radius/muted-foreground conventions each time. Every
 * interactive surface shares one press animation, so the app feels uniform.
 */

/** A bordered card on the subtle canvas, GitHub's default container. */
@Composable
fun GitHubCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val gh = LocalGitHubColors.current
    val shape = RoundedCornerShape(8.dp)
    var box = modifier
        .clip(shape)
        .background(gh.canvasSubtle)
        .border(1.dp, gh.borderDefault, shape)

    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        // A subtle scale rather than a ripple: closer to GitHub's own feel, and
        // it costs one float animation instead of an ink splash layer.
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.985f else 1f,
            animationSpec = tween(durationMillis = 90),
            label = "cardPress",
        )
        box = box
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    }

    Column(modifier = box.padding(contentPadding), content = content)
}

/** GitHub's issue-label pill. */
@Composable
fun GitHubBadge(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
    }
}

/** Semantic badges bound to the Primer token set. */
@Composable
fun AccentBadge(text: String, modifier: Modifier = Modifier) {
    val gh = LocalGitHubColors.current
    GitHubBadge(text, gh.accentSubtle, gh.accentFg, modifier)
}

@Composable
fun NeutralBadge(text: String, modifier: Modifier = Modifier) {
    val gh = LocalGitHubColors.current
    GitHubBadge(text, gh.neutralSubtle, gh.fgMuted, modifier)
}

@Composable
fun AttentionBadge(text: String, modifier: Modifier = Modifier) {
    val gh = LocalGitHubColors.current
    GitHubBadge(text, gh.attentionSubtle, gh.attentionFg, modifier)
}

/** Section heading with Primer's muted, small-caps-ish treatment. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val gh = LocalGitHubColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = gh.fgDefault,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/**
 * A segmented control, the GitHub "Boxed" toggle.
 *
 * @param options labels in display order.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val gh = LocalGitHubColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(gh.canvasSubtle)
            .border(1.dp, gh.borderDefault, RoundedCornerShape(6.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) gh.canvasDefault else Color.Transparent,
                animationSpec = tween(160),
                label = "segmentBg",
            )
            val foreground by animateColorAsState(
                targetValue = if (isSelected) gh.fgDefault else gh.fgMuted,
                animationSpec = tween(160),
                label = "segmentFg",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(background)
                    .then(
                        if (isSelected) {
                            Modifier.border(1.dp, gh.borderDefault, RoundedCornerShape(4.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = foreground,
                )
            }
        }
    }
}

/** Primary action button in Primer's green/blue emphasis style. */
@Composable
fun GitHubPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val gh = LocalGitHubColors.current
    val background = if (enabled) gh.successFg else gh.neutralSubtle
    val foreground = if (enabled) Color.White else gh.fgSubtle
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
        )
    }
}

/** Secondary button: bordered, on canvas. */
@Composable
fun GitHubSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val gh = LocalGitHubColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(gh.canvasSubtle)
            .border(1.dp, gh.borderDefault, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) gh.fgDefault else gh.fgSubtle,
        )
    }
}

/** A labelled divider row, used between settings groups. */
@Composable
fun GitHubDivider(modifier: Modifier = Modifier) {
    val gh = LocalGitHubColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(gh.borderMuted),
    )
}

/** Small muted caption. */
@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalGitHubColors.current.fgMuted,
        modifier = modifier,
    )
}

/** Spacer using Primer's 8px rhythm. */
@Composable
fun VGap(multiplier: Int = 1) {
    Spacer(Modifier.height((8 * multiplier).dp))
}

@Composable
fun HGap(multiplier: Int = 1) {
    Spacer(Modifier.width((8 * multiplier).dp))
}

/** Row wrapper with an optional label on the left and content on the right. */
@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    val gh = LocalGitHubColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = gh.fgDefault)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = gh.fgMuted)
            }
        }
        HGap()
        content()
    }
}

/**
 * One full-width, tappable choice inside a dialog.
 *
 * Shared by the 调休 day editor and the calendar chooser: both present a list of mutually
 * exclusive options, and both want a full-width tap target whose selected row is
 * unmistakable.
 */
@Composable
fun SelectableRow(
    text: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val gh = LocalGitHubColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) gh.accentSubtle.copy(alpha = 0.5f) else Color.Transparent)
            .then(
                if (selected) Modifier.border(1.dp, gh.accentFg, RoundedCornerShape(6.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) gh.accentFg else gh.fgDefault,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = gh.fgMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The app's one text input, bound to the Primer tokens.
 *
 * Shared rather than duplicated: 设置 collects credentials and pasted responses while
 * 调休 collects the 教务处 notice, and a field whose border or label colour drifted
 * between those two screens is exactly the inconsistency this file exists to prevent.
 */
@Composable
fun GitHubTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val gh = LocalGitHubColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = gh.fgDefault,
            unfocusedTextColor = gh.fgDefault,
            focusedBorderColor = gh.accentFg,
            unfocusedBorderColor = gh.borderDefault,
            focusedLabelColor = gh.accentFg,
            unfocusedLabelColor = gh.fgMuted,
            cursorColor = gh.accentFg,
        ),
    )
}
