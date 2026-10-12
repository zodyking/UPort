package app.apt443.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.apt443.ui.theme.AptColors

enum class AptIconKind {
    Camera,
    Door,
    Network,
    Bell,
    Footage,
    Settings,
    Remote,
    Account,
    Check,
    ChevronRight,
    ChevronLeft,
    Back,
    Light,
    Sensor,
    Retry,
    Info,
    Play,
    Close,
    Warning,
    ArrowUp,
    ArrowDown,
    ArrowLeft,
    ArrowRight,
    ZoomIn,
    ZoomOut,
    Home,
    Speaker,
    Lock,
    Tv,
}

/** Stroke icons drawn on a square canvas; size comes from [modifier]. */
@Composable
fun AptIcon(
    kind: AptIconKind,
    modifier: Modifier = Modifier,
    tint: Color = AptColors.Secondary,
) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        when (kind) {
            AptIconKind.Camera -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.08f, h * 0.28f),
                    size = Size(w * 0.62f, h * 0.44f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = stroke,
                )
                drawCircle(tint, radius = w * 0.1f, center = Offset(w * 0.39f, h * 0.5f), style = stroke)
                line(tint, stroke, 0.72f, 0.38f, 0.92f, 0.28f)
                line(tint, stroke, 0.72f, 0.62f, 0.92f, 0.72f)
            }
            AptIconKind.Door -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.28f, h * 0.12f),
                    size = Size(w * 0.44f, h * 0.76f),
                    cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
                    style = stroke,
                )
                drawCircle(tint, radius = w * 0.045f, center = Offset(w * 0.6f, h * 0.52f))
            }
            AptIconKind.Network -> {
                val r = w * 0.07f
                val nodes = listOf(Offset(w * 0.22f, h * 0.32f), Offset(w * 0.78f, h * 0.32f), Offset(w * 0.5f, h * 0.72f))
                nodes.forEach { drawCircle(tint, radius = r, center = it, style = stroke) }
                drawLine(tint, nodes[0], nodes[1], stroke.width, StrokeCap.Round)
                drawLine(tint, nodes[0], nodes[2], stroke.width, StrokeCap.Round)
                drawLine(tint, nodes[1], nodes[2], stroke.width, StrokeCap.Round)
            }
            AptIconKind.Bell -> {
                drawArc(
                    color = tint,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(w * 0.22f, h * 0.18f),
                    size = Size(w * 0.56f, h * 0.56f),
                    style = stroke,
                )
                line(tint, stroke, 0.28f, 0.62f, 0.72f, 0.62f)
                drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.76f))
            }
            AptIconKind.Footage -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.1f, h * 0.2f),
                    size = Size(w * 0.8f, h * 0.6f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = stroke,
                )
                val tri = Path().apply {
                    moveTo(w * 0.42f, h * 0.36f)
                    lineTo(w * 0.64f, h * 0.5f)
                    lineTo(w * 0.42f, h * 0.64f)
                    close()
                }
                drawPath(tri, tint)
            }
            AptIconKind.Settings -> {
                listOf(0.3f, 0.5f, 0.7f).forEachIndexed { index, y ->
                    line(tint, stroke, 0.14f, y, 0.86f, y)
                    val knob = when (index) {
                        0 -> 0.62f
                        1 -> 0.36f
                        else -> 0.7f
                    }
                    drawCircle(AptColors.Background, radius = w * 0.085f, center = Offset(w * knob, h * y))
                    drawCircle(tint, radius = w * 0.085f, center = Offset(w * knob, h * y), style = stroke)
                }
            }
            AptIconKind.Remote -> {
                drawCircle(tint, radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                line(tint, stroke, 0.14f, 0.5f, 0.86f, 0.5f)
                drawArc(
                    color = tint,
                    startAngle = 90f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.32f, h * 0.14f),
                    size = Size(w * 0.36f, h * 0.72f),
                    style = stroke,
                )
                drawArc(
                    color = tint,
                    startAngle = 270f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.32f, h * 0.14f),
                    size = Size(w * 0.36f, h * 0.72f),
                    style = stroke,
                )
            }
            AptIconKind.Account -> {
                drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.34f), style = stroke)
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.56f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = stroke,
                )
            }
            AptIconKind.Check -> {
                val path = Path().apply {
                    moveTo(w * 0.2f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.74f)
                    lineTo(w * 0.82f, h * 0.3f)
                }
                drawPath(path, tint, style = stroke)
            }
            AptIconKind.ChevronRight -> chevron(tint, stroke, 0.38f, 0.62f)
            AptIconKind.ChevronLeft -> chevron(tint, stroke, 0.62f, 0.38f)
            AptIconKind.Back, AptIconKind.ArrowLeft -> {
                line(tint, stroke, 0.18f, 0.5f, 0.82f, 0.5f)
                line(tint, stroke, 0.18f, 0.5f, 0.42f, 0.26f)
                line(tint, stroke, 0.18f, 0.5f, 0.42f, 0.74f)
            }
            AptIconKind.ArrowRight -> {
                line(tint, stroke, 0.18f, 0.5f, 0.82f, 0.5f)
                line(tint, stroke, 0.82f, 0.5f, 0.58f, 0.26f)
                line(tint, stroke, 0.82f, 0.5f, 0.58f, 0.74f)
            }
            AptIconKind.ArrowUp -> {
                line(tint, stroke, 0.5f, 0.82f, 0.5f, 0.18f)
                line(tint, stroke, 0.5f, 0.18f, 0.26f, 0.42f)
                line(tint, stroke, 0.5f, 0.18f, 0.74f, 0.42f)
            }
            AptIconKind.ArrowDown -> {
                line(tint, stroke, 0.5f, 0.18f, 0.5f, 0.82f)
                line(tint, stroke, 0.5f, 0.82f, 0.26f, 0.58f)
                line(tint, stroke, 0.5f, 0.82f, 0.74f, 0.58f)
            }
            AptIconKind.Light -> {
                drawArc(
                    color = tint,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = Offset(w * 0.24f, h * 0.1f),
                    size = Size(w * 0.52f, h * 0.52f),
                    style = stroke,
                )
                line(tint, stroke, 0.38f, 0.58f, 0.38f, 0.74f)
                line(tint, stroke, 0.62f, 0.58f, 0.62f, 0.74f)
                line(tint, stroke, 0.38f, 0.74f, 0.62f, 0.74f)
                line(tint, stroke, 0.42f, 0.86f, 0.58f, 0.86f)
            }
            AptIconKind.Sensor -> {
                drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.5f, h * 0.5f))
                listOf(0.2f, 0.34f).forEach { r ->
                    drawArc(
                        color = tint,
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(w * (0.5f - r), h * (0.5f - r)),
                        size = Size(w * r * 2, h * r * 2),
                        style = stroke,
                    )
                }
            }
            AptIconKind.Retry -> {
                drawArc(
                    color = tint,
                    startAngle = -40f,
                    sweepAngle = 290f,
                    useCenter = false,
                    topLeft = Offset(w * 0.18f, h * 0.18f),
                    size = Size(w * 0.64f, h * 0.64f),
                    style = stroke,
                )
                line(tint, stroke, 0.74f, 0.16f, 0.78f, 0.36f)
                line(tint, stroke, 0.78f, 0.36f, 0.58f, 0.38f)
            }
            AptIconKind.Info -> {
                drawCircle(tint, radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                line(tint, stroke, 0.5f, 0.46f, 0.5f, 0.7f)
                drawCircle(tint, radius = w * 0.045f, center = Offset(w * 0.5f, h * 0.32f))
            }
            AptIconKind.Play -> {
                val tri = Path().apply {
                    moveTo(w * 0.32f, h * 0.2f)
                    lineTo(w * 0.78f, h * 0.5f)
                    lineTo(w * 0.32f, h * 0.8f)
                    close()
                }
                drawPath(tri, tint)
            }
            AptIconKind.Close -> {
                line(tint, stroke, 0.26f, 0.26f, 0.74f, 0.74f)
                line(tint, stroke, 0.74f, 0.26f, 0.26f, 0.74f)
            }
            AptIconKind.Warning -> {
                val tri = Path().apply {
                    moveTo(w * 0.5f, h * 0.14f)
                    lineTo(w * 0.9f, h * 0.84f)
                    lineTo(w * 0.1f, h * 0.84f)
                    close()
                }
                drawPath(tri, tint, style = stroke)
                line(tint, stroke, 0.5f, 0.4f, 0.5f, 0.6f)
                drawCircle(tint, radius = w * 0.04f, center = Offset(w * 0.5f, h * 0.72f))
            }
            AptIconKind.ZoomIn, AptIconKind.ZoomOut -> {
                drawCircle(tint, radius = w * 0.26f, center = Offset(w * 0.42f, h * 0.42f), style = stroke)
                line(tint, stroke, 0.62f, 0.62f, 0.84f, 0.84f)
                line(tint, stroke, 0.3f, 0.42f, 0.54f, 0.42f)
                if (kind == AptIconKind.ZoomIn) line(tint, stroke, 0.42f, 0.3f, 0.42f, 0.54f)
            }
            AptIconKind.Home -> {
                val path = Path().apply {
                    moveTo(w * 0.14f, h * 0.5f)
                    lineTo(w * 0.5f, h * 0.18f)
                    lineTo(w * 0.86f, h * 0.5f)
                }
                drawPath(path, tint, style = stroke)
                drawRect(
                    color = tint,
                    topLeft = Offset(w * 0.26f, h * 0.5f),
                    size = Size(w * 0.48f, h * 0.34f),
                    style = stroke,
                )
            }
            AptIconKind.Speaker -> {
                val body = Path().apply {
                    moveTo(w * 0.16f, h * 0.38f)
                    lineTo(w * 0.32f, h * 0.38f)
                    lineTo(w * 0.52f, h * 0.2f)
                    lineTo(w * 0.52f, h * 0.8f)
                    lineTo(w * 0.32f, h * 0.62f)
                    lineTo(w * 0.16f, h * 0.62f)
                    close()
                }
                drawPath(body, tint, style = stroke)
                drawArc(
                    color = tint,
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(w * 0.46f, h * 0.3f),
                    size = Size(w * 0.4f, h * 0.4f),
                    style = stroke,
                )
            }
            AptIconKind.Lock -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.22f, h * 0.44f),
                    size = Size(w * 0.56f, h * 0.42f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = stroke,
                )
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.32f, h * 0.14f),
                    size = Size(w * 0.36f, h * 0.46f),
                    style = stroke,
                )
            }
            AptIconKind.Tv -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.1f, h * 0.18f),
                    size = Size(w * 0.8f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
                    style = stroke,
                )
                line(tint, stroke, 0.34f, 0.84f, 0.66f, 0.84f)
            }
        }
    }
}

private fun DrawScope.line(tint: Color, stroke: Stroke, x1: Float, y1: Float, x2: Float, y2: Float) {
    drawLine(
        color = tint,
        start = Offset(size.width * x1, size.height * y1),
        end = Offset(size.width * x2, size.height * y2),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.chevron(tint: Color, stroke: Stroke, fromX: Float, toX: Float) {
    line(tint, stroke, fromX, 0.28f, toX, 0.5f)
    line(tint, stroke, toX, 0.5f, fromX, 0.72f)
}
