package app.apt443.domain

import kotlinx.coroutines.flow.Flow

interface ProtectRepository {
    val connectionMode: Flow<ConnectionMode>

    fun cameras(): Flow<Feed<Camera>>

    fun lights(): Flow<Feed<ProtectLight>>

    fun sensors(): Flow<Feed<ProtectSensor>>

    fun events(): Flow<Feed<ProtectEvent>>

    /** Alerts for the overlay service. Demo preview and live Protect events both emit here. */
    fun alerts(): Flow<AlertInset>

    fun clips(cameraId: String): Flow<Feed<FootageClip>>

    suspend fun liveStream(cameraId: String, quality: StreamQuality = StreamQuality.High): LiveStreamOffer

    /** Current camera JPEG from the public snapshot endpoint. Null when the console has none. */
    suspend fun previewJpeg(cameraId: String, highQuality: Boolean = false): ByteArray?

    /**
     * JPEG Protect stored for this clip. Held in memory only; nothing is written on the TV.
     * Null when the console has no thumbnail yet.
     */
    suspend fun eventStill(eventId: String, thumbnailId: String = ""): ByteArray?

    /**
     * Clips for the recordings screen. Live mode asks the Integration API for
     * this camera, time window, and event kind. Demo filters the sample list.
     */
    suspend fun footage(query: ProtectClipQuery): Feed<ProtectEvent>

    /**
     * Prepares a recorded clip for the player. Live mode pulls the console export to local
     * storage first, so [onProgress] reports 0f..1f, or -1f while the console has not said how
     * long the clip will be.
     */
    suspend fun playback(clip: FootageClip, onProgress: (Float) -> Unit = {}): PlaybackOffer

    /** One PTZ step while the viewer is watching. Hidden unless the camera can pan. */
    suspend fun nudge(cameraId: String, move: PtzMove): Result<String>

    /** Official preset slot. Home is -1. The first saved view is 0. */
    suspend fun gotoPreset(cameraId: String, slot: Int): Result<String>

    /** Turn a Protect floodlight on or off. Not a device setting. */
    suspend fun setLight(lightId: String, on: Boolean): Result<String>

    /** Demo-only sample, or a live preview of [cameraId] when set. */
    suspend fun previewAlert(cameraId: String? = null)

    /**
     * Play a short spoken line on a camera speaker.
     * Live mode uses the official talkback session and sends audio to the camera.
     * Demo mode does not pretend the speaker played.
     */
    suspend fun announce(cameraId: String, message: String): Result<String>
}

interface NetworkRepository {
    fun dashboard(): Flow<NetworkDashboard>

    /** Starts the gateway speed test. Results show up on the next dashboard refresh. */
    suspend fun startSpeedTest(): Result<String>
}

interface AccessRepository {
    fun feed(): Flow<AccessFeed>

    /**
     * Momentary unlock. [confirmed] must be true. The UI reaches this only
     * from a second remote confirmation, never from the first click.
     */
    suspend fun unlock(doorId: String, confirmed: Boolean): Result<String>

    /** Groups, policies, doors, and cameras this person may use. Empty detail when Access has no match. */
    suspend fun grantsFor(email: String, userId: String): ResidentAccess
}
