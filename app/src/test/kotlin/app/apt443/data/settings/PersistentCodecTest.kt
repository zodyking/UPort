package app.apt443.data.settings

import app.apt443.domain.PersistentCamera
import app.apt443.domain.PopupLook
import app.apt443.domain.PopupPicture
import org.junit.Assert.assertEquals
import org.junit.Test

class PersistentCodecTest {
    @Test
    fun roundTripsPlacementAndLayout() {
        val saved = listOf(
            PersistentCamera(
                cameraId = "front",
                look = PopupLook.Minimal,
                picture = PopupPicture.Snapshot,
                x = 0.1f,
                y = 0.2f,
                width = 0.3f,
                height = 0.25f,
            ),
        )
        val decoded = PersistentCodec.decode(PersistentCodec.encode(saved))
        assertEquals(saved.map { it.placed() }, decoded)
    }

    @Test
    fun blankOrBrokenJsonIsEmpty() {
        assertEquals(emptyList<PersistentCamera>(), PersistentCodec.decode(null))
        assertEquals(emptyList<PersistentCamera>(), PersistentCodec.decode("{"))
    }
}
