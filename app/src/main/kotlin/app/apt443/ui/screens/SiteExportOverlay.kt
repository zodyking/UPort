package app.apt443.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.apt443.data.AppContainer
import app.apt443.signin.SiteExportBridgeServer
import app.apt443.signin.tvLinkSlug
import app.apt443.signin.SitePackage
import app.apt443.signin.qrBitmap
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.InlineMessage
import app.apt443.ui.components.StatusTone
import app.apt443.ui.components.aptPanel
import app.apt443.ui.player.StreamSpinner
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType

@Composable
fun SiteExportOverlay(
    container: AppContainer,
    siteName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val server = remember(container, siteName) {
        SiteExportBridgeServer(
            linkSlug = { context.tvLinkSlug(siteName) },
            fileName = { SitePackage.fileNameFor(siteName) },
            loadBytes = { container.exportSitePackageBytes() },
        )
    }
    val url by server.url.collectAsStateWithLifecycle()
    val status by server.status.collectAsStateWithLifecycle()
    val qr = remember(url) { url?.let(::qrBitmap) }

    DisposableEffect(server) {
        server.start()
        onDispose { server.stop() }
    }
    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AptColors.Background.copy(alpha = 0.98f))
            .padding(horizontal = AptSpace.xxxl, vertical = AptSpace.overscanY),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AptSpace.xxxl),
        ) {
            Column(
                modifier = Modifier.widthIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(AptSpace.md),
            ) {
                Text(
                    text = "Export site file",
                    fontSize = AptType.hero,
                    fontWeight = FontWeight.SemiBold,
                    color = AptColors.OnBackground,
                )
                Text(
                    text = "Scan with your phone and download the site file. Keep that file private.",
                    fontSize = AptType.body,
                    color = AptColors.Secondary,
                )
                InlineMessage(status, StatusTone.Neutral)
                AptButton(text = "Done", style = AptButtonStyle.Secondary, onClick = onDismiss)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AptSpace.sm),
            ) {
                Box(
                    modifier = Modifier
                        .aptPanel(fill = Color.White, corner = AptRadius.sheet)
                        .padding(AptSpace.md),
                    contentAlignment = Alignment.Center,
                ) {
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = "Scan to download site file",
                            modifier = Modifier.size(260.dp),
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.None,
                        )
                    } else {
                        Box(modifier = Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                            StreamSpinner(size = 28.dp)
                        }
                    }
                }
                if (!url.isNullOrBlank()) {
                    Text(
                        text = url.orEmpty(),
                        fontSize = AptType.label,
                        fontFamily = FontFamily.Monospace,
                        color = AptColors.Secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 300.dp),
                    )
                }
            }
        }
    }
}
