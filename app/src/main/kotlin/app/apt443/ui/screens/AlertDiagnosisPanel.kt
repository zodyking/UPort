package app.apt443.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import app.apt443.alert.AlertDiagnosis
import app.apt443.alert.AlertDiagnosisReport
import app.apt443.alert.DiagnosisAction
import app.apt443.alert.DiagnosisTone
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptCard
import app.apt443.ui.components.AptFocusSurface
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.components.SettingsReadRow
import app.apt443.ui.components.StatusDot
import app.apt443.ui.components.StatusTone
import app.apt443.ui.components.color
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptMotion
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType

/**
 * Reads alert checks when this page is composed and again whenever the activity resumes,
 * so a trip to system Settings cannot leave a stale "enabled" flag on screen.
 */
@Composable
fun rememberAlertDiagnosis(): Pair<AlertDiagnosisReport, () -> Unit> {
    val context = LocalContext.current
    var report by remember { mutableStateOf(AlertDiagnosis.read(context)) }
    val refresh = {
        report = AlertDiagnosis.read(context)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                report = AlertDiagnosis.read(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return report to refresh
}

@Composable
fun AlertDiagnosisPanel(
    report: AlertDiagnosisReport,
    onRecheck: () -> Unit,
    onSystemAction: (DiagnosisAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    AptCard(
        modifier = modifier,
        title = "Alert check",
        subtitle = "Checked just now on this ${report.familyLabel}.",
    ) {
        DiagnosisHeading("Status")
        Column(verticalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
            report.lines.forEach { line ->
                DiagnosisStatusLine(text = line.text, tone = line.tone.toStatusTone())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
            AptButton(
                text = "Check again",
                style = AptButtonStyle.Primary,
                icon = AptIconKind.Retry,
                onClick = onRecheck,
            )
            when (report.systemAction) {
                DiagnosisAction.OpenOverlaySettings -> AptButton(
                    text = "Open permission screen",
                    onClick = { onSystemAction(DiagnosisAction.OpenOverlaySettings) },
                )
                DiagnosisAction.OpenAccessibilitySettings -> AptButton(
                    text = "Open Accessibility",
                    onClick = { onSystemAction(DiagnosisAction.OpenAccessibilitySettings) },
                )
                null -> Unit
            }
        }
        DiagnosisHeading("What to do")
        Column(verticalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
            report.whatToDo.forEachIndexed { index, step ->
                SettingsReadRow(title = "${index + 1}", body = step)
            }
        }
        DiagnosisHeading("Commands")
        SettingsReadRow(
            title = "Commands",
            body = buildString {
                if (report.commandIntro.isNotBlank()) append(report.commandIntro).append("\n\n")
                report.commands.forEach { line ->
                    append(readableCommand(line)).append("\n\n")
                }
                if (report.commandNote.isNotBlank()) append(report.commandNote)
            }.trim(),
        )
    }
}

@Composable
private fun DiagnosisHeading(text: String) {
    Text(
        text = text,
        fontSize = AptType.heading,
        fontWeight = FontWeight.SemiBold,
        color = AptColors.OnBackground,
    )
}

@Composable
private fun DiagnosisStatusLine(text: String, tone: StatusTone) {
    AptFocusSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        corner = AptRadius.row,
        scale = false,
        fill = AptColors.Glass,
        focusedFill = AptColors.GlassFocused,
        focusScale = AptMotion.focusScaleRow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AptSpace.lg, vertical = AptSpace.sm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
        ) {
            StatusDot(tone.color(), modifier = Modifier.padding(top = 6.dp))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                fontSize = AptType.body,
                fontWeight = FontWeight.Medium,
                color = if (tone == StatusTone.Neutral) AptColors.OnBackground else tone.color(),
            )
        }
    }
}

private fun DiagnosisTone.toStatusTone(): StatusTone = when (this) {
    DiagnosisTone.Ok -> StatusTone.Online
    DiagnosisTone.NeedsAction -> StatusTone.Warning
    DiagnosisTone.Unknown -> StatusTone.Neutral
}

/** Lets a long command wrap on the TV without adding a character the viewer would type. */
private fun readableCommand(line: String): String =
    line.replace("/", "/\u200b").replace(".", ".\u200b")
