package app.apt443.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.apt443.R
import app.apt443.alert.AlertController
import app.apt443.alert.DreamVisibility
import app.apt443.alert.QuietPromptController
import app.apt443.data.AppContainer
import app.apt443.domain.AccountGate
import app.apt443.domain.AlertStyle
import app.apt443.domain.PopupLook
import app.apt443.domain.AppSettings
import app.apt443.domain.prefixSpeakerMessage
import app.apt443.domain.speakerPhrases
import app.apt443.domain.Availability
import app.apt443.domain.ConnectionMode
import app.apt443.domain.Feed
import app.apt443.domain.camerasNeedingSetup
import app.apt443.ui.components.AlertInsetHost
import app.apt443.ui.components.AlertPopupGroup
import app.apt443.ui.components.AptIcon
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.components.StatusDot
import app.apt443.ui.components.StatusTone
import app.apt443.ui.components.aptFocusFrame
import app.apt443.ui.components.color
import app.apt443.ui.screens.AccessScreen
import app.apt443.ui.screens.AccountScreen
import app.apt443.ui.screens.CameraWizardBus
import app.apt443.ui.screens.CameraWizardScreen
import app.apt443.ui.screens.CamerasScreen
import app.apt443.ui.screens.FootageScreen
import app.apt443.ui.screens.LiveScreen
import app.apt443.ui.screens.NetworkScreen
import app.apt443.ui.screens.RemoteScreen
import app.apt443.ui.screens.SettingsScreen
import app.apt443.ui.screens.SignInScreen
import app.apt443.ui.screens.StreamSetupScreen
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptMotion
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSize
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.LocalReducedMotion
import app.apt443.ui.theme.aptTween
import app.apt443.data.Startup
import app.apt443.screensaver.CameraWall
import app.apt443.screensaver.SleepPreview
import app.apt443.ui.components.AptButton
import app.apt443.ui.components.AptButtonStyle
import app.apt443.ui.components.AptSwitchRow
import app.apt443.ui.player.StreamSpinner
import app.apt443.ui.theme.AptType

object Routes {
    const val Home = "home"
    const val Cameras = "cameras"
    const val Live = "live/{cameraId}"
    const val Footage = "footage"
    const val Network = "network"
    const val Access = "access"
    const val Remote = "remote"
    const val Settings = "settings"
    const val Account = "account"

    const val Stream = "stream/{cameraId}"

    fun live(cameraId: String) = "live/$cameraId"
    fun stream(cameraId: String) = "stream/${android.net.Uri.encode(cameraId)}"
}

@Composable
private fun TailscaleConsentPrompt(container: AppContainer) {
    val context = LocalContext.current
    val request by container.vpnConsent.request.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        container.vpnConsent.complete(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        val intent = VpnService.prepare(context)
        if (intent == null) container.vpnConsent.complete(true) else launcher.launch(intent)
    }
}

private data class Destination(val route: String, val label: String, val icon: AptIconKind)

private val destinations = listOf(
    Destination(Routes.Cameras, "Live Feed", AptIconKind.Camera),
    Destination(Routes.Footage, "Playback", AptIconKind.Footage),
    Destination(Routes.Access, "Access", AptIconKind.Door),
    Destination(Routes.Network, "Network", AptIconKind.Network),
    Destination(Routes.Remote, "Tailscale", AptIconKind.Remote),
    Destination(Routes.Settings, "Settings", AptIconKind.Settings),
)

private val accountDestination = Destination(Routes.Account, "My account", AptIconKind.Account)

@Composable
private fun StartupLock(container: AppContainer, detail: String, canReset: Boolean) {
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(canReset, confirmReset) {
        if (!canReset) return@LaunchedEffect
        awaitFrames(2)
        runCatching { actionFocus.requestFocus() }
    }
    Box(
        modifier = Modifier.fillMaxSize().background(AptColors.backgroundBrush),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp).padding(AptSpace.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AptSpace.md),
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .border(1.dp, AptColors.Hairline, CircleShape)
                    .background(AptColors.Glass, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                StreamSpinner(size = 40.dp)
            }
            Text(
                text = "Loading",
                fontSize = AptType.hero,
                fontWeight = FontWeight.SemiBold,
                color = AptColors.OnBackground,
            )
            Text(text = detail, fontSize = AptType.body, color = AptColors.Secondary, textAlign = TextAlign.Center)
            if (canReset && !confirmReset) {
                Spacer(modifier = Modifier.height(AptSpace.xs))
                AptButton(
                    text = "Delete setup",
                    style = AptButtonStyle.Secondary,
                    onClick = { confirmReset = true },
                    modifier = Modifier.focusRequester(actionFocus),
                )
            }
            if (canReset && confirmReset) {
                Text(
                    text = "Delete the console and Tailscale login from this TV?",
                    fontSize = AptType.body,
                    color = AptColors.OnBackground,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
                    AptButton(
                        text = "Cancel",
                        style = AptButtonStyle.Secondary,
                        onClick = { confirmReset = false },
                        modifier = Modifier.focusRequester(actionFocus),
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
}

private suspend fun awaitFrames(count: Int) {
    repeat(count) { withFrameNanos { } }
}

/** True for remote/arrow-key moves; false for programmatic Enter/Exit requests. */
fun FocusDirection.isDpad(): Boolean =
    this == FocusDirection.Up || this == FocusDirection.Down ||
        this == FocusDirection.Left || this == FocusDirection.Right ||
        this == FocusDirection.Next || this == FocusDirection.Previous

@Composable
fun Apt443Root(container: AppContainer) {
    TailscaleConsentPrompt(container)
    val settings by produceState<AppSettings?>(initialValue = null) {
        container.settings.collect { value = it }
    }
    val startup by container.startup.collectAsStateWithLifecycle(initialValue = Startup.Loading("Loading"))
    if (settings == null) {
        StartupLock(container, detail = "Loading", canReset = false)
        return
    }
    val current = settings ?: return
    if (current.siteConfigured && startup is Startup.Loading) {
        StartupLock(container, detail = (startup as Startup.Loading).detail, canReset = true)
        return
    }
    if (current.accountGate != AccountGate.Console) {
        SignInScreen(container)
        return
    }
    val scope = rememberCoroutineScope()
    val cameraFeed by container.protect.cameras().collectAsStateWithLifecycle(
        initialValue = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    val unsetCameras = current.camerasNeedingSetup(cameraFeed.items)
    val manualWizard by CameraWizardBus.request.collectAsStateWithLifecycle()
    // A setup run holds every camera it was asked about until Finish, even after each
    // camera has saved and left the "needs setup" list.
    var setupRun by remember { mutableStateOf<Set<String>?>(null) }
    val unsetIds = unsetCameras.map { it.id }.toSet()
    if (unsetIds.isNotEmpty() && setupRun?.containsAll(unsetIds) != true) {
        setupRun = setupRun.orEmpty() + unsetIds
    }
    val setupLocked = setupRun != null
    if (setupLocked || manualWizard != null) {
        val client = remember(current.consoleHost, current.trustConsoleCertificate) {
            container.http.clientFor(current)
        }
        val wizardIds = if (setupLocked) {
            setupRun
        } else {
            manualWizard?.takeIf { it.isNotBlank() }?.let { setOf(it) }
        }
        CameraWizardScreen(
            container = container,
            settings = current,
            client = client,
            cameraIds = wizardIds,
            allowSkip = !setupLocked,
            onFinished = {
                scope.launch {
                    container.markCameraWizardDone(current.identityUserId)
                    CameraWizardBus.clear()
                    setupRun = null
                }
            },
            onSkip = { CameraWizardBus.clear() },
        )
        return
    }
    SignedInShell(container, current)
}

@Composable
private fun SignedInShell(container: AppContainer, settings: AppSettings) {
    LaunchedEffect(Unit) {
        container.healLocalAdminMode()
    }
    val nav = rememberNavController()
    val client = remember(settings.consoleHost, settings.trustConsoleCertificate) {
        container.http.clientFor(settings)
    }
    val slots by AlertController.slots.collectAsStateWithLifecycle()
    val prompt by QuietPromptController.prompt.collectAsStateWithLifecycle()
    val watch by NavigationBus.camera.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val sleepOn by SleepPreview.open.collectAsStateWithLifecycle()
    val immersive = route?.startsWith("live/") == true || sleepOn
    val reduced = LocalReducedMotion.current
    val focusManager = LocalFocusManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var pageHasFocus by remember { mutableStateOf(false) }
    var anchorFocused by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    val pageAnchor = remember { FocusRequester() }
    val sleepFocus = remember { FocusRequester() }
    var anchorArmed by remember { mutableStateOf(false) }
    val popups = slots
    val showPrompt = prompt
    val overlayUp = popups.isNotEmpty() || showPrompt != null
    LaunchedEffect(route) { menuOpen = false }
    LaunchedEffect(overlayUp) {
        if (overlayUp) menuOpen = false
    }
    LaunchedEffect(menuOpen) {
        if (!menuOpen || immersive) return@LaunchedEffect
        // Rail items only become focusable after the recomposition that follows menuOpen.
        repeat(3) {
            awaitFrames(2)
            if (runCatching { railFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // Page focus: restore the last control (focusRestorer), else enter the page's first control,
    // else park on the 1dp anchor so the remote still has a target. While parked, keep retrying
    // for a moment because pages that load data make their controls focusable a little later.
    DisposableEffect(Unit) {
        onDispose { DreamVisibility.showingPreview = false }
    }
    LaunchedEffect(sleepOn) {
        DreamVisibility.showingPreview = sleepOn
        if (!sleepOn) return@LaunchedEffect
        menuOpen = false
        repeat(8) { attempt ->
            if (attempt > 0) delay(40)
            if (runCatching { sleepFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(route, menuOpen, overlayUp, sleepOn) {
        anchorArmed = false
        if (menuOpen || immersive || overlayUp || sleepOn) return@LaunchedEffect
        delay(48)
        repeat(6) { attempt ->
            if (attempt > 0) delay(220)
            if (pageHasFocus && !anchorFocused) return@LaunchedEffect
            if (anchorFocused) {
                anchorArmed = false
                awaitFrames(2)
            }
            runCatching { pageFocus.requestFocus() }
            awaitFrames(1)
            if (pageHasFocus && !anchorFocused) return@LaunchedEffect
            anchorArmed = true
            awaitFrames(2)
            runCatching { pageAnchor.requestFocus() }
        }
    }

    LaunchedEffect(watch?.atEpochMs) {
        val request = watch ?: return@LaunchedEffect
        nav.navigate(Routes.live(request.id)) { launchSingleTop = true }
    }

    BackHandler(enabled = menuOpen || overlayUp || sleepOn) {
        when {
            sleepOn -> SleepPreview.open.value = false
            overlayUp -> AlertController.ignore()
            menuOpen -> menuOpen = false
        }
    }

    val scrim by animateFloatAsState(
        targetValue = if (menuOpen && !immersive) 1f else 0f,
        animationSpec = aptTween(AptMotion.slow, reduced),
        label = "rail-scrim",
    )
    val pageEnter: EnterTransition = if (reduced) EnterTransition.None else fadeIn(tween(AptMotion.normal))
    val pageExit: ExitTransition = if (reduced) ExitTransition.None else fadeOut(tween(AptMotion.fast))

    Box(modifier = Modifier.fillMaxSize().background(AptColors.backgroundBrush)) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (!immersive) {
                // The rail keeps a fixed slot and expands over the content, so video tiles never relayout.
                Box(
                    modifier = Modifier
                        .width(AptSize.railCollapsed)
                        .fillMaxHeight()
                        .zIndex(2f)
                        .wrapContentWidth(align = Alignment.Start, unbounded = true),
                ) {
                    Rail(
                        nav = nav,
                        route = route,
                        container = container,
                        settings = settings,
                        open = menuOpen,
                        railFocus = railFocus,
                        onClose = { menuOpen = false },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { pageHasFocus = it.hasFocus }
                    .onKeyEvent { event ->
                        val left = event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft
                        if (!left || menuOpen || overlayUp || immersive) return@onKeyEvent false
                        // Left moves within the page first; the rail opens only from the page's left edge.
                        if (!anchorFocused && focusManager.moveFocus(FocusDirection.Left)) return@onKeyEvent true
                        menuOpen = true
                        true
                    }
                    .focusRequester(pageFocus)
                    .focusRestorer()
                    .focusGroup(),
            ) {
                NavHost(
                    navController = nav,
                    startDestination = Routes.Cameras,
                    enterTransition = { pageEnter },
                    exitTransition = { pageExit },
                    popEnterTransition = { pageEnter },
                    popExitTransition = { pageExit },
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (immersive) {
                                Modifier
                            } else {
                                Modifier.padding(
                                    start = AptSpace.lg,
                                    end = AptSpace.overscanX,
                                    top = AptSpace.overscanY,
                                    bottom = AptSpace.overscanY,
                                )
                            },
                        ),
                ) {
                    composable(Routes.Cameras) { CamerasScreen(container, client, nav) }
                    composable(
                        route = Routes.Live,
                        arguments = listOf(navArgument("cameraId") { type = NavType.StringType }),
                    ) { backStack ->
                        val cameraId = backStack.arguments?.getString("cameraId").orEmpty()
                        LiveScreen(
                            container = container,
                            client = client,
                            cameraId = cameraId,
                            onBack = { nav.popBackStack() },
                            onAddStream = { nav.navigate(Routes.stream(cameraId)) },
                        )
                    }
                    composable(
                        route = Routes.Stream,
                        arguments = listOf(navArgument("cameraId") { type = NavType.StringType }),
                    ) { backStack ->
                        StreamSetupScreen(
                            container = container,
                            cameraId = backStack.arguments?.getString("cameraId").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable(Routes.Footage) { FootageScreen(container, client) }
                    composable(Routes.Network) {
                        NetworkScreen(container, onOpenRemote = { nav.navigate(Routes.Remote) { launchSingleTop = true } })
                    }
                    composable(Routes.Remote) {
                        RemoteScreen(container, onOpenSettings = { nav.navigate(Routes.Settings) { launchSingleTop = true } })
                    }
                    composable(Routes.Access) {
                        AccessScreen(container, onOpenRemote = { nav.navigate(Routes.Remote) { launchSingleTop = true } })
                    }
                    composable(Routes.Settings) { SettingsScreen(container) }
                    composable(Routes.Account) { AccountScreen(container) }
                }
                if (scrim > 0f) {
                    Box(modifier = Modifier.fillMaxSize().alpha(scrim).background(AptColors.RailScrim))
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(1.dp)
                        .onFocusChanged { anchorFocused = it.isFocused }
                        .focusRequester(pageAnchor)
                        .focusProperties { canFocus = anchorArmed }
                        .focusable(),
                )
            }
        }
        if (sleepOn) {
            SleepWall(
                container = container,
                focusRequester = sleepFocus,
                onToggle = { SleepPreview.open.value = false },
            )
        }
        if (overlayUp && !sleepOn) {
            AlertInsetHost(corner = settings.alertCorner) {
                AlertPopupGroup(
                    slots = popups,
                    prompt = showPrompt,
                    corner = settings.alertCorner,
                    compact = settings.alertStyle == AlertStyle.Notification,
                    size = settings.popupSize,
                    minimal = settings.popupLook == PopupLook.Minimal,
                    client = client,
                    onAnnounce = { cameraId, message ->
                        container.protect.announce(cameraId, settings.prefixSpeakerMessage(message))
                    },
                    phrasesFor = { forDoor -> settings.speakerPhrases(forDoor) },
                )
            }
        }
    }
}

@Composable
private fun SleepWall(
    container: AppContainer,
    focusRequester: FocusRequester,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AptColors.backgroundBrush)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .zIndex(4f),
    ) {
        CameraWall(
            container = container,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = AptSpace.overscanX,
                    end = AptSpace.overscanX,
                    top = AptSpace.overscanY,
                    bottom = 96.dp,
                ),
        )
        AptSwitchRow(
            label = "Sleep screen",
            description = "Showing the camera wall. Back leaves it.",
            checked = true,
            icon = AptIconKind.Camera,
            onToggle = onToggle,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = AptSpace.overscanX,
                    end = AptSpace.overscanX,
                    bottom = AptSpace.overscanY,
                )
                .focusRequester(focusRequester),
        )
    }
}

@Composable
private fun Rail(
    nav: NavHostController,
    route: String?,
    container: AppContainer,
    settings: AppSettings,
    open: Boolean,
    railFocus: FocusRequester,
    onClose: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val width by animateDpAsState(
        targetValue = if (open) AptSize.railExpanded else AptSize.railCollapsed,
        animationSpec = aptTween(AptMotion.slow, reduced),
        label = "rail-width",
    )
    val sheet by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = aptTween(AptMotion.slow, reduced),
        label = "rail-sheet",
    )
    val expanded = open
    val mode by container.protect.connectionMode.collectAsStateWithLifecycle(initialValue = ConnectionMode.Unconfigured)
    val cameras by container.protect.cameras().collectAsStateWithLifecycle(
        initialValue = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    val sheetShape = RoundedCornerShape(topEnd = AptRadius.sheet, bottomEnd = AptRadius.sheet)

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .shadow(elevation = 28.dp * sheet, shape = sheetShape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(AptColors.Sheet.copy(alpha = AptColors.Sheet.alpha * sheet), sheetShape)
            .border(1.dp, AptColors.Hairline.copy(alpha = AptColors.Hairline.alpha * sheet), sheetShape)
            .onKeyEvent { event ->
                val leave = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionRight || event.key == Key.Back || event.key == Key.Escape)
                if (open && leave) {
                    onClose()
                    true
                } else {
                    false
                }
            }
            .focusProperties {
                canFocus = false
                // While open, Up/Down at the ends of the rail stay in the rail instead of
                // jumping to whatever page control happens to sit above or below. Programmatic
                // requests (Enter), such as an alert popup taking focus, must still pass.
                onExit = { if (open && requestedFocusDirection.isDpad()) cancelFocusChange() }
            }
            .focusRestorer()
            .focusGroup()
            .padding(start = AptSpace.md, end = AptSpace.sm, top = AptSpace.overscanY, bottom = AptSpace.overscanY),
        verticalArrangement = Arrangement.spacedBy(AptSpace.xxs),
    ) {
        RailBrand(locationName = settings.locationName, expanded = expanded)
        Spacer(modifier = Modifier.height(AptSpace.lg))
        val focusRoute = if (route == Routes.Account) {
            Routes.Account
        } else {
            destinations.firstOrNull { destination ->
                val selected = route == destination.route ||
                    (destination.route == Routes.Cameras && route?.startsWith("live/") == true)
                val homeOnly = destination.route == Routes.Network && mode != ConnectionMode.OnNetwork
                selected && !homeOnly
            }?.route ?: Routes.Cameras
        }
        destinations.forEach { destination ->
            val selected = route == destination.route ||
                (destination.route == Routes.Cameras && route?.startsWith("live/") == true)
            val homeOnly = destination.route == Routes.Network && mode != ConnectionMode.OnNetwork
            RailItem(
                destination = destination,
                selected = selected && !homeOnly,
                expanded = expanded,
                enabled = !homeOnly,
                open = open,
                focusRequester = if (destination.route == focusRoute) railFocus else null,
                onClick = {
                    onClose()
                    nav.navigate(destination.route) { launchSingleTop = true }
                },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        RailItem(
            destination = accountDestination,
            selected = route == Routes.Account,
            expanded = expanded,
            open = open,
            focusRequester = if (route == Routes.Account) railFocus else null,
            onClick = {
                onClose()
                nav.navigate(Routes.Account) { launchSingleTop = true }
            },
        )
        Spacer(modifier = Modifier.height(AptSpace.xs))
        RailStatus(mode = mode, cameras = cameras, expanded = expanded)
    }
}

@Composable
private fun RailBrand(locationName: String, expanded: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 10.dp).padding(vertical = AptSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_uport_mark),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(32.dp).padding(2.dp),
        )
        if (expanded) {
            Column {
                Text(
                    "UPort",
                    fontSize = AptType.body,
                    fontWeight = FontWeight.SemiBold,
                    color = AptColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (locationName.isNotBlank() && locationName != "UPort") {
                    Text(
                        locationName,
                        fontSize = AptType.caption,
                        color = AptColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RailItem(
    destination: Destination,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    open: Boolean,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(AptRadius.chip)
    val tint = when {
        !enabled -> AptColors.Tertiary.copy(alpha = 0.6f)
        focused -> AptColors.OnBackground
        selected -> AptColors.Primary
        expanded -> AptColors.Secondary
        else -> AptColors.Tertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AptSize.control + 4.dp)
            .onFocusChanged { focused = it.isFocused }
            .aptFocusFrame(focused = focused, shape = shape, scaleTarget = AptMotion.focusScaleControl - 0.01f)
            .clip(shape)
            .background(
                when {
                    focused -> AptColors.GlassFocused
                    selected && expanded -> AptColors.GlassRaised
                    else -> Color.Transparent
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .focusProperties { canFocus = open && enabled }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .padding(start = 15.dp, end = AptSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AptSpace.md),
    ) {
        AptIcon(destination.icon, modifier = Modifier.size(22.dp), tint = tint)
        if (expanded) {
            Text(
                text = destination.label,
                fontSize = AptType.body,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                color = if (focused || selected) AptColors.OnBackground else tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                StatusDot(AptColors.Primary, size = 6.dp)
            }
        }
    }
}

@Composable
private fun RailStatus(mode: ConnectionMode, cameras: Feed<app.apt443.domain.Camera>, expanded: Boolean) {
    val online = cameras.items.count { it.online }
    val tone = when (mode) {
        ConnectionMode.OnNetwork -> if (cameras.availability is Availability.Ready) StatusTone.Online else StatusTone.Warning
        ConnectionMode.Remote -> StatusTone.Warning
        ConnectionMode.Demo -> StatusTone.Info
        ConnectionMode.Unconfigured -> StatusTone.Danger
    }
    val label = when (mode) {
        ConnectionMode.OnNetwork -> "Connected"
        ConnectionMode.Remote -> "Remote"
        ConnectionMode.Demo -> "Demo"
        ConnectionMode.Unconfigured -> "Not connected"
    }
    val detail = when {
        cameras.items.isEmpty() -> null
        else -> "$online of ${cameras.items.size} cameras"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(
                if (expanded) {
                    Modifier
                        .border(1.dp, AptColors.Hairline, RoundedCornerShape(AptRadius.row))
                        .background(AptColors.Glass, RoundedCornerShape(AptRadius.row))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = AptSpace.md, vertical = AptSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.spacedBy(AptSpace.sm) else Arrangement.Start,
    ) {
        Box(modifier = Modifier.padding(start = if (expanded) 0.dp else 4.dp)) {
            StatusDot(tone.color(), size = 10.dp)
        }
        if (expanded) {
            Column {
                Text(
                    label,
                    fontSize = AptType.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = tone.color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        detail,
                        fontSize = AptType.caption,
                        color = AptColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
