package app.apt443.data.live

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayableRtspTest {
    @Test
    fun rewritesRtspsPortAndStripsSrtp() {
        val input = "rtsps://unvr.local:7441/s0?enableSrtp"
        assertEquals(
            "rtsp://192.168.0.1:7447/s0",
            playableRtsp(input, "192.168.0.1"),
        )
    }

    @Test
    fun stripsEnableSrtpWithValue() {
        val input = "rtsps://192.168.0.1:7441/abc?foo=1&enableSrtp=true"
        assertEquals(
            "rtsp://192.168.0.1:7447/abc?foo=1",
            playableRtsp(input),
        )
    }

    @Test
    fun pickStreamMediumDoesNotFallBackToHigh() {
        val body = """
            {
              "rtspsStreams": {
                "high": "rtsps://wrong-host:7441/high?enableSrtp"
              }
            }
        """.trimIndent()
        assertEquals(
            null,
            pickStream(body, app.apt443.domain.StreamQuality.Medium, "192.168.0.1"),
        )
    }

    @Test
    fun pickStreamPrefersRequestedMediumTier() {
        val body = """
            {
              "rtspsStreams": {
                "high": "rtsps://wrong-host:7441/high?enableSrtp",
                "medium": "rtsps://wrong-host:7441/medium?enableSrtp",
                "low": "rtsps://wrong-host:7441/low?enableSrtp"
              }
            }
        """.trimIndent()
        assertEquals(
            "rtsp://192.168.0.1:7447/medium",
            pickStream(body, app.apt443.domain.StreamQuality.Medium, "192.168.0.1"),
        )
    }

    @Test
    fun pickStreamReadsNestedRtspsStreams() {
        val body = """
            {
              "data": {
                "rtspsStreams": {
                  "high": "rtsps://wrong-host:7441/s0?enableSrtp"
                }
              }
            }
        """.trimIndent()
        assertEquals(
            "rtsp://192.168.0.1:7447/s0",
            pickStream(body, app.apt443.domain.StreamQuality.High, "192.168.0.1"),
        )
    }
}
