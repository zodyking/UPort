package app.apt443.ui.player

import android.content.Context
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.apt443.domain.StreamQuality
import okhttp3.OkHttpClient

/** Shared live-stream playback helpers: device quirks, decoder failures, quality defaults. */
object StreamPlayback {
    const val RTSP_MAX_RETRIES = 5
    const val RTSP_RETRY_DELAY_MS = 2_000L
    const val RTSP_TIMEOUT_MS = 15_000L

    private var preferMediumAfterDecoderFailure = false

    fun liveRtspLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(1_000, 4_000, 250, 1_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    fun buildPlayer(context: Context, url: String, client: OkHttpClient): ExoPlayer {
        val isRtsp = url.startsWith("rtsp", ignoreCase = true)
        val trackSelector = DefaultTrackSelector(context).apply {
            if (isRtsp) {
                setParameters(
                    buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build(),
                )
            }
        }
        val builder = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
        if (isRtsp) {
            builder.setLoadControl(liveRtspLoadControl())
        }
        return builder.build().apply {
            val item = MediaItem.fromUri(url)
            val source = if (isRtsp) {
                RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .setTimeoutMs(RTSP_TIMEOUT_MS)
                    .createMediaSource(item)
            } else {
                // Recording exports are downloaded first, so clips arrive as local files.
                val data = if (isLocalFile(url)) {
                    FileDataSource.Factory()
                } else {
                    OkHttpDataSource.Factory(client)
                }
                ProgressiveMediaSource.Factory(data).createMediaSource(item)
            }
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            setMediaSource(source)
            playWhenReady = true
            prepare()
        }
    }

    fun seekToLiveEdge(player: Player) {
        if (player.playbackState != Player.STATE_READY) return
        val duration = player.duration
        if (duration > 0) {
            player.seekTo(duration)
        }
    }

    fun shouldRetryRtsp(error: PlaybackException, attempt: Int, alreadyPlaying: Boolean): Boolean =
        shouldRetryRtspAttempt(attempt, alreadyPlaying, isDecoderFailure(error))

    internal fun shouldRetryRtspAttempt(
        attempt: Int,
        alreadyPlaying: Boolean,
        decoderFailure: Boolean,
    ): Boolean {
        if (alreadyPlaying || attempt >= RTSP_MAX_RETRIES || decoderFailure) return false
        return true
    }

    fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT
        val hardware = Build.HARDWARE
        val model = Build.MODEL
        val product = Build.PRODUCT
        val device = Build.DEVICE
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            fingerprint.contains("goldfish", ignoreCase = true) ||
            fingerprint.contains("sdk_gphone", ignoreCase = true) ||
            hardware.contains("goldfish", ignoreCase = true) ||
            hardware.contains("ranchu", ignoreCase = true) ||
            hardware.contains("emu", ignoreCase = true) ||
            device.contains("emu", ignoreCase = true) ||
            device.contains("goldfish", ignoreCase = true) ||
            model.contains("sdk_gphone", ignoreCase = true) ||
            model.contains("sdk_google", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            product.contains("sdk", ignoreCase = true) ||
            product.contains("emulator", ignoreCase = true)
    }

    fun preferredLiveQuality(): StreamQuality = when {
        isEmulator() -> StreamQuality.Low
        preferMediumAfterDecoderFailure -> StreamQuality.Medium
        else -> StreamQuality.High
    }

    fun rememberDecoderFailure() {
        preferMediumAfterDecoderFailure = true
    }

    /**
     * Sorts a player error into the one thing the viewer can act on.
     *
     * Grouping by [PlaybackException.errorCode] first matters: a refused export and an
     * unreadable container both used to be reported as an unsupported format, because the
     * old check searched the whole cause chain for the word "decoder" and MediaCodec puts
     * that word in wrapper messages regardless of what actually went wrong.
     */
    fun classify(error: PlaybackException): StreamFailure =
        classifyCode(error.errorCode, causeChain(error))

    internal fun classifyCode(errorCode: Int, text: String): StreamFailure = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        -> if (text.contains("401") || text.contains("403")) StreamFailure.Auth else StreamFailure.Network
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        -> StreamFailure.Network
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> StreamFailure.Format
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        -> StreamFailure.Decoder
        else -> if (looksLikeDecoderFailure(text)) StreamFailure.Decoder else StreamFailure.Unknown
    }

    fun isDecoderFailure(error: PlaybackException): Boolean = classify(error) == StreamFailure.Decoder

    /** Only codec names, never the bare word "decoder", which MediaCodec adds to every wrapper. */
    internal fun looksLikeDecoderFailure(text: String): Boolean {
        val haystack = text.lowercase()
        return haystack.contains("hevc") ||
            haystack.contains("h265") ||
            haystack.contains("h.265") ||
            haystack.contains("goldfish")
    }

    internal fun causeChain(error: PlaybackException): String = buildString {
        append(error.message.orEmpty())
        var cause = error.cause
        repeat(5) {
            val next = cause ?: return@buildString
            append(' ')
            append(next.message.orEmpty())
            cause = next.cause
        }
    }

    fun friendlyMessage(error: PlaybackException): String = message(classify(error))

    fun message(failure: StreamFailure): String = when (failure) {
        StreamFailure.Auth -> "The console refused this stream. Sign in again on the Account page."
        StreamFailure.Network -> "Can't reach the camera stream on the network."
        StreamFailure.Format -> "This recording could not be read. Try a shorter time range."
        StreamFailure.Decoder -> "This stream format isn't supported on this device."
        StreamFailure.Unknown -> "Playback failed."
    }

    internal fun isLocalFile(url: String): Boolean =
        url.startsWith("file:", ignoreCase = true) || url.startsWith("/")
}

/** Why a stream stopped, grouped by what the viewer can do about it. */
enum class StreamFailure {
    /** The console rejected the request; the session needs refreshing. */
    Auth,

    /** The console or camera could not be reached. */
    Network,

    /** Bytes arrived but were not a readable container. */
    Format,

    /** The container was readable but this device has no decoder for it. */
    Decoder,

    Unknown,
}

fun StreamQuality.lower(): StreamQuality? = when (this) {
    StreamQuality.High -> StreamQuality.Medium
    StreamQuality.Medium -> StreamQuality.Low
    StreamQuality.Low -> null
}
