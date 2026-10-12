package app.apt443.data.live

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the top-level box list of an MP4 so a bad recording export can be named before
 * ExoPlayer sees it.
 *
 * Protect writes `/proxy/protect/api/video/export` while it muxes. When the console gives
 * up part way the body still arrives with a 200, but the file has no `moov` index and no
 * player can start it. A console that answers with an HTML sign-in page has no `ftyp`
 * either. Both cases are indistinguishable from a codec problem once Media3 reports them,
 * so they are caught here instead.
 */
object Mp4Probe {
    /** Box types in file order, e.g. `[ftyp, free, mdat, moov]`. Empty when this is not an MP4. */
    fun boxTypes(file: File, max: Int = 24): List<String> {
        if (!file.isFile || file.length() < HEADER) return emptyList()
        val types = mutableListOf<String>()
        RandomAccessFile(file, "r").use { handle ->
            val length = handle.length()
            var offset = 0L
            while (offset + HEADER <= length && types.size < max) {
                handle.seek(offset)
                val header = ByteArray(HEADER)
                if (handle.read(header) != HEADER) break
                val type = String(header, 4, 4, Charsets.US_ASCII)
                if (!type.all { it.isBoxChar() }) break
                types += type
                val declared = readUInt32(header, 0)
                val size = when (declared) {
                    // 1 means the real size is a 64-bit value right after the type.
                    1L -> {
                        if (offset + HEADER + 8 > length) break
                        val large = ByteArray(8)
                        if (handle.read(large) != 8) break
                        readUInt64(large)
                    }
                    // 0 means the box runs to the end of the file.
                    0L -> length - offset
                    else -> declared
                }
                if (size < HEADER) break
                offset += size
            }
        }
        return types
    }

    /** Null when these boxes can play. Otherwise a line to show the viewer. */
    fun problem(types: List<String>): String? = when {
        types.isEmpty() -> "The console did not return a video file for this clip."
        !types.contains("ftyp") -> "The console returned a sign-in page instead of video. Sign in again."
        !types.contains("moov") ->
            "The console sent an incomplete recording with no index. Try a shorter time range."
        !types.contains("mdat") -> "The console sent an empty recording for this time range."
        else -> null
    }

    fun problem(file: File): String? = problem(boxTypes(file))

    private const val HEADER = 8

    private fun Char.isBoxChar(): Boolean = this in ' '..'~'

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
        }
        return value
    }

    private fun readUInt64(bytes: ByteArray): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[i].toLong() and 0xff)
        }
        return value
    }
}
