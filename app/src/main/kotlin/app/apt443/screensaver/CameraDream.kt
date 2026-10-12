package app.apt443.screensaver

import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.SweepGradient
import android.service.dreams.DreamService
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.Text
import app.apt443.Apt443Application
import app.apt443.R
import app.apt443.alert.AlertController
import app.apt443.alert.DreamVisibility
import app.apt443.data.AppContainer
import app.apt443.domain.AppSettings
import app.apt443.domain.Availability
import app.apt443.domain.Camera
import app.apt443.domain.Feed
import app.apt443.domain.StreamQuality
import app.apt443.domain.coerceFadeSeconds
import app.apt443.ui.player.LiveStreamTile
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import app.apt443.ui.theme.Apt443Theme
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Idle screen: cameras in pages of nine, no buttons. Any remote key leaves it. The panel stays lit. */
class CameraDream : DreamService() {
    private var owner: DreamOwner? = null
    private var gate: Job? = null

    override fun onAttachedToWindow() {
        if (!StayAwake.cameraScreensaverOn) {
            super.onAttachedToWindow()
            finish()
            return
        }
        gate = MainScope().launch {
            if (!(application as Apt443Application).container.settings.first().cameraScreensaver) finish()
        }
        DreamVisibility.showingDream = true
        super.onAttachedToWindow()
        isFullscreen = true
        isScreenBright = true
        isInteractive = false
        val host = DreamOwner()
        val themed = ContextThemeWrapper(this, R.style.Theme_Apt443)
        val view = ComposeView(themed).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            isFocusable = false
            setBackgroundColor(AndroidColor.BLACK)
            setContent { DreamCameras(application as Apt443Application) }
        }
        host.create()
        setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.resume()
        owner = host
    }

    override fun onDetachedFromWindow() {
        DreamVisibility.showingDream = false
        owner?.destroy()
        owner = null
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        gate?.cancel()
        gate = null
        DreamVisibility.showingDream = false
        owner?.destroy()
        owner = null
        super.onDestroy()
    }
}

@Composable
private fun DreamCameras(application: Apt443Application) {
    val container = application.container
    val settings by container.settings.collectAsState(initial = AppSettings())
    val feed by container.protect.cameras().collectAsState(initial = Feed(emptyList(), Availability.NeedsSetup("")))
    val events by container.protect.events().collectAsState(initial = Feed(emptyList(), Availability.NeedsSetup("")))
    val slots by AlertController.slots.collectAsState()
    val client = remember(settings.consoleHost, settings.trustConsoleCertificate) {
        container.http.clientFor(settings)
    }
    val cameras = feed.items.filter { it.id.isNotBlank() }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lingerMs = coerceFadeSeconds(settings.alertFadeSeconds) * 1_000L
    // Open AlertController slots, plus Protect events that are still open or inside the popup fade window.
    val activeIds = remember(slots, events.items, lingerMs, nowMs, cameras) {
        val known = cameras.map { it.id }.toSet()
        activeDreamCameraIds(
            slotCameraIds = slots.mapNotNull { it.alert.cameraId?.takeIf { id -> id.isNotBlank() } }.toSet(),
            events = events.items,
            nowMs = nowMs,
            lingerMs = lingerMs,
        ).filter { it in known }.toSet()
    }
    val boardState = remember { mutableStateOf(DreamBoard()) }
    val boardLock = remember { Any() }
    val camerasState = rememberUpdatedState(cameras)
    val activeState = rememberUpdatedState(activeIds)
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_000)
            val stampedAt = System.currentTimeMillis()
            nowMs = stampedAt
            val active = activeState.value
            if (active.isEmpty()) continue
            synchronized(boardLock) {
                boardState.value = noteDreamActivity(boardState.value, active, stampedAt)
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { camerasState.value.map { it.id } to activeState.value }
            .collectLatest { (ids, active) ->
                val started = System.currentTimeMillis()
                synchronized(boardLock) {
                    boardState.value = reconcileDreamBoard(boardState.value, ids, active, started)
                }
                if (ids.size <= DREAM_PAGE_SIZE || active.isNotEmpty()) return@collectLatest
                while (isActive) {
                    delay(DREAM_CYCLE_MS)
                    val latestIds = camerasState.value.map { it.id }
                    val latestActive = activeState.value
                    if (latestIds.size <= DREAM_PAGE_SIZE || latestActive.isNotEmpty()) return@collectLatest
                    synchronized(boardLock) {
                        boardState.value = cycleDreamBoard(boardState.value, latestIds)
                    }
                }
            }
    }
    val board = boardState.value
    val byId = cameras.associateBy { it.id }
    val visible = board.visibleIds.mapNotNull { byId[it] }.ifEmpty { cameras.take(DREAM_PAGE_SIZE) }
    val ringTurn = rememberDreamRingTurn()
    Apt443Theme {
        Box(modifier = Modifier.fillMaxSize().background(AptColors.Background)) {
            if (cameras.isEmpty()) {
                Text(
                    text = "Waiting for cameras",
                    modifier = Modifier.align(Alignment.Center),
                    fontSize = AptType.title,
                    color = AptColors.Secondary,
                )
            } else {
                DreamGrid(
                    cameras = visible,
                    activeIds = activeIds,
                    ringTurn = ringTurn,
                    container = container,
                    client = client,
                )
            }
        }
    }
}

@Composable
private fun rememberDreamRingTurn(): Float {
    val transition = rememberInfiniteTransition(label = "dream-rgb")
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dream-rgb-turn",
    )
    return turn
}

/** Colors travel around a fixed border. The card itself does not spin. */
private fun chasingBorder(turn: Float): ShaderBrush = object : ShaderBrush() {
    override fun createShader(size: Size): android.graphics.Shader {
        val shader = SweepGradient(
            size.width / 2f,
            size.height / 2f,
            intArrayOf(
                0xFFFF3B30.toInt(),
                0xFFFFCC00.toInt(),
                0xFF34C759.toInt(),
                0xFF32ADE6.toInt(),
                0xFF5E5CE6.toInt(),
                0xFFFF2D55.toInt(),
                0xFFFF3B30.toInt(),
            ),
            null,
        )
        shader.setLocalMatrix(
            Matrix().apply { setRotate(turn, size.width / 2f, size.height / 2f) },
        )
        return shader
    }
}

@Composable
private fun DreamGrid(
    cameras: List<Camera>,
    activeIds: Set<String>,
    ringTurn: Float,
    container: AppContainer,
    client: OkHttpClient,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AptSpace.sm),
        verticalArrangement = Arrangement.spacedBy(AptSpace.sm),
    ) {
        dreamGrid(cameras.map { it.id }).forEach { row ->
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
            ) {
                row.forEach { id ->
                    val camera = id?.let { cellId -> cameras.firstOrNull { it.id == cellId } }
                    if (camera == null) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight())
                    } else {
                        DreamCell(
                            camera = camera,
                            activeIds = activeIds,
                            ringTurn = ringTurn,
                            container = container,
                            client = client,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DreamCell(
    camera: Camera,
    activeIds: Set<String>,
    ringTurn: Float,
    container: AppContainer,
    client: OkHttpClient,
) {
    var shown by remember { mutableStateOf(camera) }
    val alpha = remember { Animatable(1f) }
    val nudge = remember { Animatable(0f) }
    LaunchedEffect(camera.id) {
        if (shown.id == camera.id) {
            shown = camera
            alpha.snapTo(1f)
            nudge.snapTo(0f)
            return@LaunchedEffect
        }
        alpha.animateTo(0f, tween(320))
        nudge.animateTo(-28f, tween(320))
        shown = camera
        nudge.snapTo(28f)
        alpha.animateTo(1f, tween(460))
        nudge.animateTo(0f, tween(460))
    }
    val displayed = if (shown.id == camera.id) camera else shown
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .graphicsLayer {
                this.alpha = alpha.value
                translationX = nudge.value
            },
    ) {
        DreamTile(
            camera = displayed,
            container = container,
            client = client,
            detecting = displayed.id in activeIds,
            ringTurn = ringTurn,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun DreamTile(
    camera: Camera,
    container: AppContainer,
    client: OkHttpClient,
    detecting: Boolean,
    ringTurn: Float,
    modifier: Modifier,
) {
    val stroke = 6.dp
    val corner = AptRadius.tile
    Box(
        modifier = modifier.drawWithContent {
            drawContent()
            if (detecting) {
                val strokePx = stroke.toPx()
                val half = strokePx / 2f
                val frame = Size(size.width - strokePx, size.height - strokePx)
                drawRoundRect(
                    brush = chasingBorder(ringTurn),
                    topLeft = Offset(half, half),
                    size = frame,
                    cornerRadius = CornerRadius(corner.toPx(), corner.toPx()),
                    style = Stroke(width = strokePx),
                )
            }
        },
    ) {
        LiveStreamTile(
            cameraId = camera.id,
            quality = StreamQuality.Low,
            container = container,
            client = client,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            showSpinner = false,
            enabled = camera.online,
        )
        Text(
            text = camera.name.ifBlank { "Camera" },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(AptSpace.xs),
            fontSize = AptType.label,
            fontWeight = FontWeight.SemiBold,
            color = AptColors.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private class DreamOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    init {
        savedStateRegistryController.performAttach()
    }

    fun create() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun resume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        viewModelStore.clear()
    }
}
