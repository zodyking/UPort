package app.apt443.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import app.apt443.domain.Availability
import app.apt443.domain.ConnectionMode
import app.apt443.domain.explain
import app.apt443.domain.label
import app.apt443.ui.player.StreamSpinner
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptMotion
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSize
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import app.apt443.ui.theme.LocalReducedMotion
import app.apt443.ui.theme.aptTween
import java.text.DateFormat
import java.util.Date

enum class AptButtonStyle {
    Primary,
    Secondary,
    Ghost,
    Danger,

    /** Legacy alias for [Danger]. */
    Critical,
}

enum class StatusTone {
    Online,
    Offline,
    Warning,
    Danger,
    Live,
    Info,
    Neutral,
}

fun StatusTone.color(): Color = when (this) {
    StatusTone.Online -> AptColors.Online
    StatusTone.Offline -> AptColors.Offline
    StatusTone.Warning -> AptColors.Warning
    StatusTone.Danger -> AptColors.Danger
    StatusTone.Live -> AptColors.Live
    StatusTone.Info -> AptColors.Primary
    StatusTone.Neutral -> AptColors.Secondary
}

// ---------------------------------------------------------------------------------------------
// Focus treatment
// ---------------------------------------------------------------------------------------------

/**
 * Shared focus look: animated scale, bright ring, and a soft lift. Place it before `clickable`
 * and after `onFocusChanged` so the whole surface scales. Snaps when animations are off.
 */
fun Modifier.aptFocusFrame(
    focused: Boolean,
    shape: Shape,
    scale: Boolean = true,
    idleBorder: Color = Color.Transparent,
    focusBorder: Color = AptColors.FocusRing,
    borderWidth: Dp = AptSize.focusBorder,
    scaleTarget: Float = AptMotion.focusScale,
    elevation: Dp = AptSize.focusElevation,
): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val factor by animateFloatAsState(
        targetValue = if (focused && scale) scaleTarget else 1f,
        animationSpec = aptTween(AptMotion.normal, reduced),
        label = "focus-scale",
    )
    val lift by animateDpAsState(
        targetValue = if (focused) elevation else 0.dp,
        animationSpec = aptTween(AptMotion.normal, reduced),
        label = "focus-elevation",
    )
    val ring by animateColorAsState(
        targetValue = if (focused) focusBorder else idleBorder,
        animationSpec = aptTween(AptMotion.fast, reduced),
        label = "focus-ring",
    )
    this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = factor
            scaleY = factor
        }
        .shadow(elevation = lift, shape = shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .border(
            width = if (focused) borderWidth else 1.dp,
            color = ring,
            shape = shape,
        )
}

@Composable
private fun animatedFill(target: Color): Color {
    val reduced = LocalReducedMotion.current
    val color by animateColorAsState(target, aptTween(AptMotion.fast, reduced), label = "fill")
    return color
}

// ---------------------------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------------------------

private fun buttonFill(style: AptButtonStyle, focused: Boolean): Color {
    val danger = style == AptButtonStyle.Danger || style == AptButtonStyle.Critical
    return when {
        style == AptButtonStyle.Primary -> if (focused) AptColors.PrimaryFocused else AptColors.Primary
        danger -> if (focused) AptColors.DangerFocused else AptColors.Danger
        style == AptButtonStyle.Ghost -> if (focused) AptColors.GlassFocused else Color.Transparent
        else -> if (focused) AptColors.GlassFocused else AptColors.GlassRaised
    }
}

private fun buttonContent(style: AptButtonStyle, focused: Boolean): Color {
    val danger = style == AptButtonStyle.Danger || style == AptButtonStyle.Critical
    return when {
        style == AptButtonStyle.Primary -> AptColors.OnPrimary
        danger -> Color.White
        style == AptButtonStyle.Ghost -> if (focused) AptColors.OnBackground else AptColors.Secondary
        else -> AptColors.OnBackground
    }
}

@Composable
fun AptButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AptButtonStyle = AptButtonStyle.Secondary,
    enabled: Boolean = true,
    icon: AptIconKind? = null,
    compact: Boolean = false,
    fontSize: TextUnit = AptType.body,
    onFocused: () -> Unit = {},
    focusScale: Float = AptMotion.focusScaleControl,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(AptRadius.button)
    val fill = animatedFill(buttonFill(style, focused))
    val content = buttonContent(style, focused)
    val idleBorder = if (style == AptButtonStyle.Secondary) AptColors.Hairline else Color.Transparent
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = modifier
            .defaultMinSize(
                minWidth = if (compact) AptSize.controlSmall else 104.dp,
                minHeight = if (compact) AptSize.controlSmall else AptSize.control,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .aptFocusFrame(focused = focused, shape = shape, idleBorder = idleBorder, scaleTarget = focusScale)
            .clip(shape)
            .background(fill.copy(alpha = fill.alpha * alpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = if (compact) AptSpace.sm else 20.dp, vertical = AptSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AptSpace.xs, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            AptIcon(icon, modifier = Modifier.size(AptSize.iconSmall), tint = content.copy(alpha = alpha))
        }
        Text(
            text = text,
            color = content.copy(alpha = alpha),
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Round icon-only button for toolbars and HUDs. */
@Composable
fun AptIconButton(
    icon: AptIconKind,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: AptButtonStyle = AptButtonStyle.Secondary,
    size: Dp = AptSize.control,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = CircleShape
    val fill = animatedFill(buttonFill(style, focused))
    val danger = style == AptButtonStyle.Danger || style == AptButtonStyle.Critical
    val tint = when {
        danger -> Color.White
        style == AptButtonStyle.Primary -> AptColors.OnPrimary
        else -> AptColors.OnBackground
    }
    Box(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .aptFocusFrame(
                focused = focused,
                shape = shape,
                idleBorder = if (style == AptButtonStyle.Secondary) AptColors.Hairline else Color.Transparent,
                scaleTarget = AptMotion.focusScaleControl,
            )
            .clip(shape)
            .background(if (enabled) fill else fill.copy(alpha = fill.alpha * 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AptIcon(icon, modifier = Modifier.size(size * 0.44f), tint = if (enabled) tint else tint.copy(alpha = 0.4f))
    }
}

// ---------------------------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------------------------

/** Focusable, clickable panel. Tiles and rows build on this so every target looks the same. */
@Composable
fun AptFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = AptRadius.card,
    onFocused: () -> Unit = {},
    selected: Boolean = false,
    enabled: Boolean = true,
    scale: Boolean = true,
    fill: Color = AptColors.Surface,
    focusScale: Float = AptMotion.focusScale,
    focusedFill: Color? = null,
    idleBorder: Color = AptColors.Hairline,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(corner)
    val background = animatedFill(
        when {
            focused && focusedFill != null -> focusedFill
            selected && !focused -> AptColors.SurfaceRaised
            else -> fill
        },
    )
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .aptFocusFrame(
                focused = focused,
                shape = shape,
                scale = scale,
                idleBorder = if (selected) AptColors.Primary.copy(alpha = 0.6f) else idleBorder,
                scaleTarget = focusScale,
            )
            .clip(shape)
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        content = content,
    )
}

fun Modifier.aptPanel(fill: Color = AptColors.Surface, corner: Dp = AptRadius.card): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .border(1.dp, AptColors.Hairline, shape)
        .clip(shape)
        .background(fill)
}

/** Translucent raised panel for content sitting on the page canvas. Not for use over video. */
fun Modifier.aptGlass(corner: Dp = AptRadius.card, fill: Color = AptColors.Glass): Modifier =
    aptPanel(fill = fill, corner = corner)

/** Static card with an optional header row. Not focusable; put controls inside. */
@Composable
fun AptCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(AptSpace.lg),
    fill: Color = AptColors.Surface,
    corner: Dp = AptRadius.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.aptPanel(fill = fill, corner = corner).padding(padding),
        verticalArrangement = Arrangement.spacedBy(AptSpace.sm),
    ) {
        if (title != null || trailing != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AptSpace.xxs)) {
                    if (title != null) {
                        Text(
                            title,
                            fontSize = AptType.heading,
                            fontWeight = FontWeight.SemiBold,
                            color = AptColors.OnBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            fontSize = AptType.label,
                            color = AptColors.Secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                trailing?.invoke(this)
            }
        }
        content()
    }
}

/**
 * Hero block at the top of a dashboard page: small-caps eyebrow, status badges, a title,
 * a row of metrics, and an optional primary action on the right. Decorative; not focusable.
 */
@Composable
fun AptHero(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    badges: @Composable RowScope.() -> Unit = {},
    metrics: @Composable RowScope.() -> Unit = {},
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .aptGlass(corner = AptRadius.sheet, fill = AptColors.GlassRaised)
            .padding(horizontal = AptSpace.xl, vertical = AptSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AptSpace.xl),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
            ) {
                Text(
                    eyebrow.uppercase(),
                    fontSize = AptType.caption,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = AptType.capsTracking,
                    color = AptColors.Tertiary,
                )
                badges()
            }
            Text(
                text = title,
                fontSize = AptType.title,
                fontWeight = FontWeight.SemiBold,
                color = AptColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = AptSpace.xxs),
                horizontalArrangement = Arrangement.spacedBy(AptSpace.xl),
                content = metrics,
            )
        }
        action?.invoke()
    }
}

/** One number with a caption, for hero metric rows. */
@Composable
fun AptMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, fontSize = AptType.heading, fontWeight = FontWeight.SemiBold, color = AptColors.OnBackground, maxLines = 1)
        Text(
            label.uppercase(),
            fontSize = AptType.caption,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            color = AptColors.Tertiary,
            maxLines = 1,
        )
    }
}

/**
 * Focusable list row: optional leading icon, a title over a meta line, and a trailing slot.
 * Rows without an action still take focus so a remote can scroll through them.
 */
@Composable
fun AptListRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    icon: AptIconKind? = null,
    iconTint: Color = AptColors.Secondary,
    onClick: () -> Unit = {},
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    AptFocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        corner = AptRadius.row,
        fill = AptColors.Glass,
        focusedFill = AptColors.GlassFocused,
        focusScale = AptMotion.focusScaleRow,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = AptSpace.lg, vertical = AptSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AptSpace.md),
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier.size(36.dp).background(AptColors.GlassRaised, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AptIcon(icon, modifier = Modifier.size(AptSize.iconSmall + 2.dp), tint = iconTint)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    fontSize = AptType.body,
                    fontWeight = FontWeight.Medium,
                    color = AptColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!meta.isNullOrBlank()) {
                    Text(
                        meta,
                        fontSize = AptType.label,
                        color = AptColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
        }
    }
}

/** Quiet, non-focusable placeholder for an empty section inside a list. */
@Composable
fun InlineEmpty(text: String, modifier: Modifier = Modifier, icon: AptIconKind = AptIconKind.Info) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .aptGlass(corner = AptRadius.row)
            .padding(horizontal = AptSpace.lg, vertical = AptSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
    ) {
        AptIcon(icon, modifier = Modifier.size(AptSize.iconSmall), tint = AptColors.Tertiary)
        Text(text, fontSize = AptType.body, color = AptColors.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------------
// Headers and labels
// ---------------------------------------------------------------------------------------------

@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    divider: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AptSpace.lg),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AptSpace.xxs)) {
                if (!eyebrow.isNullOrBlank()) {
                    Text(
                        text = eyebrow.uppercase(),
                        fontSize = AptType.caption,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = AptType.capsTracking,
                        color = AptColors.Tertiary,
                        maxLines = 1,
                    )
                }
                Text(
                    text = title,
                    fontSize = AptType.display,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    color = AptColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = AptType.body,
                        color = AptColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
                content = trailing,
            )
        }
        if (divider) {
            Spacer(modifier = Modifier.height(AptSpace.sm))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AptColors.Hairline))
        }
    }
}

@Composable
fun ScreenTitle(title: String, detail: String) {
    PageHeader(title = title, subtitle = detail)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(top = AptSpace.md, bottom = AptSpace.xxs),
        fontSize = AptType.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = AptType.capsTracking,
        color = AptColors.Tertiary,
    )
}

/** Label above a control inside a settings section. */
@Composable
fun ControlCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(top = AptSpace.xs),
        fontSize = AptType.label,
        fontWeight = FontWeight.SemiBold,
        color = AptColors.Secondary,
    )
}

/** Titled block used on Settings pages: heading, helper text, then controls. */
@Composable
fun SettingsSection(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
        AptFocusSurface(
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            corner = AptRadius.row,
            scale = false,
            fill = Color.Transparent,
            focusedFill = AptColors.GlassFocused,
            focusScale = AptMotion.focusScaleRow,
            idleBorder = Color.Transparent,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AptSpace.sm, vertical = AptSpace.xs),
                verticalArrangement = Arrangement.spacedBy(AptSpace.xxs),
            ) {
                Text(
                    title,
                    fontSize = AptType.heading,
                    fontWeight = FontWeight.SemiBold,
                    color = AptColors.OnBackground,
                )
                if (!description.isNullOrBlank()) {
                    Text(description, fontSize = AptType.body, color = AptColors.Secondary)
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().aptPanel().padding(AptSpace.lg),
            verticalArrangement = Arrangement.spacedBy(AptSpace.md),
            content = content,
        )
    }
}

/** Read-only settings block the remote can land on, so text scrolls into view. */
@Composable
fun SettingsReadRow(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    AptFocusSurface(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        corner = AptRadius.row,
        scale = false,
        fill = AptColors.Glass,
        focusedFill = AptColors.GlassFocused,
        focusScale = AptMotion.focusScaleRow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AptSpace.lg, vertical = AptSpace.sm),
            verticalArrangement = Arrangement.spacedBy(AptSpace.xxs),
        ) {
            if (title.isNotBlank()) {
                Text(
                    title,
                    fontSize = AptType.label,
                    fontWeight = FontWeight.SemiBold,
                    color = AptColors.Secondary,
                )
            }
            Text(
                body,
                fontSize = AptType.body,
                color = AptColors.OnBackground,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------------------------

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier = modifier.size(size).background(color, CircleShape))
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Row(
        modifier = modifier
            .background(
                if (filled) color else color.copy(alpha = 0.16f),
                RoundedCornerShape(AptRadius.chip),
            )
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color = if (filled) Color.White else color, size = 6.dp)
        Text(
            text = text,
            color = if (filled) Color.White else color,
            fontSize = AptType.caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun StatusPill(text: String, tone: StatusTone, modifier: Modifier = Modifier, filled: Boolean = false) {
    StatusPill(text = text, color = tone.color(), modifier = modifier, filled = filled)
}

@Composable
fun ModeBadge(mode: ConnectionMode) {
    val tone = when (mode) {
        ConnectionMode.OnNetwork -> StatusTone.Online
        ConnectionMode.Remote -> StatusTone.Warning
        ConnectionMode.Demo -> StatusTone.Info
        ConnectionMode.Unconfigured -> StatusTone.Danger
    }
    StatusPill(mode.label(), tone)
}

@Composable
fun AvailabilityLine(availability: Availability) {
    val text = availability.explain()
    if (text.isBlank()) return
    InlineMessage(text, StatusTone.Warning)
}

/** One-line status with a leading dot. Use for save confirmations and soft warnings. */
@Composable
fun InlineMessage(text: String, tone: StatusTone = StatusTone.Neutral, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
    ) {
        StatusDot(tone.color())
        Text(
            text,
            fontSize = AptType.body,
            color = if (tone == StatusTone.Neutral) AptColors.Secondary else tone.color(),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = AptSize.control).padding(vertical = AptSpace.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, fontSize = AptType.body, color = AptColors.Secondary)
            Text(
                text = value,
                fontSize = AptType.body,
                fontWeight = FontWeight.Medium,
                color = AptColors.OnBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = AptSpace.lg).widthIn(max = 420.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AptColors.Hairline))
    }
}

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

@Composable
fun AptSwitchRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    icon: AptIconKind? = null,
) {
    AptFocusSurface(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        corner = AptRadius.row,
        focusScale = AptMotion.focusScaleRow,
        fill = AptColors.Glass,
        focusedFill = AptColors.GlassFocused,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AptSpace.md + 2.dp, vertical = AptSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(AptSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                AptIcon(icon, modifier = Modifier.size(AptSize.icon), tint = AptColors.Secondary)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label,
                    fontSize = AptType.body,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) AptColors.OnBackground else AptColors.Tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        description,
                        fontSize = AptType.label,
                        color = AptColors.Secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SwitchGlyph(checked = checked, enabled = enabled)
        }
    }
}

@Composable
fun SwitchGlyph(checked: Boolean, enabled: Boolean = true) {
    val reduced = LocalReducedMotion.current
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = aptTween(AptMotion.fast, reduced),
        label = "switch-thumb",
    )
    val track by animateColorAsState(
        targetValue = if (checked) AptColors.Primary else AptColors.HairlineStrong,
        animationSpec = aptTween(AptMotion.fast, reduced),
        label = "switch-track",
    )
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(26.dp)
            .background(track.copy(alpha = track.alpha * if (enabled) 1f else 0.4f), RoundedCornerShape(AptRadius.chip)),
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset, y = 2.dp)
                .size(22.dp)
                .background(Color.White, CircleShape),
        )
    }
}

/** Single-select pill group. Equal-height options inside a sunken track. Wraps instead of running off the screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> AptSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = RoundedCornerShape(AptRadius.chip)
    FlowRow(
        modifier = modifier
            .background(AptColors.GlassSunken, track)
            .border(1.dp, AptColors.Hairline, track)
            .padding(4.dp)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(AptRadius.chip)
            val fill = animatedFill(
                when {
                    isSelected -> AptColors.Selected
                    focused -> AptColors.GlassFocused
                    else -> Color.Transparent
                },
            )
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 88.dp, minHeight = AptSize.controlSmall + 4.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .aptFocusFrame(focused = focused, shape = shape, scaleTarget = AptMotion.focusScaleControl)
                    .clip(shape)
                    .background(fill)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(option) },
                    )
                    .padding(horizontal = AptSpace.md, vertical = AptSpace.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    fontSize = AptType.body,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) AptColors.OnSelected else AptColors.OnBackground,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Toggle chip. Selected chips fill with a light neutral and show a check; focus adds the ring. */
@Composable
fun AptChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: AptIconKind? = null,
    iconOnly: Boolean = false,
    fontSize: TextUnit = AptType.label,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(AptRadius.chip)
    val fill = animatedFill(
        when {
            selected -> AptColors.Selected
            focused -> AptColors.GlassFocused
            else -> AptColors.GlassRaised
        },
    )
    val content = if (selected) AptColors.OnSelected else AptColors.OnBackground
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = modifier
            .defaultMinSize(
                minWidth = if (iconOnly) AptSize.controlSmall + 4.dp else 0.dp,
                minHeight = AptSize.controlSmall + 4.dp,
            )
            .semantics { if (iconOnly) contentDescription = text }
            .onFocusChanged { focused = it.isFocused }
            .aptFocusFrame(
                focused = focused,
                shape = shape,
                idleBorder = if (selected) Color.Transparent else AptColors.Hairline,
                scaleTarget = AptMotion.focusScaleControl,
            )
            .clip(shape)
            .background(fill.copy(alpha = fill.alpha * alpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(
                horizontal = if (iconOnly) AptSpace.sm else AptSpace.md,
                vertical = AptSpace.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        val shownIcon = if (iconOnly) icon else if (selected) AptIconKind.Check else icon
        if (shownIcon != null) {
            AptIcon(shownIcon, modifier = Modifier.size(if (iconOnly) 18.dp else 14.dp), tint = content.copy(alpha = alpha))
        }
        if (!iconOnly) {
            Text(
                text,
                fontSize = fontSize,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = content.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Dropdown
// ---------------------------------------------------------------------------------------------

/**
 * Filter dropdown sized for a remote.
 *
 * The list scrolls, so a console with twenty cameras still works, and the current choice takes
 * focus when the menu opens so the first D-pad press moves from there rather than from the top.
 * [footer] is for actions that are not one of [options], such as opening a custom range.
 */
@Composable
fun AptDropdown(
    title: String,
    value: String,
    options: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    menuWidth: Dp = 340.dp,
    maxVisibleOptions: Int = 7,
    valueWidth: Dp = 240.dp,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val menuOffset = with(LocalDensity.current) { (64.dp + AptSpace.xs).roundToPx() }
    Box(modifier = modifier) {
        AptFocusSurface(
            onClick = onToggle,
            corner = AptRadius.button + 2.dp,
            fill = AptColors.GlassRaised,
            focusedFill = AptColors.GlassFocused,
            focusScale = AptMotion.focusScaleControl,
            selected = expanded,
        ) {
            Row(
                modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = AptSpace.md + 2.dp, vertical = AptSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title.uppercase(),
                        fontSize = AptType.caption,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                        color = AptColors.Tertiary,
                        maxLines = 1,
                    )
                    Text(
                        value,
                        fontSize = AptType.body,
                        fontWeight = FontWeight.Medium,
                        color = AptColors.OnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(min = 96.dp, max = valueWidth),
                    )
                }
                AptIcon(
                    if (expanded) AptIconKind.ArrowUp else AptIconKind.ArrowDown,
                    modifier = Modifier.size(AptSize.iconSmall),
                    tint = AptColors.Secondary,
                )
            }
        }
        if (!expanded) return@Box
        val selectedIndex = options.indexOf(value).coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
        val focusOnSelected = remember { FocusRequester() }
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(0, menuOffset),
            onDismissRequest = onToggle,
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                modifier = Modifier
                    .width(menuWidth)
                    .shadow(24.dp, RoundedCornerShape(AptRadius.sheet), clip = false)
                    .aptPanel(fill = AptColors.SurfaceOverlay, corner = AptRadius.sheet)
                    .padding(AptSpace.sm),
                verticalArrangement = Arrangement.spacedBy(AptSpace.xxs),
            ) {
                SectionLabel(title, modifier = Modifier.padding(horizontal = AptSpace.sm))
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .heightIn(max = AptSize.menuRow * maxVisibleOptions)
                        .focusRestorer()
                        .focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(AptSpace.xxs),
                ) {
                    itemsIndexed(options, key = { index, option -> "$index:$option" }) { index, option ->
                        val selected = option == value
                        AptFocusSurface(
                            onClick = { onSelect(option) },
                            modifier = if (index == selectedIndex) Modifier.focusRequester(focusOnSelected) else Modifier,
                            scale = false,
                            corner = AptRadius.button,
                            fill = Color.Transparent,
                            focusedFill = AptColors.GlassFocused,
                            idleBorder = Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = AptSize.menuRow)
                                    .padding(horizontal = AptSpace.md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
                            ) {
                                Text(
                                    text = option,
                                    modifier = Modifier.weight(1f),
                                    fontSize = AptType.body,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = AptColors.OnBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (selected) {
                                    AptIcon(
                                        AptIconKind.Check,
                                        modifier = Modifier.size(AptSize.iconSmall),
                                        tint = AptColors.Primary,
                                    )
                                }
                            }
                        }
                        if (index == options.lastIndex && footer != null) {
                            Spacer(modifier = Modifier.height(AptSpace.xxs))
                        }
                    }
                }
                footer?.invoke(this)
            }
            LaunchedEffect(Unit) { runCatching { focusOnSelected.requestFocus() } }
        }
    }
}

/**
 * One value with a minus and a plus, for entering a date or time without a keyboard.
 * A TV remote has no number row, so stepping is the only entry method that always works.
 */
@Composable
fun AptStepper(
    label: String,
    value: String,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AptSpace.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label.uppercase(),
            fontSize = AptType.caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = AptColors.Tertiary,
        )
        Row(
            modifier = Modifier.focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AptSpace.xxs),
        ) {
            AptIconButton(
                icon = AptIconKind.ArrowLeft,
                contentDescription = "$label down",
                onClick = { onStep(-1) },
                size = AptSize.controlSmall,
            )
            Text(
                text = value,
                fontSize = AptType.body,
                fontWeight = FontWeight.SemiBold,
                color = AptColors.OnBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 64.dp),
            )
            AptIconButton(
                icon = AptIconKind.ArrowRight,
                contentDescription = "$label up",
                onClick = { onStep(1) },
                size = AptSize.controlSmall,
            )
        }
    }
}

/** TV-friendly setting row: label + current value, opens an editor on select. */
@Composable
fun SettingFieldButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    AptFocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        corner = AptRadius.row,
        focusScale = AptMotion.focusScaleRow,
        fill = AptColors.Glass,
        focusedFill = AptColors.GlassFocused,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = AptSpace.lg, vertical = AptSpace.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(label, fontSize = AptType.label, fontWeight = FontWeight.Medium, color = AptColors.Secondary)
                Text(
                    value,
                    fontSize = AptType.body,
                    fontWeight = FontWeight.Medium,
                    color = AptColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!description.isNullOrBlank()) {
                    Text(description, fontSize = AptType.caption, color = AptColors.Tertiary, maxLines = 2)
                }
            }
            AptIcon(AptIconKind.ChevronRight, modifier = Modifier.size(AptSize.iconSmall), tint = AptColors.Tertiary)
        }
    }
}

/** Single-field editor shown after picking a [SettingFieldButton]. */
@Composable
fun SettingEditCard(
    title: String,
    fieldLabel: String,
    value: String,
    onValue: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    placeholder: String = "",
    helper: String? = null,
) {
    AptCard(
        modifier = modifier.fillMaxWidth(),
        title = title,
        subtitle = helper,
    ) {
        AptTextField(
            label = fieldLabel,
            value = value,
            onValue = onValue,
            password = password,
            placeholder = placeholder,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
            AptButton(text = "Save", style = AptButtonStyle.Primary, icon = AptIconKind.Check, onClick = onSave)
            AptButton(text = "Cancel", style = AptButtonStyle.Ghost, icon = AptIconKind.Close, onClick = onCancel)
        }
    }
}

@Composable
fun AptTextField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    placeholder: String = "",
    helper: String? = null,
    keyboardType: KeyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
    singleLine: Boolean = true,
    minHeight: Dp = AptSize.control,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(AptRadius.field)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = AptType.label, fontWeight = FontWeight.Medium, color = AptColors.Secondary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .aptFocusFrame(focused = focused, shape = shape, scale = false, idleBorder = AptColors.HairlineStrong)
                .clip(shape)
                .background(AptColors.SurfaceSunken)
                .padding(horizontal = AptSpace.md, vertical = AptSpace.sm),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder.isNotBlank()) {
                Text(placeholder, fontSize = AptType.body, color = AptColors.Tertiary, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = singleLine,
                textStyle = TextStyle(color = AptColors.OnBackground, fontSize = AptType.body),
                cursorBrush = SolidColor(AptColors.Primary),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
            )
        }
        if (!helper.isNullOrBlank()) {
            Text(helper, fontSize = AptType.caption, color = AptColors.Tertiary)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// States
// ---------------------------------------------------------------------------------------------

@Composable
fun EmptyState(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    icon: AptIconKind = AptIconKind.Info,
    expand: Boolean = true,
    iconTint: Color = AptColors.Secondary,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier.then(if (expand) Modifier.fillMaxSize() else Modifier.fillMaxWidth()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AptSpace.sm),
            modifier = Modifier.widthIn(max = 560.dp).padding(AptSpace.xl),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(1.dp, AptColors.Hairline, CircleShape)
                    .background(AptColors.GlassRaised, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AptIcon(icon, modifier = Modifier.size(34.dp), tint = iconTint)
            }
            Spacer(modifier = Modifier.height(AptSpace.xs))
            Text(
                title,
                fontSize = AptType.title,
                fontWeight = FontWeight.SemiBold,
                color = AptColors.OnBackground,
                textAlign = TextAlign.Center,
            )
            if (!message.isNullOrBlank()) {
                Text(message, fontSize = AptType.body, color = AptColors.Secondary, textAlign = TextAlign.Center)
            }
            if (action != null) {
                Spacer(modifier = Modifier.height(AptSpace.sm))
                action()
            }
        }
    }
}

@Composable
fun LoadingState(label: String = "Loading", modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AptSpace.md)) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(1.dp, AptColors.Hairline, CircleShape)
                    .background(AptColors.Glass, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                StreamSpinner(size = 32.dp)
            }
            Text(label, fontSize = AptType.body, color = AptColors.Secondary)
        }
    }
}

@Composable
fun ErrorState(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    retryLabel: String = "Retry",
    onRetry: (() -> Unit)? = null,
) {
    val action: (@Composable () -> Unit)? = if (onRetry != null) {
        { AptButton(text = retryLabel, onClick = onRetry, style = AptButtonStyle.Primary, icon = AptIconKind.Retry) }
    } else {
        null
    }
    EmptyState(
        title = title,
        message = message,
        modifier = modifier,
        icon = AptIconKind.Warning,
        iconTint = AptColors.Warning,
        action = action,
    )
}

// ---------------------------------------------------------------------------------------------
// Permissions and helpers
// ---------------------------------------------------------------------------------------------

@Composable
fun rememberAlertPermissions(): app.apt443.alert.AlertPermissionState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(app.apt443.alert.AlertPermissions.read(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state = app.apt443.alert.AlertPermissions.read(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

fun Context.canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

fun Long.shortTime(): String {
    if (this <= 0L) return ""
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(this))
}

val railPadding = PaddingValues(horizontal = AptSpace.lg, vertical = AptSpace.lg)
