package app.apt443.ui.player

import app.apt443.domain.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPlaybackTest {
    @Test
    fun lowerQualityStepsDown() {
        assertEquals(StreamQuality.Medium, StreamQuality.High.lower())
        assertEquals(StreamQuality.Low, StreamQuality.Medium.lower())
        assertEquals(null, StreamQuality.Low.lower())
    }

    @Test
    fun decoderFailureFromGoldfishHevcMessage() {
        assertTrue(
            StreamPlayback.looksLikeDecoderFailure("Decoder failed: c2.goldfish.hevc.decoder"),
        )
    }

    @Test
    fun decoderFailureFromHevcKeyword() {
        assertTrue(StreamPlayback.looksLikeDecoderFailure("HEVC decode error"))
    }

    @Test
    fun networkErrorIsNotDecoderFailure() {
        assertFalse(StreamPlayback.looksLikeDecoderFailure("Network connection failed"))
    }

    @Test
    fun rtspRetriesStopAfterLimit() {
        assertTrue(StreamPlayback.shouldRetryRtspAttempt(0, alreadyPlaying = false, decoderFailure = false))
        assertFalse(
            StreamPlayback.shouldRetryRtspAttempt(
                StreamPlayback.RTSP_MAX_RETRIES,
                alreadyPlaying = false,
                decoderFailure = false,
            ),
        )
    }

    @Test
    fun rtspDoesNotRetryDecoderFailures() {
        assertFalse(StreamPlayback.shouldRetryRtspAttempt(0, alreadyPlaying = false, decoderFailure = true))
    }
}
