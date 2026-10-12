package app.apt443.data.live

import android.content.Context
import app.apt443.domain.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pulls a Protect recording export to local storage and hands back a playable file.
 *
 * The export endpoint muxes the MP4 as it sends it, so the response is chunked with no
 * content length and the `moov` index can land at the very end. Media3 cannot start a
 * stream shaped like that over HTTP, which is why the clip is written to the cache first
 * and played from disk.
 */
class RecordingCache(
    context: Context,
    private val legacy: ProtectLegacySession,
) {
    private val root = File(context.cacheDir, "clips")

    /**
     * Returns a finished local clip. [onProgress] gets 0f..1f while a length is known and
     * -1f while the console is still muxing and has not said how long the clip will be.
     */
    suspend fun clip(
        settings: AppSettings,
        url: String,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = File(root, fileNameFor(url))
        val cached = validCachedFile(target)
        if (cached != null) {
            target.setLastModified(System.currentTimeMillis())
            onProgress(1f)
            return@withContext Result.success(cached)
        }
        downloadOnce(settings, url, target, onProgress)
            .recoverCatching {
                // A half-written export from a dropped connection can sit in cache; try once more.
                runCatching { target.delete() }
                downloadOnce(settings, url, target, onProgress).getOrThrow()
            }
    }

    private suspend fun downloadOnce(
        settings: AppSettings,
        url: String,
        target: File,
        onProgress: (Float) -> Unit,
    ): Result<File> {
        root.mkdirs()
        val part = File(root, target.name + ".part")
        runCatching { part.delete() }
        val download = legacy.downloadExport(settings, url, part) { read, total ->
            onProgress(if (total > 0L) (read.toFloat() / total).coerceIn(0f, 1f) else -1f)
        }
        val result = download.fold(
            onSuccess = { bytes -> validateDownload(part, bytes, target) },
            onFailure = { Result.failure(it) },
        )
        runCatching { part.delete() }
        if (result.isSuccess) {
            onProgress(1f)
            trim()
        }
        return result
    }

    private fun validateDownload(part: File, bytes: Long, target: File): Result<File> {
        if (bytes <= MIN_CLIP_BYTES) {
            return Result.failure(
                IllegalStateException("The console returned almost no data for this clip. Try again."),
            )
        }
        val problem = Mp4Probe.problem(part)
        if (problem != null) {
            return Result.failure(IllegalStateException(problem))
        }
        runCatching { target.delete() }
        return if (part.renameTo(target)) {
            Result.success(target)
        } else {
            Result.failure(IllegalStateException("This TV could not save the recording."))
        }
    }

    /** Returns the file only when it still looks like a complete MP4. */
    private fun validCachedFile(target: File): File? {
        if (!target.isFile || target.length() <= MIN_CLIP_BYTES) return null
        if (Mp4Probe.problem(target) != null) {
            runCatching { target.delete() }
            return null
        }
        return target
    }

    /** Keeps the newest clips and drops the rest so recordings cannot fill the device. */
    private fun trim() {
        val clips = root.listFiles { file -> file.isFile && file.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        var kept = 0L
        clips.forEachIndexed { index, file ->
            kept += file.length()
            if (index >= MAX_CLIPS || kept > MAX_CACHE_BYTES) {
                runCatching { file.delete() }
            }
        }
    }

    private fun fileNameFor(url: String): String =
        "clip-${url.hashCode().toUInt().toString(16)}.mp4"

    private companion object {
        const val MIN_CLIP_BYTES = 2_048L
        const val MAX_CLIPS = 8
        const val MAX_CACHE_BYTES = 512L * 1024 * 1024
    }
}
