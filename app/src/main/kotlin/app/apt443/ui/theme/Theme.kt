package app.apt443.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.darkColorScheme

/**
 * APT 443 visual system: "soft premium" dark security console.
 * Near-black gradient canvas, translucent raised surfaces, large radii, hairline borders,
 * one blue accent reserved for focus and primary actions, a 10-foot type scale and
 * overscan-safe spacing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Apt443Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AptColors.Primary,
            onPrimary = AptColors.OnPrimary,
            background = AptColors.Background,
            onBackground = AptColors.OnBackground,
            surface = AptColors.Surface,
            onSurface = AptColors.OnBackground,
            surfaceVariant = AptColors.SurfaceRaised,
            onSurfaceVariant = AptColors.Secondary,
            error = AptColors.Danger,
            border = AptColors.Hairline,
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(AptRadius.chip),
            small = RoundedCornerShape(AptRadius.button),
            medium = RoundedCornerShape(AptRadius.card),
            large = RoundedCornerShape(AptRadius.tile),
            extraLarge = RoundedCornerShape(AptRadius.sheet),
        ),
    ) {
        CompositionLocalProvider(
            LocalReducedMotion provides reducedMotion,
            LocalBringIntoViewSpec provides AptBringIntoView,
            content = content,
        )
    }
}

/** True when the system animator scale is off; focus and rail motion snap instead of animating. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Tween that collapses to a snap when the viewer turned animations off. */
fun <T> aptTween(durationMs: Int, reduced: Boolean): AnimationSpec<T> =
    if (reduced) snap() else tween(durationMs)

/**
 * Scroll rule for every lazy list and scrolling column: keep the focused item fully on screen
 * with a peek margin so the next row is visible, and never scroll when it already fits.
 */
@OptIn(ExperimentalFoundationApi::class)
val AptBringIntoView: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val peek = (containerSize * 0.14f).coerceAtMost(((containerSize - size) / 2f).coerceAtLeast(0f))
        val trailing = offset + size
        return when {
            size + peek * 2 >= containerSize -> offset - peek
            offset < peek -> offset - peek
            trailing > containerSize - peek -> trailing - (containerSize - peek)
            else -> 0f
        }
    }
}

object AptColors {
    // Canvas. The page background is a soft vertical gradient between these two.
    val BackgroundTop = Color(0xFF11151C)
    val Background = Color(0xFF07090C)
    val BackgroundBottom = Color(0xFF040507)
    val backgroundBrush: Brush = Brush.verticalGradient(listOf(BackgroundTop, Background, BackgroundBottom))

    // Opaque neutral surfaces, darkest to lightest. Use these where content sits over video or other apps.
    val SurfaceSunken = Color(0xFF0D1015)
    val Surface = Color(0xFF14181F)
    val SurfaceRaised = Color(0xFF1B2029)
    val SurfaceOverlay = Color(0xFF242A35)
    val PreviewWell = Color(0xFF040506)

    // Translucent "frosted" surfaces for cards and rows sitting on the page canvas.
    val Glass = Color(0x0FFFFFFF)
    val GlassRaised = Color(0x17FFFFFF)
    val GlassFocused = Color(0x29FFFFFF)
    val GlassSunken = Color(0x33000000)

    /** Expanded rail and dropdown sheets: nearly opaque so content underneath never competes. */
    val Sheet = Color(0xF21A1F28)

    // Hairlines (white at low alpha so they read on any surface).
    val Hairline = Color(0x1AFFFFFF)
    val HairlineStrong = Color(0x2EFFFFFF)

    // Accent.
    val Primary = Color(0xFF2F8BFF)
    val PrimaryFocused = Color(0xFF4F9DFF)
    val PrimarySoft = Color(0x332F8BFF)
    val PrimaryGlow = Color(0x662F8BFF)
    val FocusRing = Color(0xFFB9DAFF)
    val OnPrimary = Color(0xFF041022)
    val Focus = Primary

    // Selected (not focused) state for chips and segments: a light neutral, not the accent.
    val Selected = Color(0xFFE8ECF2)
    val OnSelected = Color(0xFF0A0D12)

    // Text.
    val OnBackground = Color(0xFFF3F5F8)
    val OnVideo = Color(0xFFFFFFFF)
    val Secondary = Color(0xFFA7B0BC)
    val Tertiary = Color(0xFF6F7A88)
    val Muted = Secondary

    // Semantic.
    val Online = Color(0xFF34C759)
    val OnlineSoft = Color(0x2634C759)
    val Warning = Color(0xFFF2A93B)
    val WarningSoft = Color(0x26F2A93B)
    val Danger = Color(0xFFFF4D4F)
    val DangerFocused = Color(0xFFFF6B6D)
    val DangerSoft = Color(0x26FF4D4F)
    val Live = Danger
    val Offline = Tertiary

    // Overlays drawn over video.
    val Scrim = Color(0xE6070A0E)
    val ScrimSoft = Color(0x99070A0E)

    /** Dims the page while the rail is open. */
    val RailScrim = Color(0x8C000000)
}

object AptSpace {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val xxxl = 64.dp
    val overscanX = 48.dp
    val overscanY = 27.dp

    /** Default gutter between tiles in grids. */
    val gutter = 16.dp

    /** Inset inside scrolling lists so a scaled, ringed item is never clipped by the list edge. */
    val focusInset = 14.dp
}

object AptRadius {
    val chip = 999.dp
    val button = 14.dp
    val field = 12.dp
    val card = 22.dp
    val tile = 20.dp
    val row = 18.dp
    val sheet = 28.dp
}

object AptType {
    val hero = 42.sp
    val display = 32.sp
    val title = 24.sp
    val heading = 19.sp
    val camera = 17.sp
    val body = 16.sp
    val label = 14.sp
    val caption = 12.sp

    /** Letter spacing for small-caps labels (section labels, eyebrows). */
    val capsTracking = 1.6.sp
}

/** Ready-made text styles built from [AptType]; headlines SemiBold, body Regular. */
object AptText {
    val hero = TextStyle(fontSize = AptType.hero, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    val display = TextStyle(fontSize = AptType.display, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val title = TextStyle(fontSize = AptType.title, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp)
    val heading = TextStyle(fontSize = AptType.heading, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = AptType.body, fontWeight = FontWeight.Normal, lineHeight = 22.sp)
    val label = TextStyle(fontSize = AptType.label, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = AptType.caption, fontWeight = FontWeight.Normal)
    val overline = TextStyle(
        fontSize = AptType.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = AptType.capsTracking,
    )
}

object AptMotion {
    const val fast = 150
    const val normal = 200
    const val slow = 250

    /** Scale applied to focused tiles and cards. */
    const val focusScale = 1.05f

    /** Scale for buttons, chips and other small controls. */
    const val focusScaleControl = 1.05f

    /** Scale for full-width rows, kept small so the row never runs past the page edges. */
    const val focusScaleRow = 1.015f
}

object AptSize {
    val control = 48.dp
    val controlSmall = 40.dp

    /** One dropdown row, tall enough to focus from across the room. */
    val menuRow = 48.dp
    val icon = 20.dp
    val iconSmall = 16.dp
    val railCollapsed = 80.dp
    val railExpanded = 248.dp
    val focusBorder = 2.dp

    /** Lift applied under a focused surface. */
    val focusElevation = 14.dp
}
