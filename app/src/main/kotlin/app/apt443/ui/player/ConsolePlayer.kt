@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.apt443.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import app.apt443.ui.theme.AptColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Media3 player for a console RTSPS URL or an MP4 export URL.
 * Demo and remote modes pass a null URL and the caller shows an empty state.
 * No proprietary codecs are bundled; playback uses platform decoders.
 */
@Composable
fun ConsolePlayer(
    url: String?,
    client: OkHttpClient,
    modifier: Modifier = Modifier,
    surfaceType: Int = SURFACE_TYPE_SURFACE_VIEW,
    onVideoAspect: (Float) -> Unit = {},
    onPlaying: () -> Unit = {},
    onError: (message: String, failure: StreamFailure) -> Unit = { _, _ -> },
    paused: Boolean = false,
) {
    if (url.isNullOrBlank()) return
    val context = LocalContext.current
    val isRtsp = url.startsWith("rtsp", ignoreCase = true)
    val networkEpoch by StreamEpoch.epoch.collectAsStateWithLifecycle()
    var attempt by remember(url) { mutableIntStateOf(0) }
    val player = remember(url, attempt, networkEpoch) {
        StreamPlayback.buildPlayer(context, url, client)
    }
    val aspectCallback by rememberUpdatedState(onVideoAspect)
    val playingCallback by rememberUpdatedState(onPlaying)
    val errorCallback by rememberUpdatedState(onError)
    var failed by remember(url) { mutableStateOf(false) }
    var reportedPlaying by remember(url) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                val pixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                aspectCallback(videoSize.width * pixelRatio / videoSize.height)
                if (!reportedPlaying) {
                    reportedPlaying = true
                    playingCallback()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    if (isRtsp) {
                        StreamPlayback.seekToLiveEdge(player)
                    }
                    if (player.isPlaying && !reportedPlaying) {
                        reportedPlaying = true
                        playingCallback()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (StreamPlayback.shouldRetryRtsp(error, attempt, reportedPlaying)) {
                    scope.launch {
                        delay(StreamPlayback.RTSP_RETRY_DELAY_MS)
                        attempt++
                    }
                    return
                }
                if (reportedPlaying) return
                failed = true
                val failure = StreamPlayback.classify(error)
                errorCallback(StreamPlayback.message(failure), failure)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, paused) { player.playWhenReady = !paused }
    if (!failed) {
        PlayerSurface(
            player = player,
            modifier = modifier,
            surfaceType = surfaceType,
        )
    }
}

/**
 * Centers the stream and scales to the largest size that fits without cropping.
 */
@Composable
fun FittedStream(
    url: String?,
    client: OkHttpClient,
    modifier: Modifier = Modifier,
    surfaceType: Int = SURFACE_TYPE_SURFACE_VIEW,
    onPlaying: () -> Unit = {},
    onError: (message: String, failure: StreamFailure) -> Unit = { _, _ -> },
    onVideoAspect: (Float) -> Unit = {},
    crop: Boolean = false,
    paused: Boolean = false,
) {
    var ratio by remember(url) { mutableFloatStateOf(16f / 9f) }
    val aspectCallback by rememberUpdatedState(onVideoAspect)
    Box(
        modifier = modifier.background(AptColors.PreviewWell),
        contentAlignment = Alignment.Center,
    ) {
        if (crop) {
            AspectCover(ratio = ratio, modifier = Modifier.fillMaxSize()) { frame ->
                ConsolePlayer(
                    url = url,
                    client = client,
                    modifier = frame,
                    surfaceType = surfaceType,
                    onVideoAspect = { next ->
                        ratio = next
                        aspectCallback(next)
                    },
                    onPlaying = onPlaying,
                    onError = onError,
                    paused = paused,
                )
            }
        } else {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (maxWidth <= 0.dp || maxHeight <= 0.dp) return@BoxWithConstraints
            val containerRatio = maxWidth / maxHeight
            val frame = if (ratio >= containerRatio) {
                Modifier
                    .fillMaxWidth()
                    .height(maxWidth / ratio)
            } else {
                Modifier
                    .fillMaxHeight()
                    .width(maxHeight * ratio)
            }
            ConsolePlayer(
                url = url,
                client = client,
                modifier = frame,
                surfaceType = surfaceType,
                onVideoAspect = { next ->
                    ratio = next
                    aspectCallback(next)
                },
                onPlaying = onPlaying,
                onError = onError,
                paused = paused,
            )
        }
        }
    }
}
