package app.apt443.data.settings

import app.apt443.domain.PersistentCamera
import app.apt443.domain.PopupLook
import app.apt443.domain.PopupPicture
import org.json.JSONArray
import org.json.JSONObject

/** JSON list of always-on cameras. Unknown layout names fall back to the popup defaults. */
object PersistentCodec {
    fun encode(cameras: List<PersistentCamera>): String {
        val rows = JSONArray()
        cameras.filter { it.cameraId.isNotBlank() }.forEach { camera ->
            val placed = camera.placed()
            rows.put(
                JSONObject()
                    .put("id", placed.cameraId)
                    .put("look", placed.look.name)
                    .put("picture", placed.picture.name)
                    .put("x", placed.x.toDouble())
                    .put("y", placed.y.toDouble())
                    .put("w", placed.width.toDouble())
                    .put("h", placed.height.toDouble()),
            )
        }
        return JSONObject().put("v", 1).put("cameras", rows).toString()
    }

    fun decode(raw: String?): List<PersistentCamera> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val rows = root.optJSONArray("cameras") ?: return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id")
                if (id.isBlank()) continue
                add(
                    PersistentCamera(
                        cameraId = id,
                        look = enumOr(row.optString("look"), PopupLook.Card),
                        picture = enumOr(row.optString("picture"), PopupPicture.Live),
                        x = row.optDouble("x", 0.64).toFloat(),
                        y = row.optDouble("y", 0.06).toFloat(),
                        width = row.optDouble("w", 0.28).toFloat(),
                        height = row.optDouble("h", 0.32).toFloat(),
                    ).placed(),
                )
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}
