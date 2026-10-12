package app.apt443.data.settings

import app.apt443.domain.CameraAlertPrefs
import app.apt443.domain.DetectionType
import app.apt443.domain.VoiceTarget
import org.json.JSONArray
import org.json.JSONObject

data class StoredAlertPrefs(
    val default: CameraAlertPrefs = CameraAlertPrefs(),
    val cameras: Map<String, CameraAlertPrefs> = emptyMap(),
)

/**
 * JSON form stored under DataStore key `alert_prefs`:
 * `{"v":1,"default":{...},"cameras":{"<cameraId>":{...}}}` where each prefs object is
 * `{"popups":true,"voice":true,"off":["Face"]}`. Disabled detections are stored (not enabled
 * ones) so detection types added later default to on. Unknown names are ignored and missing
 * fields default to enabled. A legacy `"detections"` (enabled list) is also accepted on read.
 */
object AlertPrefsCodec {
    fun encode(prefs: StoredAlertPrefs): String {
        val cameras = JSONObject()
        prefs.cameras.toSortedMap().forEach { (id, value) ->
            if (id.isNotBlank()) cameras.put(id, encodePrefs(value))
        }
        return JSONObject()
            .put("v", 1)
            .put("default", encodePrefs(prefs.default))
            .put("cameras", cameras)
            .toString()
    }

    fun decode(raw: String?): StoredAlertPrefs {
        if (raw.isNullOrBlank()) return StoredAlertPrefs()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return StoredAlertPrefs()
        val default = root.optJSONObject("default")?.let(::decodePrefs) ?: CameraAlertPrefs()
        val cameras = mutableMapOf<String, CameraAlertPrefs>()
        root.optJSONObject("cameras")?.let { obj ->
            obj.keys().forEach { id ->
                val value = obj.optJSONObject(id) ?: return@forEach
                if (id.isNotBlank()) cameras[id] = decodePrefs(value)
            }
        }
        return StoredAlertPrefs(default, cameras)
    }

    private fun encodePrefs(prefs: CameraAlertPrefs): JSONObject {
        val off = JSONArray()
        DetectionType.entries.filterNot { it in prefs.detections }.forEach { off.put(it.name) }
        val json = JSONObject()
            .put("popups", prefs.popups)
            .put("voice", prefs.voice)
            .put("voiceTarget", prefs.voiceTarget.name)
            .put("off", off)
        val spoken = prefs.spokenDetections
        if (spoken != null) {
            val spokenOff = JSONArray()
            DetectionType.entries.filterNot { it in spoken }.forEach { spokenOff.put(it.name) }
            json.put("spokenOff", spokenOff)
        }
        return json
    }

    private fun decodePrefs(obj: JSONObject): CameraAlertPrefs {
        val detections = when {
            obj.has("off") -> DetectionType.entries.toSet() - names(obj.optJSONArray("off"))
            obj.has("detections") -> names(obj.optJSONArray("detections"))
            else -> DetectionType.entries.toSet()
        }
        val target = obj.optString("voiceTarget")
        val spoken = if (obj.has("spokenOff")) {
            DetectionType.entries.toSet() - names(obj.optJSONArray("spokenOff"))
        } else {
            null
        }
        return CameraAlertPrefs(
            popups = obj.optBoolean("popups", true),
            voice = obj.optBoolean("voice", true),
            voiceTarget = VoiceTarget.entries.firstOrNull { it.name.equals(target, ignoreCase = true) }
                ?: VoiceTarget.Tv,
            detections = detections,
            spokenDetections = spoken,
        )
    }

    private fun names(array: JSONArray?): Set<DetectionType> {
        if (array == null) return emptySet()
        val out = mutableSetOf<DetectionType>()
        for (i in 0 until array.length()) {
            val name = array.optString(i)
            DetectionType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let(out::add)
        }
        return out
    }
}
