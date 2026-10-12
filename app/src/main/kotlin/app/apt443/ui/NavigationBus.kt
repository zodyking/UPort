package app.apt443.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CameraRequest(val id: String, val atEpochMs: Long)

object NavigationBus {
    private val requests = MutableStateFlow<CameraRequest?>(null)
    val camera: StateFlow<CameraRequest?> = requests

    fun open(cameraId: String) {
        if (cameraId.isBlank()) return
        requests.value = CameraRequest(cameraId, System.currentTimeMillis())
    }
}
