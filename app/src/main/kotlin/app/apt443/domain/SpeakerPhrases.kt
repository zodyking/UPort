package app.apt443.domain

/** A short label and the sentence the camera speaker actually says. */
data class SpeakerPhrase(val label: String, val message: String)

fun defaultDoorPhrases(): List<SpeakerPhrase> = listOf(
    SpeakerPhrase("On the way", "Someone is on the way to open the door."),
    SpeakerPhrase("One moment", "One moment, please."),
    SpeakerPhrase("Coming now", "We're coming to the door now."),
)

fun defaultCameraPhrases(): List<SpeakerPhrase> = listOf(
    SpeakerPhrase("We see you", "Hello. We can see you."),
    SpeakerPhrase("One moment", "One moment, please."),
)

/** Phrases shown on an alert. Custom lines replace both lists once any are saved. */
fun AppSettings.speakerPhrases(forDoor: Boolean): List<SpeakerPhrase> {
    if (!useCustomSpeakerPhrases) {
        return if (forDoor) defaultDoorPhrases() else defaultCameraPhrases()
    }
    val custom = customSpeakerPhrases.cleanPhrases()
    if (custom.isEmpty()) return if (forDoor) defaultDoorPhrases() else defaultCameraPhrases()
    return custom
}

fun List<SpeakerPhrase>.cleanPhrases(limit: Int = MAX_SPEAKER_PHRASES): List<SpeakerPhrase> = mapNotNull { phrase ->
    val label = phrase.label.trim().replace("\n", " ").take(PHRASE_LABEL_LIMIT)
    val message = phrase.message.trim().replace("\n", " ").take(PHRASE_MESSAGE_LIMIT)
    if (label.isEmpty() || message.isEmpty()) null else SpeakerPhrase(label, message)
}.distinctBy { it.label.lowercase() + "\u0000" + it.message.lowercase() }.take(limit)

internal fun encodeSpeakerPhrases(phrases: List<SpeakerPhrase>): String {
    return phrases.cleanPhrases().joinToString("\n") { phrase ->
        phrase.label.replace(PHRASE_FIELD, " ") + PHRASE_FIELD + phrase.message.replace(PHRASE_FIELD, " ")
    }
}

internal fun decodeSpeakerPhrases(raw: String?): List<SpeakerPhrase> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.lineSequence().mapNotNull { line ->
        val split = line.split(PHRASE_FIELD, limit = 2)
        if (split.size != 2) null else SpeakerPhrase(split[0], split[1])
    }.toList().cleanPhrases()
}

const val MAX_SPEAKER_PHRASES = 8
const val PHRASE_LABEL_LIMIT = 24
const val PHRASE_MESSAGE_LIMIT = 160
private const val PHRASE_FIELD = "\u001f"
