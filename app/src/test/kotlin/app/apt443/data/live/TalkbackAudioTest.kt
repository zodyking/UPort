package app.apt443.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera reads a bare ADTS stream, so these headers are the whole contract between this TV
 * and the speaker. A wrong bit here is silence with no error anywhere.
 */
class TalkbackAudioTest {
    @Test
    fun headerCarriesSyncwordProfileAndLength() {
        val payload = ByteArray(29) { 0x5A }
        val frame = adtsFrame(payload, sampleRate = 22_050, channels = 1)

        assertEquals(payload.size + 7, frame.size)
        assertEquals(0xFF, frame[0].toInt() and 0xFF)
        // Syncword tail, MPEG-4, layer 0, no CRC.
        assertEquals(0xF1, frame[1].toInt() and 0xFF)
        // Profile bits are AAC-LC, which Protect cameras decode.
        assertEquals(0b01, (frame[2].toInt() and 0xC0) shr 6)
        // 22050 Hz is index 7 in the ADTS table.
        assertEquals(7, (frame[2].toInt() and 0x3C) shr 2)
        val declared = ((frame[3].toInt() and 0x3) shl 11) or
            ((frame[4].toInt() and 0xFF) shl 3) or
            ((frame[5].toInt() and 0xE0) shr 5)
        assertEquals(frame.size, declared)
    }

    @Test
    fun channelConfigurationFollowsTheCamera() {
        val mono = adtsFrame(ByteArray(8), sampleRate = 48_000, channels = 1)
        val stereo = adtsFrame(ByteArray(8), sampleRate = 48_000, channels = 2)
        assertEquals(1, channelsOf(mono))
        assertEquals(2, channelsOf(stereo))
        // Anything out of range is clamped rather than writing a reserved value.
        assertEquals(2, channelsOf(adtsFrame(ByteArray(8), sampleRate = 48_000, channels = 7)))
        assertEquals(1, channelsOf(adtsFrame(ByteArray(8), sampleRate = 48_000, channels = 0)))
    }

    @Test
    fun payloadIsCopiedAfterTheHeaderUntouched() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frame = adtsFrame(payload, sampleRate = 16_000, channels = 1)
        assertTrue(payload.contentEquals(frame.copyOfRange(7, frame.size)))
    }

    @Test
    fun ratesOffTheAacGridSnapToTheNearestSupportedRate() {
        assertEquals(22_050, nearestAacRate(22_050))
        assertEquals(24_000, nearestAacRate(23_900))
        assertEquals(22_050, nearestAacRate(22_500))
        // Rates past either end of the AAC table clamp to the closest one it has.
        assertEquals(7_350, nearestAacRate(4_000))
        assertEquals(96_000, nearestAacRate(192_000))
    }

    @Test
    fun aRateAacCannotCarryIsRejectedWithAReason() {
        val failure = runCatching { adtsFrame(ByteArray(4), sampleRate = 19_000, channels = 1) }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("19000"))
    }

    @Test
    fun sessionPrefersTheConsoleUrlThenFallsBackToTheBindAddress() {
        val withUrl = TalkbackSession(
            url = "udp://10.0.0.8:7004",
            codec = "aac",
            samplingRate = 22_050,
            bitsPerSample = 16,
            bindAddr = "0.0.0.0",
            bindPort = 7004,
        )
        assertEquals("10.0.0.8" to 7004, withUrl.destination)

        val bindOnly = withUrl.copy(url = "", bindAddr = "10.0.0.9")
        assertEquals("10.0.0.9" to 7004, bindOnly.destination)

        // A wildcard bind address falls back to the camera IP from bootstrap.
        val bootstrapHost = withUrl.copy(url = "", cameraHost = "10.0.0.12")
        assertEquals("10.0.0.12" to 7004, bootstrapHost.destination)

        assertNull(withUrl.copy(url = "", cameraHost = "").destination)

        val speakerSocket = withUrl.copy(url = "wss://192.168.1.1/proxy/protect/integration/v1/talkback-session/abc")
        assertNull(speakerSocket.destination)
        assertEquals(
            "wss://192.168.1.1/proxy/protect/integration/v1/talkback-session/abc",
            speakerSocket.speakerWebSocket,
        )
        val rtsps = withUrl.copy(url = "rtsps://192.168.1.1:7441/talk")
        assertEquals("10.0.0.8" to 7004, rtsps.copy(bindAddr = "10.0.0.8").destination)

        val rtp = withUrl.copy(url = "rtp://10.0.0.12:7004")
        assertEquals("10.0.0.12" to 7004, rtp.destination)
        val udpWithoutPort = withUrl.copy(url = "udp://10.0.0.8", bindAddr = "", bindPort = 0, cameraHost = "")
        assertEquals("10.0.0.8" to 7004, udpWithoutPort.destination)
    }

    @Test
    fun opusIsRtpAndNotAdts() {
        assertEquals(TalkbackContainer.Adts, talkbackContainer("aac"))
        assertEquals(TalkbackContainer.OpusRtp, talkbackContainer("opus"))
        assertEquals(24_000, nearestOpusRate(24_000))
        assertEquals(24_000, nearestOpusRate(22_050))

        val payload = byteArrayOf(0xF8.toByte(), 0x01, 0x02, 0x03)
        val packets = opusRtpPackets(
            frames = listOf(payload to 0L, payload to 20_000L),
            sampleRate = 24_000,
            sequenceStart = 1,
            ssrc = 0x41505434,
        )
        assertEquals(2, packets.size)
        val first = packets[0]
        assertEquals(payload.size + 12, first.size)
        // Version 2, payload type 96. An ADTS frame would start with 0xFF.
        assertEquals(0x80, first[0].toInt() and 0xFF)
        assertEquals(96, first[1].toInt() and 0x7F)
        assertTrue(payload.contentEquals(first.copyOfRange(12, first.size)))
        assertTrue((first[0].toInt() and 0xFF) != 0xFF)
        val timestamp = ((packets[1][4].toInt() and 0xFF) shl 24) or
            ((packets[1][5].toInt() and 0xFF) shl 16) or
            ((packets[1][6].toInt() and 0xFF) shl 8) or
            (packets[1][7].toInt() and 0xFF)
        // 20 ms of 24000 Hz audio is 480 samples, which is 960 ticks on the 48 kHz RTP clock.
        assertEquals(960, timestamp)
    }

    @Test
    fun vorbisIsRefusedBeforeAnySend() {
        val failure = runCatching { talkbackContainer("vorbis") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("vorbis"))
    }

    @Test
    fun bootstrapFindsCameraUnderDataWrapper() {
        val body = """
            {
              "data": {
                "cameras": [{
                  "id": "cam-1",
                  "host": "192.168.1.50",
                  "talkbackSettings": {
                    "bindAddr": "0.0.0.0",
                    "bindPort": 7004,
                    "samplingRate": 22050,
                    "bitsPerSample": 16,
                    "channels": 1
                  }
                }]
              }
            }
        """.trimIndent()
        assertEquals("192.168.1.50" to 7004, talkbackFromBootstrapBody(body, "cam-1").destination)
    }

    @Test
    fun bootstrapCameraJsonBuildsSessionWhenBindAddrIsWildcard() {
        val body = """
            {
              "cameras": [{
                "id": "cam-1",
                "host": "192.168.1.50",
                "talkbackSettings": {
                  "bindAddr": "0.0.0.0",
                  "bindPort": 7004,
                  "samplingRate": 22050,
                  "bitsPerSample": 16,
                  "channels": 1,
                  "codec": "aac"
                }
              }]
            }
        """.trimIndent()
        val session = talkbackFromBootstrapBody(body, "cam-1")
        assertEquals("192.168.1.50" to 7004, session.destination)
        assertEquals(22_050, session.samplingRate)
    }

    private fun channelsOf(frame: ByteArray): Int =
        ((frame[2].toInt() and 0x1) shl 2) or ((frame[3].toInt() and 0xC0) shr 6)
}
