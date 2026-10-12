@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.apt443.ui.player

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.apt443.data.AppContainer
import app.apt443.domain.Availability
import app.apt443.domain.StreamQuality
import app.apt443.ui.theme.AptColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private sealed interface PlayerSignal {
    val attempt: Int

    data class Live(override val attempt: Int) : PlayerSignal

    data class Failed(override val attempt: Int, val decoder: Boolean, val message: String) : PlayerSignal
}

private sealed interface UrlResult {
    data class Url(val url: String) : UrlResult

    data class Unavailable(val message: String, val terminal: Boolean) : UrlResult
}

/**
 * Always-live camera tile: poster + spinner while connecting, aspect-fit video once the first
 * frame renders, infinite backoff retries, and per-tile snapshot mode after a decoder failure.
 *
 * Player identity is keyed strictly on (url, attempt). Changing [cameraId] or [quality] resets
 * the tile. The player is released when the tile leaves composition or the host lifecycle
 * drops below STARTED.
 *
 * @param enabled false keeps only the poster (e.g. camera offline); phase reports [StreamPhase.Failed].
 * @param playing false does not open a stream or refresh snapshots. The live grid uses this for
 *   tiles that are not on screen.
 * @param highQualityPoster request the high quality snapshot (full-screen viewers).
 * @param onDecoderFailure called on a decoder failure before switching to snapshots; return
 *   true if the caller handles it (e.g. by lowering [quality]), false to enter SnapshotOnly.
 * @param onMessage user-facing reason when no URL is offered or playback fails.
 * @param placeholder drawn when no poster is available yet.
 * @param overlay drawn above video and poster (labels, badges); receives the current phase.
 */
@Composable
fun LiveStreamTile(
    cameraId: String,
    quality: StreamQuality,
    container: AppContainer,
    client: OkHttpClient,
    modifier: Modifier = Modifier,
    surfaceType: Int = SURFACE_TYPE_TEXTURE_VIEW,
    showSpinner: Boolean = true,
    enabled: Boolean = true,
    playing: Boolean = true,
    paused: Boolean = false,
    highQualityPoster: Boolean = false,
    onPhase: (StreamPhase) -> Unit = {},
    onMessage: (String) -> Unit = {},
    onDecoderFailure: () -> Boolean = { false },
    placeholder: @Composable BoxScope.() -> Unit = {},
    overlay: @Composable BoxScope.(StreamPhase) -> Unit = {},
    crop: Boolean = false,
    onAspect: (Float) -> Unit = {},
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val active = enabled && playing && foreground && cameraId.isNotBlank()

    val policy = remember(cameraId, quality) { StreamRetryPolicy() }
    var phase by remember(cameraId, quality) { mutableStateOf(StreamPhase.Connecting) }
    var url by remember(cameraId, quality) { mutableStateOf<String?>(null) }
    var attempt by remember(cameraId, quality) { mutableIntStateOf(0) }
    var ratio by remember(cameraId) { mutableFloatStateOf(16f / 9f) }
    var poster by remember(cameraId) { mutableStateOf<ImageBitmap?>(null) }
    val signals = remember(cameraId, quality) { Channel<PlayerSignal>(Channel.UNLIMITED) }

    val phaseCallback by rememberUpdatedState(onPhase)
    val aspectCallback by rememberUpdatedState(onAspect)
    val messageCallback by rememberUpdatedState(onMessage)
    val decoderCallback by rememberUpdatedState(onDecoderFailure)

    LaunchedEffect(phase) { phaseCallback(phase) }
    LaunchedEffect(ratio) { aspectCallback(ratio) }

    LaunchedEffect(cameraId, quality, active) {
        if (!active) {
            url = null
            if (!enabled) {
                policy.onDisabled()
                phase = policy.phase
            }
            return@LaunchedEffect
        }
        policy.onResume()
        phase = policy.phase
        var known: String? = null
        var forceFetch = false
        while (isActive) {
            if (known == null || forceFetch) {
                when (val result = obtainUrl(container, cameraId, quality, force = forceFetch)) {
                    is UrlResult.Url -> {
                        known = result.url
                        forceFetch = false
                    }
                    is UrlResult.Unavailable -> {
                        known = null
                        url = null
                        if (result.message.isNotBlank()) messageCallback(result.message)
                        val step = policy.onUrlUnavailable(result.terminal)
                        phase = policy.phase
                        delay(step.delayMs)
                        continue
                    }
                }
            }
            val current = policy.beginAttempt()
            attempt = current
            url = known
            if (policy.phase != StreamPhase.SnapshotOnly) phase = policy.phase
            val failure = awaitFailure(signals, current) {
                policy.onLive()
                phase = StreamPhase.Live
            }
            url = null
            if (failure.message.isNotBlank()) messageCallback(failure.message)
            if (failure.decoder && policy.phase != StreamPhase.SnapshotOnly && decoderCallback()) {
                awaitCancellation()
            }
            val step = policy.onPlaybackFailure(failure.decoder)
            phase = policy.phase
            if (step is RetryStep.RetryLive && step.refetchUrl) {
                StreamUrlCache.invalidate(cameraId, quality)
                forceFetch = true
            }
            delay(step.delayMs)
        }
    }

    LaunchedEffect(cameraId, phase, foreground, highQualityPoster, playing) {
        if (!playing || !foreground || cameraId.isBlank()) return@LaunchedEffect
        // The live grid used to download a snapshot for every camera while the streams
        // were still starting. That queue is what made the page slow. Snapshots stay on
        // the full-screen viewer and on tiles that have already fallen back to pictures.
        val wantPoster = phase == StreamPhase.SnapshotOnly || (highQualityPoster && phase != StreamPhase.Live)
        if (!wantPoster) return@LaunchedEffect
        if (phase == StreamPhase.SnapshotOnly) {
            while (isActive) {
                loadPoster(container, cameraId, highQualityPoster)?.let { poster = it }
                delay(policy.snapshotRefreshMs)
            }
        } else {
            loadPoster(container, cameraId, highQualityPoster)?.let { poster = it }
        }
    }

    Box(
        modifier = modifier.background(AptColors.PreviewWell),
        contentAlignment = Alignment.Center,
    ) {
        val playing = url
        if (active && playing != null) {
            val frame: @Composable (@Composable (Modifier) -> Unit) -> Unit = { content ->
                if (crop) {
                    AspectCover(ratio = ratio, modifier = Modifier.fillMaxSize(), content = content)
                } else {
                    AspectFit(ratio = ratio, modifier = Modifier.fillMaxSize(), content = content)
                }
            }
            frame { playerFrame ->
                LivePlayerSurface(
                    url = playing,
                    attempt = attempt,
                    client = client,
                    surfaceType = surfaceType,
                    connectTimeoutMs = policy.connectTimeoutMs,
                    stallTimeoutMs = policy.stallTimeoutMs,
                    paused = paused,
                    modifier = playerFrame,
                    onAspect = { next ->
                        if (next.isFinite() && next in 0.25f..4f) ratio = next
                    },
                    onSignal = { signals.trySend(it) },
                )
            }
        }
        if (phase != StreamPhase.Live) {
            StreamPosterLayer(
                poster = poster,
                spinning = showSpinner && active &&
                    (phase == StreamPhase.Connecting || phase == StreamPhase.Reconnecting),
                modifier = Modifier.fillMaxSize(),
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                placeholder = placeholder,
            )
        }
        overlay(phase)
    }
}

/** Snapshot (aspect-fit, centered) with an optional centered spinner. Reusable by any viewer. */
@Composable
fun StreamPosterLayer(
    poster: ImageBitmap?,
    spinning: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    placeholder: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (poster != null) {
            Image(
                bitmap = poster,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            placeholder()
        }
        if (spinning) {
            StreamSpinner()
        }
    }
}

@Composable
fun StreamSpinner(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val transition = rememberInfiniteTransition(label = "stream-spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "stream-spinner-angle",
    )
    Box(
        modifier = modifier
            .size(size + 16.dp)
            .background(AptColors.Scrim.copy(alpha = 0.55f), CircleShape)
            .semantics { contentDescription = "Connecting" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size).rotate(angle)) {
            val stroke = 3.dp.toPx()
            drawArc(
                color = AptColors.OnVideo.copy(alpha = 0.25f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = AptColors.OnVideo,
                startAngle = 0f,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** Fills the container and clips the overflow, so letterbox bars are not shown. */
@Composable
fun AspectCover(
    ratio: Float,
    modifier: Modifier = Modifier,
    content: @Composable (frame: Modifier) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        if (maxWidth <= 0.dp || maxHeight <= 0.dp) return@BoxWithConstraints
        val safeRatio = ratio.takeIf { it > 0f && it.isFinite() } ?: (16f / 9f)
        val containerRatio = maxWidth / maxHeight
        val frame = if (safeRatio >= containerRatio) {
            Modifier.fillMaxHeight().width(maxHeight * safeRatio)
        } else {
            Modifier.fillMaxWidth().height(maxWidth / safeRatio)
        }
        content(frame)
    }
}

/** Largest centered frame with [ratio] that fits the container, no cropping or stretching. */
@Composable
fun AspectFit(
    ratio: Float,
    modifier: Modifier = Modifier,
    content: @Composable (frame: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        if (maxWidth <= 0.dp || maxHeight <= 0.dp) return@BoxWithConstraints
        val safeRatio = ratio.takeIf { it > 0f && it.isFinite() } ?: (16f / 9f)
        val containerRatio = maxWidth / maxHeight
        val frame = if (safeRatio >= containerRatio) {
            Modifier.fillMaxWidth().height(maxWidth / safeRatio)
        } else {
            Modifier.fillMaxHeight().width(maxHeight * safeRatio)
        }
        content(frame)
    }
}

/**
 * One ExoPlayer for one (url, attempt). No internal retries: reports exactly one terminal
 * [PlayerSignal.Failed] (error, end of stream, connect timeout, or stall) per attempt.
 */
@Composable
private fun LivePlayerSurface(
    url: String,
    attempt: Int,
    client: OkHttpClient,
    surfaceType: Int,
    connectTimeoutMs: Long,
    stallTimeoutMs: Long,
    paused: Boolean = false,
    modifier: Modifier,
    onAspect: (Float) -> Unit,
    onSignal: (PlayerSignal) -> Unit,
) {
    val context = LocalContext.current
    val isRtsp = url.startsWith("rtsp", ignoreCase = true)
    val player = remember(url, attempt) { StreamPlayback.buildPlayer(context, url, client) }
    val aspectCallback by rememberUpdatedState(onAspect)
    val signalCallback by rememberUpdatedState(onSignal)
    val hold by rememberUpdatedState(paused)
    val outcome = remember(player) { AttemptOutcome() }

    fun fail(decoder: Boolean, message: String) {
        if (outcome.done) return
        outcome.done = true
        signalCallback(PlayerSignal.Failed(attempt, decoder, message))
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                val pixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                aspectCallback(videoSize.width * pixelRatio / videoSize.height)
            }

            override fun onRenderedFirstFrame() {
                if (outcome.live || outcome.done) return
                outcome.live = true
                signalCallback(PlayerSignal.Live(attempt))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> if (isRtsp && !outcome.live) StreamPlayback.seekToLiveEdge(player)
                    Player.STATE_ENDED -> fail(decoder = false, message = "")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                fail(StreamPlayback.isDecoderFailure(error), StreamPlayback.friendlyMessage(error))
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, paused) { player.playWhenReady = !paused }
    LaunchedEffect(player) {
        var started = System.currentTimeMillis()
        var lastPosition = -1L
        var lastProgressAt = started
        while (isActive && !outcome.done) {
            delay(1_000L)
            val now = System.currentTimeMillis()
            if (hold) {
                started = now
                lastProgressAt = now
                continue
            }
            if (!outcome.live) {
                if (now - started > connectTimeoutMs) fail(decoder = false, message = "")
                continue
            }
            val position = player.currentPosition
            if (position != lastPosition) {
                lastPosition = position
                lastProgressAt = now
            } else if (now - lastProgressAt > stallTimeoutMs) {
                fail(decoder = false, message = "")
            }
        }
    }

    PlayerSurface(
        player = player,
        modifier = modifier,
        surfaceType = surfaceType,
    )
}

private class AttemptOutcome {
    var live = false
    var done = false
}

private suspend fun awaitFailure(
    signals: Channel<PlayerSignal>,
    attempt: Int,
    onLive: () -> Unit,
): PlayerSignal.Failed {
    while (true) {
        val signal = signals.receive()
        if (signal.attempt != attempt) continue
        when (signal) {
            is PlayerSignal.Live -> onLive()
            is PlayerSignal.Failed -> return signal
        }
    }
}

private suspend fun obtainUrl(
    container: AppContainer,
    cameraId: String,
    quality: StreamQuality,
    force: Boolean,
): UrlResult {
    if (force) {
        StreamUrlCache.invalidate(cameraId, quality)
    } else {
        StreamUrlCache.get(cameraId, quality)?.let { return UrlResult.Url(it) }
    }
    val offer = try {
        container.protect.liveStream(cameraId, quality)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return UrlResult.Unavailable(error.message.orEmpty(), terminal = false)
    }
    val url = offer.url
    if (!url.isNullOrBlank()) {
        StreamUrlCache.put(cameraId, quality, url)
        return UrlResult.Url(url)
    }
    return UrlResult.Unavailable(offer.message, terminal = offer.availability != Availability.Ready)
}

private suspend fun loadPoster(container: AppContainer, cameraId: String, highQuality: Boolean): ImageBitmap? {
    val bytes = try {
        container.protect.previewJpeg(cameraId, highQuality)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    } ?: return null
    return withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
}
