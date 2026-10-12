package app.apt443.data.live

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Speaks a short line on a Protect camera.
 *
 * The camera's talkback session names the codec and the address. AAC is paced ADTS on that
 * UDP or RTP port. Opus is paced Opus-in-RTP on the same port, with the 48 kHz RTP clock from
 * RFC 7587. Vorbis and anything else are refused. A WebSocket is used only when the session
 * has no UDP or RTP address, and the frames are paced there too.
 *
 * Speech comes from this TV's own engine when it has one, and from Google otherwise.
 * The microphone is never opened.
 *
 * The engine stays bound for the life of the process. Preparing the words overlaps the
 * talkback request, and each encoded frame is sent as soon as it exists. Packets stay paced.
 */
class TalkbackAnnouncer(
    private val context: Context,
    private val speech: GoogleTranslateSpeech,
) {
    private val ttsThread = HandlerThread("apt443-tts").apply { start() }
    private val ttsHandler = Handler(ttsThread.looper)
    private val ttsEngine = CompletableDeferred<TextToSpeech?>()

    init {
        ttsHandler.post {
            val holder = arrayOfNulls<TextToSpeech>(1)
            holder[0] = TextToSpeech(context) { status ->
                val engine = holder[0]
                if (engine == null || status != TextToSpeech.SUCCESS) {
                    engine?.shutdown()
                    ttsEngine.complete(null)
                    return@TextToSpeech
                }
                val language = engine.setLanguage(Locale.US)
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.shutdown()
                    ttsEngine.complete(null)
                    return@TextToSpeech
                }
                ttsEngine.complete(engine)
            }
        }
    }

    /**
     * Speaks [message] on the camera's own speaker.
     *
     * [open] runs while the words are prepared, so the talkback request does not wait for
     * speech and speech does not wait for the talkback request. A udp or rtp destination is
     * used as-is. [TalkbackSocket] is only a fallback when that address is missing, and each
     * frame is paced. Opening the socket is not enough to report success.
     */
    internal suspend fun play(
        message: String,
        open: suspend () -> Result<Pair<TalkbackSession, TalkbackSocket?>>,
    ): SpeakOutcome = speech.oneAtATime {
        val line = message.trim()
        if (line.isEmpty()) {
            return@oneAtATime SpeakOutcome.Failed(IllegalStateException("Choose a message first."))
        }
        if (line.length > 180) {
            return@oneAtATime SpeakOutcome.Failed(
                IllegalStateException("That message is too long for the camera speaker."),
            )
        }
        supervisorScope {
            val pcmDeferred = async(Dispatchers.Default) { loadPcm(line) }
            try {
                val opened = try {
                    open()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    return@supervisorScope SpeakOutcome.Rejected(error)
                }
                val (session, socket) = opened.getOrElse { error ->
                    return@supervisorScope SpeakOutcome.Rejected(error)
                }
                try {
                    send(session, pcmDeferred.await(), socket)
                    SpeakOutcome.Spoken
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    SpeakOutcome.Failed(error)
                }
            } finally {
                pcmDeferred.cancel()
            }
        }
    }

    /**
     * PCM for [text]. A warm engine is used on its own. Google starts only when that engine
     * is missing or still binding, and it runs beside the engine instead of after it.
     */
    private suspend fun loadPcm(text: String): DecodedPcm {
        if (ttsEngine.isCompleted) {
            synthesize(text)?.let { return it }
            return decodeMp3(speech.mp3(text))
        }
        return supervisorScope {
            val remote = async(Dispatchers.IO) {
                runCatching { decodeMp3(speech.mp3(text)) }.getOrNull()
            }
            val local = async { synthesize(text) }
            val first = select<DecodedPcm?> {
                local.onAwait { it }
                remote.onAwait { it }
            }
            val pcm = when {
                first != null -> first
                local.isCompleted -> remote.await()
                else -> local.await() ?: remote.await()
            }
            local.cancel()
            remote.cancel()
            pcm ?: error("Speech could not be created.")
        }
    }

    /** Local speech when the TV has an engine, which avoids a round trip to Google. */
    private suspend fun synthesize(text: String): DecodedPcm? {
        val engine = withTimeoutOrNull(ENGINE_WAIT_MS) { ttsEngine.await() } ?: return null
        val wav = runCatching { synthesizeWav(engine, text) }.getOrNull() ?: return null
        return runCatching { parseWav(wav).let { DecodedPcm(it.samples, it.sampleRate, it.channels) } }
            .getOrNull()
    }

    private suspend fun synthesizeWav(engine: TextToSpeech, text: String): ByteArray =
        withTimeout(SYNTH_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val utterance = "apt443-${System.nanoTime()}"
                val file = File(context.cacheDir, "apt443-say.wav")
                cont.invokeOnCancellation {
                    ttsHandler.post { runCatching { engine.stop() } }
                }
                ttsHandler.post {
                    if (!cont.isActive) return@post
                    file.delete()
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId != utterance) return
                            val bytes = runCatching { file.readBytes() }.getOrElse { error ->
                                file.delete()
                                if (cont.isActive) cont.resumeWithException(error)
                                return
                            }
                            file.delete()
                            if (cont.isActive) cont.resume(bytes)
                        }

                        @Deprecated("Framework calls onError(utteranceId, errorCode) on newer APIs.")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId != null && utteranceId != utterance) return
                            file.delete()
                            if (cont.isActive) {
                                cont.resumeWithException(IllegalStateException("Speech could not be created."))
                            }
                        }
                    })
                    val started = engine.synthesizeToFile(text, Bundle(), file, utterance)
                    if (started != TextToSpeech.SUCCESS && cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Speech could not be created."))
                    }
                }
            }
        }

    /**
     * Encodes [pcm] and sends each frame as the encoder emits it.
     *
     * Resample and the first packet overlap the address lookup. Later packets wait out their
     * own duration so the camera's buffer is not overrun.
     */
    private suspend fun send(
        session: TalkbackSession,
        pcm: DecodedPcm,
        socket: TalkbackSocket?,
    ) {
        val container = talkbackContainer(session.codec)
        val rate = when (container) {
            TalkbackContainer.Adts -> nearestAacRate(session.samplingRate)
            TalkbackContainer.OpusRtp -> nearestOpusRate(session.samplingRate)
        }
        val frames = Channel<AacFrame>(Channel.UNLIMITED)
        coroutineScope {
            var encodeError: Throwable? = null
            val encodeJob = launch(Dispatchers.Default) {
                try {
                    val mono = if (pcm.channels == 1) pcm.samples else downmix(pcm.samples, pcm.channels)
                    val rated = resample(mono, pcm.sampleRate, rate)
                    val padded = padSilence(rated, rate, leadingMs = LEAD_IN_MS, trailingMs = TRAIL_MS)
                    produceFrames(padded, rate, container) { frame ->
                        frames.trySend(frame)
                    }
                } catch (error: Throwable) {
                    encodeError = error
                    frames.close(error)
                } finally {
                    frames.close()
                }
            }
            val sendResult = try {
                val target = session.destination
                if (target != null) {
                    val (host, port) = target
                    sendOverUdp(host, port, frames)
                } else {
                    val fallback = socket ?: error("The camera did not give this TV a speaker address.")
                    sendOverSocket(fallback, frames)
                }
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            encodeJob.join()
            sendResult.getOrThrow()
            encodeError?.let { throw it }
        }
    }

    private suspend fun sendOverSocket(
        socket: TalkbackSocket,
        packets: ReceiveChannel<AacFrame>,
    ) {
        socket.use { live ->
            live.awaitOpen()
            sendPaced(packets) { bytes -> live.send(bytes) }
            // The last frame is already queued. Keep the socket up only long enough for it to leave.
            delay(SOCKET_TAIL_MS)
        }
    }
}

private data class PcmClip(val sampleRate: Int, val channels: Int, val samples: ShortArray)

private fun parseWav(bytes: ByteArray): PcmClip {
    if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") {
        error("Speech file was not a WAV.")
    }
    var offset = 12
    var rate = 0
    var channels = 0
    var bits = 0
    var data: ByteArray? = null
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4)
        val size = readLeInt(bytes, offset + 4)
        val start = offset + 8
        if (size < 0 || start + size > bytes.size) break
        when (id) {
            "fmt " -> {
                if (size >= 16) {
                    channels = readLeShort(bytes, start + 2)
                    rate = readLeInt(bytes, start + 4)
                    bits = readLeShort(bytes, start + 14)
                }
            }
            "data" -> data = bytes.copyOfRange(start, start + size)
        }
        offset = start + size + (size and 1)
    }
    val pcm = data ?: error("Speech file had no audio.")
    if (rate <= 0 || channels <= 0 || bits != 16) error("Speech file was not 16-bit PCM.")
    val samples = ShortArray(pcm.size / 2)
    for (i in samples.indices) {
        samples[i] = readLeShort(pcm, i * 2).toShort()
    }
    return PcmClip(rate, channels, samples)
}

private fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
    val frames = interleaved.size / channels
    return ShortArray(frames) { frame ->
        var sum = 0
        for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
        (sum / channels).toShort()
    }
}

private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
    if (fromRate == toRate || input.isEmpty()) return input
    val outLen = (input.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
    return ShortArray(outLen) { index ->
        val src = index.toDouble() * fromRate / toRate
        val left = src.toInt().coerceIn(0, input.lastIndex)
        val right = (left + 1).coerceAtMost(input.lastIndex)
        val fraction = (src - left).toFloat()
        val mixed = input[left] * (1f - fraction) + input[right] * fraction
        mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

private fun padSilence(pcm: ShortArray, sampleRate: Int, leadingMs: Int, trailingMs: Int): ShortArray {
    val lead = sampleRate * leadingMs / 1000
    val trail = sampleRate * trailingMs / 1000
    val out = ShortArray(lead + pcm.size + trail)
    pcm.copyInto(out, lead)
    return out
}

/** One raw AAC access unit with the time it should start playing. */
private data class AacFrame(val payload: ByteArray, val timeUs: Long)

private data class DecodedPcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

private fun decodeMp3(mp3: ByteArray): DecodedPcm {
    val file = File.createTempFile("apt443-tts", ".mp3")
    try {
        file.writeBytes(mp3)
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var track = -1
        var format: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(index)
            val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                track = index
                format = candidate
                break
            }
        }
        if (track < 0 || format == null) error("Google speech was not audio.")
        extractor.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Google speech was not audio.")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            1
        }
        val codec = MediaCodec.createDecoderByType(mime)
        val samples = ArrayList<Short>()
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var spins = 0
            while (!outputDone && spins < 400) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index) ?: error("Decoder input was missing.")
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val out = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> spins += 1
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> spins = 0
                    else -> if (out >= 0) {
                        spins = 0
                        val output = codec.getOutputBuffer(out)
                        if (output != null && info.size > 1) {
                            output.position(info.offset)
                            val bytes = ByteArray(info.size)
                            output.get(bytes)
                            var offset = 0
                            while (offset + 1 < bytes.size) {
                                val sample = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
                                samples.add(sample.toShort())
                                offset += 2
                            }
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(out, false)
                        if (eos) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        if (samples.isEmpty()) error("Google speech decoded to silence.")
        return DecodedPcm(samples.toShortArray(), sampleRate, channels.coerceAtLeast(1))
    } finally {
        file.delete()
    }
}

private fun encodeAac(
    pcm: ShortArray,
    sampleRate: Int,
    channels: Int,
    onFrame: (AacFrame) -> Unit,
) {
    val frameSamples = 1024
    val frames = (pcm.size + frameSamples - 1) / frameSamples
    val padded = pcm.copyOf(frames * frameSamples)
    val codec = try {
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    } catch (_: Exception) {
        throw IllegalStateException("This TV can't encode speech for the camera speaker.")
    }
    var emitted = 0
    try {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameSamples * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var offset = 0
        var inputDone = false
        var outputDone = false
        var idle = 0
        val info = MediaCodec.BufferInfo()
        while (!outputDone && idle < 160) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(5_000)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index) ?: error("Encoder input was missing.")
                    input.clear()
                    if (offset >= padded.size) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val bytes = ByteArray(frameSamples * 2)
                        for (i in 0 until frameSamples) {
                            val sample = padded[offset + i].toInt()
                            bytes[i * 2] = (sample and 0xff).toByte()
                            bytes[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                        }
                        input.put(bytes)
                        val timeUs = offset.toLong() * 1_000_000L / sampleRate
                        codec.queueInputBuffer(index, 0, bytes.size, timeUs, 0)
                        offset += frameSamples
                    }
                }
            }
            when (val outIndex = codec.dequeueOutputBuffer(info, 5_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) idle += 1
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> idle = 0
                else -> if (outIndex >= 0) {
                    idle = 0
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        val output = codec.getOutputBuffer(outIndex)
                        if (output != null) {
                            val payload = ByteArray(info.size)
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.get(payload)
                            onFrame(AacFrame(payload, info.presentationTimeUs))
                            emitted += 1
                        }
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (eos) outputDone = true
                } else if (inputDone) {
                    idle += 1
                }
            }
        }
    } finally {
        runCatching { codec.stop() }
        codec.release()
    }
    if (emitted == 0) error("Speech encoder produced no audio.")
}

/**
 * Emits one camera packet per encoded frame. AAC is wrapped in ADTS immediately.
 * Opus is wrapped in RTP immediately, using the same 20 ms step and 48 kHz clock as [opusRtpPackets].
 */
private fun produceFrames(
    pcm: ShortArray,
    sampleRate: Int,
    container: TalkbackContainer,
    onFrame: (AacFrame) -> Unit,
) {
    when (container) {
        TalkbackContainer.Adts -> encodeAac(pcm, sampleRate, 1) { frame ->
            onFrame(AacFrame(adtsFrame(frame.payload, sampleRate, 1), frame.timeUs))
        }
        TalkbackContainer.OpusRtp -> {
            var sequence = 1
            var timestamp = 0L
            val frameSamples = (sampleRate / 50).coerceAtLeast(1)
            encodeOpus(pcm, sampleRate) { frame ->
                val packet = rtpOpusPacket(frame.payload, sequence, timestamp, OPUS_RTP_SSRC)
                sequence = (sequence + 1) and 0xFFFF
                timestamp = (timestamp + frameSamples.toLong() * OPUS_RTP_CLOCK / sampleRate) and 0xFFFF_FFFFL
                onFrame(AacFrame(packet, frame.timeUs))
            }
        }
    }
}

/**
 * Encodes mono PCM as Opus at the session rate.
 *
 * The rate is the one the camera negotiated. It is not snapped onto the AAC rate table.
 */
private fun encodeOpus(
    pcm: ShortArray,
    sampleRate: Int,
    onFrame: (AacFrame) -> Unit,
) {
    val frameSamples = (sampleRate / 50).coerceAtLeast(1)
    val frames = (pcm.size + frameSamples - 1) / frameSamples
    val padded = pcm.copyOf(frames * frameSamples)
    val codec = try {
        MediaCodec.createEncoderByType(OPUS_MIME)
    } catch (_: Exception) {
        throw IllegalStateException("This TV can't encode Opus for the camera speaker.")
    }
    var emitted = 0
    try {
        val format = MediaFormat.createAudioFormat(OPUS_MIME, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameSamples * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var offset = 0
        var inputDone = false
        var outputDone = false
        var idle = 0
        val info = MediaCodec.BufferInfo()
        while (!outputDone && idle < 160) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(5_000)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index) ?: error("Encoder input was missing.")
                    input.clear()
                    if (offset >= padded.size) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val bytes = ByteArray(frameSamples * 2)
                        for (i in 0 until frameSamples) {
                            val sample = padded[offset + i].toInt()
                            bytes[i * 2] = (sample and 0xff).toByte()
                            bytes[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                        }
                        input.put(bytes)
                        val timeUs = offset.toLong() * 1_000_000L / sampleRate
                        codec.queueInputBuffer(index, 0, bytes.size, timeUs, 0)
                        offset += frameSamples
                    }
                }
            }
            when (val outIndex = codec.dequeueOutputBuffer(info, 5_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) idle += 1
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> idle = 0
                else -> if (outIndex >= 0) {
                    idle = 0
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        val output = codec.getOutputBuffer(outIndex)
                        if (output != null) {
                            val payload = ByteArray(info.size)
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.get(payload)
                            onFrame(AacFrame(payload, info.presentationTimeUs))
                            emitted += 1
                        }
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (eos) outputDone = true
                } else if (inputDone) {
                    idle += 1
                }
            }
        }
    } finally {
        runCatching { codec.stop() }
        codec.release()
    }
    if (emitted == 0) error("Speech encoder produced no audio.")
}

/**
 * Wraps one raw AAC frame in a 7-byte ADTS header.
 *
 * The camera reads a bare ADTS stream, the same thing `ffmpeg -f adts` writes, so each frame
 * has to carry its own header. Earlier builds sent RTP packets instead, which the camera has no
 * way to interpret and silently discards.
 */
internal fun adtsFrame(payload: ByteArray, sampleRate: Int, channels: Int): ByteArray {
    val rateIndex = ADTS_SAMPLE_RATES.indexOf(sampleRate)
    if (rateIndex < 0) error("The camera asked for $sampleRate Hz audio, which AAC cannot carry.")
    val channelConfig = channels.coerceIn(1, 2)
    val total = payload.size + ADTS_HEADER_BYTES
    if (total > 0x1FFF) error("Speech frame was too large for the camera speaker.")
    val frame = ByteArray(total)
    // Syncword 0xFFF, MPEG-4, no CRC.
    frame[0] = 0xFF.toByte()
    frame[1] = 0xF1.toByte()
    // Profile AAC-LC (1 -> 0b01), sample rate index, channel configuration.
    frame[2] = (((AAC_LC_PROFILE shl 6) or (rateIndex shl 2) or (channelConfig shr 2)) and 0xFF).toByte()
    frame[3] = (((channelConfig and 0x3) shl 6) or ((total shr 11) and 0x3)).toByte()
    frame[4] = ((total shr 3) and 0xFF).toByte()
    frame[5] = (((total and 0x7) shl 5) or 0x1F).toByte()
    frame[6] = 0xFC.toByte()
    payload.copyInto(frame, ADTS_HEADER_BYTES)
    return frame
}

/** Codec the camera asked for, mapped the same way uiprotect maps it. */
internal enum class TalkbackContainer { Adts, OpusRtp }

internal fun talkbackContainer(codec: String): TalkbackContainer {
    val name = codec.trim().lowercase()
    return when {
        name.isEmpty() || name.contains("aac") || name.contains("mp4a") -> TalkbackContainer.Adts
        name == "opus" || name.contains("opus") -> TalkbackContainer.OpusRtp
        else -> error("This camera asked for $codec audio, which this TV can't send.")
    }
}

/**
 * One Opus packet in a 12-byte RTP header.
 *
 * Payload type 96 is the first dynamic type FFmpeg's rtp mux assigns. The timestamp uses the
 * 48 kHz RTP clock from RFC 7587 even when the audio itself is 24000 Hz.
 */
internal fun rtpOpusPacket(
    payload: ByteArray,
    sequence: Int,
    timestamp: Long,
    ssrc: Int,
    marker: Boolean = false,
): ByteArray {
    val packet = ByteArray(RTP_HEADER_BYTES + payload.size)
    packet[0] = 0x80.toByte()
    packet[1] = ((if (marker) 0x80 else 0) or OPUS_RTP_PAYLOAD_TYPE).toByte()
    val seq = sequence and 0xFFFF
    packet[2] = ((seq shr 8) and 0xFF).toByte()
    packet[3] = (seq and 0xFF).toByte()
    val ts = (timestamp and 0xFFFF_FFFFL).toInt()
    packet[4] = ((ts ushr 24) and 0xFF).toByte()
    packet[5] = ((ts ushr 16) and 0xFF).toByte()
    packet[6] = ((ts ushr 8) and 0xFF).toByte()
    packet[7] = (ts and 0xFF).toByte()
    packet[8] = ((ssrc ushr 24) and 0xFF).toByte()
    packet[9] = ((ssrc ushr 16) and 0xFF).toByte()
    packet[10] = ((ssrc ushr 8) and 0xFF).toByte()
    packet[11] = (ssrc and 0xFF).toByte()
    payload.copyInto(packet, RTP_HEADER_BYTES)
    return packet
}

/**
 * Wraps each Opus payload in RTP and advances the timestamp by the audio duration on a 48 kHz clock.
 */
internal fun opusRtpPackets(
    frames: List<Pair<ByteArray, Long>>,
    sampleRate: Int,
    sequenceStart: Int = 1,
    ssrc: Int = 0x41505434,
): List<ByteArray> {
    if (sampleRate <= 0) error("The camera asked for $sampleRate Hz audio, which Opus cannot carry.")
    if (frames.isEmpty()) error("Speech encoder produced no audio.")
    var sequence = sequenceStart and 0xFFFF
    var timestamp = 0L
    return frames.mapIndexed { index, (payload, timeUs) ->
        val packet = rtpOpusPacket(payload, sequence, timestamp, ssrc)
        sequence = (sequence + 1) and 0xFFFF
        val nextUs = frames.getOrNull(index + 1)?.second
        val deltaUs = if (nextUs != null) (nextUs - timeUs).coerceAtLeast(0L) else 20_000L
        val samples = deltaUs * sampleRate / 1_000_000L
        timestamp = (timestamp + samples * OPUS_RTP_CLOCK / sampleRate) and 0xFFFF_FFFFL
        packet
    }
}

/**
 * Streams already-framed packets in real time.
 *
 * An unpaced burst overruns the camera's UDP buffer, so each packet waits until
 * `samples_sent / sample_rate` of monotonic time has passed.
 */
private suspend fun sendPaced(
    packets: ReceiveChannel<AacFrame>,
    write: (ByteArray) -> Unit,
) {
    var next = packets.receiveCatching()
    val first = next.getOrNull() ?: throw (
        next.exceptionOrNull() ?: IllegalStateException("Speech encoder produced no audio.")
        )
    val clockStart = System.nanoTime()
    val firstTime = first.timeUs
    write(first.payload)
    while (true) {
        next = packets.receiveCatching()
        if (next.isClosed) {
            next.exceptionOrNull()?.let { throw it }
            break
        }
        val packet = next.getOrThrow()
        val dueNs = clockStart + (packet.timeUs - firstTime).coerceAtLeast(0L) * 1_000L
        val waitMs = (dueNs - System.nanoTime()) / 1_000_000L
        if (waitMs > 0) delay(waitMs)
        write(packet.payload)
    }
}

private suspend fun sendOverUdp(
    host: String,
    port: Int,
    packets: ReceiveChannel<AacFrame>,
) = withContext(Dispatchers.IO) {
    val address = InetAddress.getByName(host)
    DatagramSocket().use { socket ->
        sendPaced(packets) { bytes ->
            socket.send(DatagramPacket(bytes, bytes.size, address, port))
        }
    }
}

/** The AAC rate closest to what the camera asked for, since AAC has a fixed set. */
internal fun nearestAacRate(requested: Int): Int {
    if (ADTS_SAMPLE_RATES.contains(requested)) return requested
    return ADTS_SAMPLE_RATES.minByOrNull { kotlin.math.abs(it - requested) } ?: 22_050
}

/** Opus rates MediaCodec accepts. The session rate is kept when it is already one of these. */
internal fun nearestOpusRate(requested: Int): Int {
    if (OPUS_SAMPLE_RATES.contains(requested)) return requested
    return OPUS_SAMPLE_RATES.minByOrNull { kotlin.math.abs(it - requested) } ?: 24_000
}

private val ADTS_SAMPLE_RATES = intArrayOf(
    96_000, 88_200, 64_000, 48_000, 44_100, 32_000,
    24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
)
private val OPUS_SAMPLE_RATES = intArrayOf(48_000, 24_000, 16_000, 12_000, 8_000)
private const val ADTS_HEADER_BYTES = 7
private const val AAC_LC_PROFILE = 1
private const val OPUS_MIME = "audio/opus"
private const val RTP_HEADER_BYTES = 12
private const val OPUS_RTP_PAYLOAD_TYPE = 96
private const val OPUS_RTP_CLOCK = 48_000L
private const val OPUS_RTP_SSRC = 0x41505434
private const val LEAD_IN_MS = 60
private const val TRAIL_MS = 120
private const val SOCKET_TAIL_MS = 100L
private const val ENGINE_WAIT_MS = 800L
private const val SYNTH_TIMEOUT_MS = 12_000L

/** Session setup failed before audio, or the camera accepted the phrase. */
internal sealed class SpeakOutcome {
    data object Spoken : SpeakOutcome()
    data class Rejected(val error: Throwable) : SpeakOutcome()
    data class Failed(val error: Throwable) : SpeakOutcome()
}

private fun readLeInt(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)
}

private fun readLeShort(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}
