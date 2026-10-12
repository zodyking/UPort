package app.apt443.alert

import android.app.Service
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.apt443.data.AppContainer
import app.apt443.domain.AppSettings
import app.apt443.domain.Availability
import app.apt443.domain.Feed
import app.apt443.domain.PersistentCamera
import app.apt443.domain.PopupLook
import app.apt443.domain.PopupPicture
import app.apt443.ui.components.AptIcon
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.player.LiveStreamTile
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSize
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import app.apt443.ui.theme.Apt443Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Always-on camera pictures. The window covers the screen but takes no touch or focus,
 * so the remote keeps driving whatever is underneath. Turn a camera off from Settings.
 */
internal object PersistentOverlays {
    private var window: PersistentOverlayWindow? = null

    fun show(service: Service, windowType: Int) {
        val current = window
        if (current != null && current.ownedBy(service, windowType)) return
        current?.stop()
        window = PersistentOverlayWindow(service, windowType).also { it.start() }
    }

    fun stop(service: Service) {
        val current = window ?: return
        if (!current.ownedBy(service)) return
        current.stop()
        window = null
    }
}

private class PersistentOverlayWindow(
    private val service: Service,
    private val windowType: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var composeView: ComposeView? = null
    private var owner: PersistentOwner? = null
    private var windowManager: WindowManager? = null

    fun ownedBy(service: Service, windowType: Int? = null): Boolean =
        this.service === service && (windowType == null || this.windowType == windowType)

    fun start() {
        if (job != null) return
        val container = (service.application as Apt443Application).container
        job = scope.launch {
            combine(container.settings, DreamVisibility.active) { settings, dreaming ->
                settings to dreaming
            }.collect { (settings, dreaming) ->
                if (dreaming || !settings.siteConfigured || settings.persistentCameras.isEmpty()) {
                    removeView()
                } else {
                    ensureView()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        removeView()
        scope.cancel()
    }

    private fun ensureView() {
        if (composeView != null) return
        val host = PersistentOwner()
        val themed = ContextThemeWrapper(service, R.style.Theme_Apt443)
        val view = ComposeView(themed).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            isFocusable = false
            isFocusableInTouchMode = false
            setContent { PersistentOverlayContent(service) }
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { title = "UPort cameras" }
        val manager = service.getSystemService(Service.WINDOW_SERVICE) as WindowManager
        try {
            host.create()
            manager.addView(view, layout)
            host.resume()
            windowManager = manager
            composeView = view
            owner = host
        } catch (error: Exception) {
            Log.w(TAG, "Always-on window rejected", error)
            host.destroy()
        }
    }

    private fun removeView() {
        val view = composeView ?: return
        owner?.destroy()
        runCatching { windowManager?.removeView(view) }
        composeView = null
        owner = null
        windowManager = null
    }

    private companion object {
        const val TAG = "PersistentOverlay"
    }
}

@Composable
private fun PersistentOverlayContent(service: Service) {
    val container = (service.application as Apt443Application).container
    val settings by container.settings.collectAsState(initial = AppSettings())
    val cameras by container.protect.cameras().collectAsState(
        initial = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    val client = remember(settings.consoleHost, settings.trustConsoleCertificate) {
        container.http.clientFor(settings)
    }
    val names = cameras.items.associate { it.id to it.name }
    Apt443Theme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            settings.persistentCameras.forEach { item ->
                val placed = item.placed()
                PersistentPane(
                    item = placed,
                    name = names[placed.cameraId]?.ifBlank { null } ?: "Camera",
                    settings = settings,
                    container = container,
                    client = client,
                    modifier = Modifier
                        .offset(x = maxWidth * placed.x, y = maxHeight * placed.y)
                        .size(width = maxWidth * placed.width, height = maxHeight * placed.height),
                )
            }
        }
    }
}

@Composable
private fun PersistentPane(
    item: PersistentCamera,
    name: String,
    settings: AppSettings,
    container: AppContainer,
    client: OkHttpClient,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(AptRadius.sheet)
    if (item.look == PopupLook.Minimal) {
        var ratio by remember(item.cameraId) { mutableFloatStateOf(16f / 9f) }
        BoxWithConstraints(modifier = modifier) {
            val safe = ratio.takeIf { it.isFinite() && it in 0.25f..4f } ?: (16f / 9f)
            val boxRatio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else safe
            val frame = if (safe >= boxRatio) {
                Modifier.width(maxWidth).height(maxWidth / safe)
            } else {
                Modifier.height(maxHeight).width(maxHeight * safe)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(frame)
                    .clip(RoundedCornerShape(AptRadius.card))
                    .background(AptColors.PreviewWell),
            ) {
                PersistentPicture(
                    item,
                    settings,
                    container,
                    client,
                    Modifier.fillMaxSize(),
                    onAspect = { next ->
                        if (next.isFinite() && next in 0.25f..4f) ratio = next
                    },
                )
                Text(
                    text = name,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(AptSpace.xxs)
                        .background(AptColors.ScrimSoft, RoundedCornerShape(AptRadius.chip))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AptColors.OnVideo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(AptColors.Scrim),
    ) {
        PersistentPicture(
            item,
            settings,
            container,
            client,
            Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AptSpace.sm, vertical = AptSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
        ) {
            AptIcon(AptIconKind.Camera, modifier = Modifier.size(AptSize.iconSmall), tint = AptColors.OnVideo)
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                fontSize = AptType.caption,
                fontWeight = FontWeight.Bold,
                color = AptColors.OnVideo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PersistentPicture(
    item: PersistentCamera,
    settings: AppSettings,
    container: AppContainer,
    client: OkHttpClient,
    modifier: Modifier,
    onAspect: (Float) -> Unit = {},
) {
    if (item.picture == PopupPicture.Live) {
        LiveStreamTile(
            cameraId = item.cameraId,
            quality = settings.pipStreamQuality,
            container = container,
            client = client,
            modifier = modifier,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            showSpinner = false,
            onAspect = onAspect,
        )
        return
    }
    var bitmap by remember(item.cameraId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.cameraId) {
        while (isActive) {
            val next = runCatching { container.protect.previewJpeg(item.cameraId, highQuality = false) }.getOrNull()
            bitmap = if (next == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(next, 0, next.size)?.asImageBitmap()
                }
            }
            delay(SNAPSHOT_MS)
        }
    }
    LaunchedEffect(bitmap) {
        val image = bitmap ?: return@LaunchedEffect
        if (image.width > 0 && image.height > 0) onAspect(image.width.toFloat() / image.height.toFloat())
    }
    val image = bitmap
    Box(modifier = modifier.background(AptColors.PreviewWell), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private const val SNAPSHOT_MS = 4_000L

private class PersistentOwner :
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
