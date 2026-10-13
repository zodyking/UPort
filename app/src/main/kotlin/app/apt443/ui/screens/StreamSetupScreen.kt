package app.apt443.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.apt443.data.AppContainer
import app.apt443.domain.AppSettings
import app.apt443.domain.Availability
import app.apt443.domain.Feed
import app.apt443.domain.manualRtsp
import app.apt443.domain.redactRtsp
import app.apt443.domain.sanitizeRtsp
import app.apt443.signin.RtspLinkServer
import app.apt443.signin.qrBitmap
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.components.AptTextField
import app.apt443.ui.components.PageHeader
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import kotlinx.coroutines.launch

@Composable
fun StreamSetupScreen(
    container: AppContainer,
    cameraId: String,
    onBack: () -> Unit,
) {
    val settings by container.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val cameras by container.protect.cameras().collectAsStateWithLifecycle(
        initialValue = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    val camera = cameras.items.firstOrNull { it.id == cameraId }
    val name = camera?.name?.ifBlank { null } ?: "Camera"
    val saved = settings.manualRtsp(cameraId)
    val scope = rememberCoroutineScope()
    var draft by remember(cameraId) { mutableStateOf("") }
    var note by remember(cameraId) { mutableStateOf("") }
    val server = remember(cameraId, name) { RtspLinkServer(name) { url -> scope.launch { container.setManualRtsp(cameraId, url) } } }
    val link by server.url.collectAsStateWithLifecycle()
    val phoneStatus by server.status.collectAsStateWithLifecycle()
    DisposableEffect(server) {
        server.start()
        onDispose { server.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AptSpace.xs),
        verticalArrangement = Arrangement.spacedBy(AptSpace.lg),
    ) {
        PageHeader(
            title = if (saved == null) "Add a stream" else "Your stream",
            subtitle = name,
            eyebrow = "Camera",
        ) {
            AptButton(text = "Back", icon = AptIconKind.Back, style = AptButtonStyle.Secondary, onClick = onBack)
        }
        Text(
            text = "UniFi Protect does not give this camera an RTSP address. Paste one and this TV will use it for live view and popups. It is not written into the site file.",
            fontSize = AptType.body,
            color = AptColors.Secondary,
        )
        if (saved != null) {
            Text(
                text = redactRtsp(saved),
                fontSize = AptType.body,
                fontWeight = FontWeight.Medium,
                color = AptColors.OnBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            AptButton(
                text = "Remove stream",
                icon = AptIconKind.Close,
                style = AptButtonStyle.Ghost,
                onClick = { scope.launch { container.setManualRtsp(cameraId, null) } },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AptSpace.xl),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AptSpace.md),
            ) {
                AptTextField(
                    label = "RTSP address",
                    value = draft,
                    onValue = { draft = it },
                    placeholder = "rtsp://192.168.0.20:554/stream",
                    helper = note.ifBlank { "rtsps:// works too. The password stays on this TV." },
                )
                AptButton(
                    text = "Save on this TV",
                    icon = AptIconKind.Check,
                    style = AptButtonStyle.Primary,
                    onClick = {
                        val clean = sanitizeRtsp(draft)
                        if (clean == null) {
                            note = "Use an address that starts with rtsp:// or rtsps://."
                        } else {
                            note = ""
                            draft = ""
                            scope.launch { container.setManualRtsp(cameraId, clean) }
                        }
                    },
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(AptSpace.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val page = link
                val code = remember(page) { page?.let { qrBitmap(it, size = 480) } }
                if (page != null && code != null) {
                    Image(
                        bitmap = code,
                        contentDescription = "QR code for $page",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(AptRadius.row)),
                    )
                    Text(page, fontSize = AptType.caption, color = AptColors.Secondary, maxLines = 1)
                }
                Text(
                    text = phoneStatus,
                    fontSize = AptType.label,
                    color = AptColors.Secondary,
                    modifier = Modifier.padding(top = AptSpace.xs),
                )
                Text(
                    text = "The phone must be on this TV's Wi-Fi.",
                    fontSize = AptType.caption,
                    color = AptColors.Tertiary,
                )
            }
        }
    }
}
