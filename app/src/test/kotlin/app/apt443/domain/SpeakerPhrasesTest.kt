package app.apt443.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpeakerPhrasesTest {
    @Test
    fun defaultsStayUntilACustomListExists() {
        val settings = AppSettings()
        assertEquals(defaultDoorPhrases(), settings.speakerPhrases(forDoor = true))
        assertEquals(defaultCameraPhrases(), settings.speakerPhrases(forDoor = false))
        val switched = settings.copy(useCustomSpeakerPhrases = true)
        assertEquals(defaultCameraPhrases(), switched.speakerPhrases(forDoor = false))
    }

    @Test
    fun customLinesReplaceBothLists() {
        val custom = listOf(SpeakerPhrase("Quiet", "Please keep it down."))
        val settings = AppSettings(useCustomSpeakerPhrases = true, customSpeakerPhrases = custom)
        assertEquals(custom, settings.speakerPhrases(forDoor = true))
        assertEquals(custom, settings.speakerPhrases(forDoor = false))
    }

    @Test
    fun phrasesRoundTripAndDropBlanks() {
        val saved = encodeSpeakerPhrases(
            listOf(
                SpeakerPhrase("  Hello  ", "We can see you."),
                SpeakerPhrase("", "nope"),
                SpeakerPhrase("Hello", "We can see you."),
            ),
        )
        val loaded = decodeSpeakerPhrases(saved)
        assertEquals(1, loaded.size)
        assertEquals("Hello", loaded.single().label)
        assertFalse(loaded.single().message.contains("\n"))
    }
}
