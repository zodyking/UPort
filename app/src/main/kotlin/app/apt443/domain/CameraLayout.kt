package app.apt443.domain

/** Panoramic / 180° / fisheye cameras need a full grid row. */
fun Camera.isWideAngle(): Boolean {
    val model = model.lowercase()
    val label = name.lowercase()
    return model.contains("view") ||
        model.contains("180") ||
        model.contains("360") ||
        model.contains("wide") ||
        model.contains("fisheye") ||
        model.contains("panoramic") ||
        model.contains("instant") ||
        label.contains("180") ||
        label.contains("360") ||
        label.contains("wide")
}

fun Camera.tileAspectRatio(): Float = if (isWideAngle()) 32f / 9f else 16f / 9f

fun String?.displayLabel(fallback: String): String {
    val trimmed = this?.trim().orEmpty()
    if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) return fallback
    return trimmed
}
