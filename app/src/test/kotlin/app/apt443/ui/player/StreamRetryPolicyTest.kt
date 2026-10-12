package app.apt443.ui.player

import app.apt443.domain.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRetryPolicyTest {
    private fun noJitter() = StreamRetryPolicy(random = { 0.5 })

    @Test
    fun backoffDoublesThenCaps() {
        val policy = noJitter()
        policy.beginAttempt()
        val delays = (1..7).map { (policy.onPlaybackFailure(false) as RetryStep.RetryLive).delayMs }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L), delays)
    }

    @Test
    fun jitterStaysWithinBoundsAndCap() {
        val low = StreamRetryPolicy(random = { 0.0 })
        val high = StreamRetryPolicy(random = { 1.0 })
        assertEquals(800L, low.backoffFor(1))
        assertEquals(1_200L, high.backoffFor(1))
        assertEquals(12_000L, low.backoffFor(10))
        assertEquals(15_000L, high.backoffFor(10))
    }

    @Test
    fun refetchesUrlEverySecondConsecutiveFailure() {
        val policy = noJitter()
        val refetch = (1..4).map { (policy.onPlaybackFailure(false) as RetryStep.RetryLive).refetchUrl }
        assertEquals(listOf(false, true, false, true), refetch)
    }

    @Test
    fun goingLiveResetsBackoff() {
        val policy = noJitter()
        policy.onPlaybackFailure(false)
        policy.onPlaybackFailure(false)
        policy.beginAttempt()
        policy.onLive()
        assertEquals(StreamPhase.Live, policy.phase)
        assertEquals(0, policy.consecutiveFailures)
        val step = policy.onPlaybackFailure(false) as RetryStep.RetryLive
        assertEquals(1_000L, step.delayMs)
        assertEquals(StreamPhase.Reconnecting, policy.phase)
    }

    @Test
    fun firstAttemptIsConnectingAndFailuresReconnect() {
        val policy = noJitter()
        policy.beginAttempt()
        assertEquals(StreamPhase.Connecting, policy.phase)
        policy.onPlaybackFailure(false)
        policy.beginAttempt()
        assertEquals(StreamPhase.Reconnecting, policy.phase)
    }

    @Test
    fun attemptKeyIncreasesEveryAttempt() {
        val policy = noJitter()
        assertEquals(1, policy.beginAttempt())
        assertEquals(2, policy.beginAttempt())
    }

    @Test
    fun decoderFailureMovesToSnapshotOnlyAndStaysThere() {
        val policy = noJitter()
        policy.beginAttempt()
        val step = policy.onPlaybackFailure(decoderFailure = true)
        assertEquals(RetryStep.SnapshotOnly(60_000L, 3_000L), step)
        assertEquals(StreamPhase.SnapshotOnly, policy.phase)

        policy.beginAttempt()
        assertEquals(StreamPhase.SnapshotOnly, policy.phase)
        assertTrue(policy.onPlaybackFailure(false) is RetryStep.SnapshotOnly)
        assertEquals(StreamPhase.SnapshotOnly, policy.phase)
    }

    @Test
    fun snapshotProbeThatGoesLiveLeavesSnapshotMode() {
        val policy = noJitter()
        policy.onPlaybackFailure(decoderFailure = true)
        policy.beginAttempt()
        policy.onLive()
        assertEquals(StreamPhase.Live, policy.phase)
        assertTrue(policy.onPlaybackFailure(false) is RetryStep.RetryLive)
    }

    @Test
    fun terminalUnavailableIsIdleWithoutSpinner() {
        val policy = noJitter()
        val step = policy.onUrlUnavailable(terminal = true)
        assertEquals(StreamPhase.Failed, policy.phase)
        assertEquals(RetryStep.Idle(30_000L), step)
        policy.beginAttempt()
        assertEquals(StreamPhase.Connecting, policy.phase)
    }

    @Test
    fun transientUrlFailureBacksOffAndRefetches() {
        val policy = noJitter()
        val step = policy.onUrlUnavailable(terminal = false) as RetryStep.RetryLive
        assertEquals(1_000L, step.delayMs)
        assertTrue(step.refetchUrl)
        assertEquals(StreamPhase.Reconnecting, policy.phase)
    }

    @Test
    fun resumeKeepsSnapshotModeButResetsOthers() {
        val policy = noJitter()
        policy.onPlaybackFailure(false)
        policy.onResume()
        assertEquals(StreamPhase.Connecting, policy.phase)
        assertEquals(0, policy.consecutiveFailures)
        policy.onPlaybackFailure(decoderFailure = true)
        policy.onResume()
        assertEquals(StreamPhase.SnapshotOnly, policy.phase)
        policy.onDisabled()
        assertEquals(StreamPhase.Failed, policy.phase)
    }

    @Test
    fun urlStoreExpiresAndInvalidates() {
        var now = 0L
        val store = StreamUrlStore(ttlMs = 1_000L, clock = { now })
        store.put("cam", StreamQuality.Low, "rtsp://a")
        assertEquals("rtsp://a", store.get("cam", StreamQuality.Low))
        assertNull(store.get("cam", StreamQuality.High))
        now = 1_001L
        assertNull(store.get("cam", StreamQuality.Low))
        store.put("cam", StreamQuality.Low, "rtsp://b")
        store.invalidate("cam", StreamQuality.Low)
        assertNull(store.get("cam", StreamQuality.Low))
        store.put("cam", StreamQuality.Low, "rtsp://c")
        store.clear()
        assertFalse(store.get("cam", StreamQuality.Low) != null)
    }
}
