package app.apt443.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.apt443.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.apt443.data.AppContainer
import app.apt443.data.remote.VpnStatus
import app.apt443.domain.AppSettings
import app.apt443.domain.SiteVpnKind
import app.apt443.signin.SignInBridgeServer
import app.apt443.signin.tvLinkSlug
import app.apt443.signin.SignInFlowMode
import app.apt443.signin.qrBitmap
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.components.SignInPhotoBackground
import app.apt443.ui.components.InlineMessage
import app.apt443.ui.components.StatusTone
import app.apt443.ui.components.aptPanel
import app.apt443.ui.player.StreamSpinner
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
@Composable
fun SignInScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val saved by container.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val context = LocalContext.current
    val siteReady = saved.siteConfigured && saved.consoleHost.isNotBlank()
    val flowMode = if (siteReady) SignInFlowMode.TenantSignIn else SignInFlowMode.SiteSetup
    val vpn by container.vpnStatus.collectAsStateWithLifecycle(initialValue = VpnStatus())
    val server = remember(container) {
        SignInBridgeServer(
            linkSlug = { context.tvLinkSlug() },
            mode = {
                val latest = runBlocking { container.settings.first() }
                if (latest.siteConfigured && latest.consoleHost.isNotBlank()) {
                    SignInFlowMode.TenantSignIn
                } else {
                    SignInFlowMode.SiteSetup
                }
            },
            onSiteUpload = { bytes, reach -> container.applySitePackage(bytes, reach) },
            loadTenants = { container.fetchIdentityUsers() },
            onTenantVerify = { userId, email -> container.completeTenantSignIn(userId, email) },
            onResetSite = { container.resetSite() },
        )
    }
    val url by server.url.collectAsStateWithLifecycle()
    val status by server.status.collectAsStateWithLifecycle()
    val problem by server.problem.collectAsStateWithLifecycle()
    val qr = remember(url) { url?.let(::qrBitmap) }

    DisposableEffect(server) {
        server.start()
        onDispose { server.stop() }
    }
    LaunchedEffect(flowMode) {
        if (flowMode == SignInFlowMode.TenantSignIn) {
            server.refreshTenants()
        }
    }

    val title = if (siteReady) "Sign in" else "Set up this TV"
    val body = if (siteReady) {
        "Scan the code with your phone, pick your name, and confirm your email."
    } else {
        "Scan the code with your phone. Enter the console details, upload a site file, or paste a download link."
    }

    Box(modifier = Modifier.fillMaxSize()) {
    SignInPhotoBackground(Modifier.fillMaxSize())
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AptSpace.xxxl + AptSpace.md, vertical = AptSpace.overscanY),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(end = AptSpace.xxxl)
                .widthIn(max = 460.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(AptSpace.md),
        ) {
            Image(
                painter = painterResource(R.drawable.uport_wordmark_white),
                contentDescription = "UPort",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(40.dp),
            )
            Text(text = title, fontSize = AptType.hero, fontWeight = FontWeight.SemiBold, color = AptColors.OnBackground)
            Text(text = body, fontSize = AptType.body, color = AptColors.OnBackground.copy(alpha = 0.86f))
            InlineMessage(status, if (problem) StatusTone.Danger else StatusTone.Neutral)
            if (saved.vpnKind == SiteVpnKind.Tailscale && vpn.stateLabel.isNotBlank()) {
                InlineMessage("Tailscale: ${vpn.stateLabel}", statusTone(vpn))
            }
            val consent by container.vpnConsent.request.collectAsStateWithLifecycle()
            if (consent != null) {
                InlineMessage("Allow the VPN connection so this TV can join Tailscale.", StatusTone.Warning)
            }
            if (siteReady) {
                var confirmReset by remember { mutableStateOf(false) }
                val resetFocus = remember { FocusRequester() }
                LaunchedEffect(confirmReset) {
                    repeat(3) {
                        withFrameNanos { }
                        if (runCatching { resetFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                    }
                }
                Spacer(modifier = Modifier.height(AptSpace.xs))
                if (!confirmReset) {
                    AptButton(
                        text = "Set up again",
                        style = AptButtonStyle.Secondary,
                        icon = AptIconKind.Retry,
                        onClick = { confirmReset = true },
                        modifier = Modifier.focusRequester(resetFocus),
                    )
                } else {
                    Text(
                        text = "Delete the console and Tailscale login from this TV?",
                        fontSize = AptType.body,
                        color = AptColors.OnBackground,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
                        AptButton(
                            text = "Cancel",
                            style = AptButtonStyle.Secondary,
                            onClick = { confirmReset = false },
                            modifier = Modifier.focusRequester(resetFocus),
                        )
                        AptButton(
                            text = "Delete setup",
                            style = AptButtonStyle.Danger,
                            onClick = { scope.launch { container.resetSite() } },
                        )
                    }
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AptSpace.sm),
        ) {
            Box(
                modifier = Modifier
                    .shadow(24.dp, RoundedCornerShape(AptRadius.sheet), clip = false)
                    .aptPanel(fill = Color.White, corner = AptRadius.sheet)
                    .padding(AptSpace.lg),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = "Scan to continue",
                        modifier = Modifier.size(280.dp),
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    Box(modifier = Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
                            StreamSpinner(size = 28.dp)
                            Text(
                                text = "The code appears when this TV joins the network.",
                                fontSize = AptType.body,
                                color = AptColors.Background,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = AptSpace.lg),
                            )
                        }
                    }
                }
            }
            Text(
                text = "Scan with your phone",
                fontSize = AptType.body,
                fontWeight = FontWeight.Medium,
                color = AptColors.OnBackground,
            )
            if (!url.isNullOrBlank()) {
                Text(
                    text = url.orEmpty(),
                    fontSize = AptType.label,
                    fontFamily = FontFamily.Monospace,
                    color = AptColors.Secondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.widthIn(max = 312.dp),
                )
            }
        }
    }
    Text(
        text = "Developed by Brandon King",
        fontSize = AptType.caption,
        color = AptColors.Secondary,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = AptSpace.lg),
    )
    }
}

private fun statusTone(vpn: VpnStatus): StatusTone = when {
    vpn.ourTunnelUp -> StatusTone.Online
    vpn.needsPermission || vpn.foreignVpnActive -> StatusTone.Warning
    else -> StatusTone.Neutral
}
