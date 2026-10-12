package app.apt443.alert

import android.app.Service
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.apt443.Apt443Application
import app.apt443.R
import app.apt443.domain.AlertCorner
import app.apt443.domain.AlertStyle
import app.apt443.domain.AppSettings
import app.apt443.domain.PopupLayout
import app.apt443.domain.PopupLook
import app.apt443.domain.PopupSize
import app.apt443.domain.speakerPhrases
import app.apt443.ui.components.AlertPopupGroup
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.Apt443Theme

/**
 * One alert drawn in its own window, placed by the viewer's corner setting.
 *
 * The same host serves two window types: [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * where the "draw over other apps" permission exists, and
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] on devices such as Fire TV where it
 * does not. The window is only as large as the card, so the rest of the screen keeps its input.
 */
internal class AlertWindowHost(
    private val service: Service,
    private val windowType: Int,
) {
    private var composeView: ComposeView? = null
    private var owner: AlertWindowOwner? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var placedCorner: AlertCorner? = null
    private var placedStyle: AlertStyle? = null
    private var placedSize: PopupSize? = null
    private var placedLook: PopupLook? = null
    private var placedPanes: Int = 0
    private var fittedWidthPx: Int = 0
    private var fittedHeightPx: Int = 0
    private var reclaiming = false
    private var interactiveWindow = true

    val attached: Boolean get() = composeView != null

    /** Shows the alert window, returning false when the system refused it. */
    fun show(settings: AppSettings, panes: Int = 1, interactive: Boolean = true): Boolean {
        val existing = composeView
        val count = panes.coerceIn(1, PopupLayout.MAX_OPEN + 1)
        if (existing != null && placedCorner == settings.alertCorner && placedStyle == settings.alertStyle) {
            applyPlacement(settings, count)
            val resumeFocus = existing.visibility != View.VISIBLE
            existing.visibility = View.VISIBLE
            applyInteractive(interactive)
            if (resumeFocus && interactive) kickFocus()
            return true
        }
        remove()
        val host = AlertWindowOwner()
        val themed = ContextThemeWrapper(service, R.style.Theme_Apt443)
        val view = ComposeView(themed).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = true
            clipToPadding = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setContent { AlertWindowContent() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addOnUnhandledKeyEventListener { _, event ->
                    event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                        event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                        event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                        event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                }
            }
            setOnFocusChangeListener { focused, hasFocus ->
                if (hasFocus || reclaiming || !interactiveWindow || focused.visibility != View.VISIBLE) {
                    return@setOnFocusChangeListener
                }
                focused.post {
                    if (composeView == null || reclaiming || !interactiveWindow || focused.visibility != View.VISIBLE) return@post
                    kickFocus()
                }
            }
        }
        val placement = alertWindowPlacement(
            service,
            settings.alertCorner,
            settings.alertStyle,
            count,
            settings.popupSize,
            settings.popupLook == PopupLook.Minimal,
        )
        val layout = WindowManager.LayoutParams(
            placement.widthPx,
            placement.heightPx,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = placement.gravity
            x = placement.marginX
            y = placement.marginY
            title = "UPort alert"
        }
        val manager = service.getSystemService(Service.WINDOW_SERVICE) as WindowManager
        return try {
            host.create()
            manager.addView(view, layout)
            host.resume()
            windowManager = manager
            params = layout
            composeView = view
            owner = host
            placedCorner = settings.alertCorner
            placedStyle = settings.alertStyle
            placedSize = settings.popupSize
            placedLook = settings.popupLook
            placedPanes = count
            applyInteractive(interactive)
            if (interactive) kickFocus()
            true
        } catch (error: Exception) {
            Log.w(TAG, "Alert window rejected (type $windowType)", error)
            host.destroy()
            clearState()
            false
        }
    }

    fun hide() {
        composeView?.visibility = View.INVISIBLE
    }

    fun remove() {
        val view = composeView ?: return
        owner?.destroy()
        runCatching { windowManager?.removeView(view) }
        clearState()
    }

    private fun clearState() {
        composeView = null
        owner = null
        windowManager = null
        params = null
        placedCorner = null
        placedStyle = null
        placedSize = null
        placedLook = null
        placedPanes = 0
        fittedWidthPx = 0
        fittedHeightPx = 0
    }

    /**
     * The popup row reports its measured size (pictures, bars, an open action row, the prompt)
     * so the window hugs it instead of the estimate from [alertWindowPlacement].
     */
    fun fitToContent(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (fittedWidthPx == widthPx && fittedHeightPx == heightPx) return
        fittedWidthPx = widthPx
        fittedHeightPx = heightPx
        val layout = params ?: return
        val manager = windowManager ?: return
        val view = composeView ?: return
        val metrics = service.resources.displayMetrics
        layout.width = widthPx.coerceIn(1, metrics.widthPixels)
        layout.height = heightPx.coerceIn(1, metrics.heightPixels)
        runCatching { manager.updateViewLayout(view, layout) }
        if (interactiveWindow && !view.hasFocus()) view.requestFocus()
    }

    /** Minimal pictures pass the remote through. A card or the silence prompt takes it. */
    private fun applyInteractive(interactive: Boolean) {
        interactiveWindow = interactive
        val layout = params ?: return
        val manager = windowManager ?: return
        val view = composeView ?: return
        val blocked = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val next = if (interactive) layout.flags and blocked.inv() else layout.flags or blocked
        if (next == layout.flags) return
        layout.flags = next
        runCatching { manager.updateViewLayout(view, layout) }
    }

    private fun applyPlacement(settings: AppSettings, panes: Int) {
        val view = composeView ?: return
        val manager = windowManager ?: return
        val layout = params ?: return
        val placement = alertWindowPlacement(
            service,
            settings.alertCorner,
            settings.alertStyle,
            panes,
            settings.popupSize,
            settings.popupLook == PopupLook.Minimal,
        )
        if (placedLook != settings.popupLook || placedSize != settings.popupSize) {
            fittedWidthPx = 0
            fittedHeightPx = 0
        }
        val fitted = fittedWidthPx > 0 && fittedHeightPx > 0
        layout.width = if (fitted) fittedWidthPx else placement.widthPx
        layout.height = if (fitted) fittedHeightPx else placement.heightPx
        layout.gravity = placement.gravity
        layout.x = placement.marginX
        layout.y = placement.marginY
        runCatching { manager.updateViewLayout(view, layout) }
        placedPanes = panes
        placedSize = settings.popupSize
        placedLook = settings.popupLook
    }

    /**
     * Fire TV hands D-pad focus to a new window only after it has settled, so the window gives
     * focus up for a moment and takes it back. Without this the remote keeps driving the app
     * underneath and the alert buttons are unreachable.
     */
    fun claimFocus() = kickFocus()

    private fun kickFocus() {
        if (!interactiveWindow) return
        val view = composeView ?: return
        val manager = windowManager ?: return
        val layout = params ?: return
        reclaiming = true
        view.post {
            try {
                layout.flags = layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                manager.updateViewLayout(view, layout)
                view.postDelayed({
                    layout.flags = layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    runCatching { manager.updateViewLayout(view, layout) }
                    view.requestFocus()
                    view.post { reclaiming = false }
                }, FOCUS_SETTLE_MS)
            } catch (error: Exception) {
                Log.w(TAG, "Alert window focus kick failed", error)
                view.requestFocus()
                reclaiming = false
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun AlertWindowContent() {
        val container = (service.application as Apt443Application).container
        val settings by container.settings.collectAsState(initial = AppSettings())
        val client = remember(settings.consoleHost, settings.trustConsoleCertificate) {
            container.http.clientFor(settings)
        }
        val slots by AlertController.slots.collectAsState()
        val prompt by QuietPromptController.prompt.collectAsState()
        val dreaming by DreamVisibility.active.collectAsState()
        Apt443Theme {
            if (!dreaming && (slots.isNotEmpty() || prompt != null)) AlertPopupGroup(
                slots = slots,
                prompt = prompt,
                corner = settings.alertCorner,
                compact = settings.alertStyle == AlertStyle.Notification,
                size = settings.popupSize,
                minimal = settings.popupLook == PopupLook.Minimal,
                client = client,
                onAnnounce = { cameraId, message -> AlertController.announce(cameraId, message) },
                phrasesFor = { forDoor -> settings.speakerPhrases(forDoor) },
                onGroupSize = { widthDp, heightDp ->
                    val density = service.resources.displayMetrics.density
                    fitToContent(
                        kotlin.math.ceil(widthDp * density).toInt(),
                        kotlin.math.ceil(heightDp * density).toInt(),
                    )
                },
            )
        }
    }

    private companion object {
        const val TAG = "AlertWindowHost"
        const val FOCUS_SETTLE_MS = 100L
    }
}

/** Where and how large one alert window sits, in pixels. */
internal data class AlertWindowPlacement(
    val gravity: Int,
    val widthPx: Int,
    val heightPx: Int,
    val marginX: Int,
    val marginY: Int,
)

/**
 * The one place a corner setting turns into window geometry. Every alert surface routes through
 * here so the setting means the same thing whichever path delivered the alert.
 */
internal fun alertWindowPlacement(
    service: Service,
    corner: AlertCorner,
    style: AlertStyle,
    panes: Int = 1,
    size: PopupSize = PopupSize.Small,
    minimal: Boolean = false,
): AlertWindowPlacement {
    val metrics = service.resources.displayMetrics
    val density = metrics.density
    fun px(dp: Float): Int = (dp * density).toInt()
    val count = panes.coerceIn(1, PopupLayout.MAX_OPEN + 1)
    val screenWidthDp = metrics.widthPixels / density
    val screenHeightDp = metrics.heightPixels / density
    val compact = style == AlertStyle.Notification
    val card = PopupLayout.cardWidthDp(
        screenWidthDp = screenWidthDp,
        screenHeightDp = screenHeightDp,
        panes = count,
        compact = compact,
        size = size,
    )
    val width = px(PopupLayout.groupWidthDp(card, count))
    val heightDp = if (minimal && !compact) {
        PopupLayout.minimalHeightDp(size, screenWidthDp, screenHeightDp)
    } else {
        PopupLayout.cardHeightDp(card, compact)
    }
    val height = px(heightDp)
    val centered = corner == AlertCorner.Center
    return AlertWindowPlacement(
        gravity = corner.toWindowGravity(),
        widthPx = width.coerceAtMost(metrics.widthPixels),
        heightPx = height.coerceAtLeast(1),
        marginX = if (centered || corner.isHorizontallyCentered()) 0 else px(AptSpace.overscanX.value),
        marginY = if (centered) 0 else px(AptSpace.overscanY.value),
    )
}

internal fun AlertCorner.toWindowGravity(): Int = when (this) {
    AlertCorner.TopLeft -> Gravity.TOP or Gravity.START
    AlertCorner.TopCenter -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
    AlertCorner.TopRight -> Gravity.TOP or Gravity.END
    AlertCorner.Center -> Gravity.CENTER
    AlertCorner.BottomLeft -> Gravity.BOTTOM or Gravity.START
    AlertCorner.BottomCenter -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    AlertCorner.BottomRight -> Gravity.BOTTOM or Gravity.END
}

private fun AlertCorner.isHorizontallyCentered(): Boolean =
    this == AlertCorner.TopCenter || this == AlertCorner.BottomCenter

/** Minimal owner set so Compose can run inside a window that has no Activity behind it. */
private class AlertWindowOwner :
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
