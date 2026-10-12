package app.apt443.domain

/** UniFi Identity (ULP) user shown during tenant sign-in. Not persisted in the site file. */
data class IdentityUser(
    val id: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val status: String = "ACTIVE",
) {
    val shortLabel: String = TenantLabel.short(firstName, lastName)

    val fullName: String
        get() {
            val combined = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
            return combined.ifBlank { shortLabel }
        }
}

/** Resident sign-in must not drop a local admin that the site file already stored. */
fun AppSettings.signInModeKeepingLocalAdmin(hasOsPassword: Boolean): ConsoleSignInMode {
    if (osUsername.isNotBlank() && hasOsPassword) return ConsoleSignInMode.LocalUserAndApiKey
    return signInMode
}

fun AppSettings.withResidentSignIn(user: IdentityUser, hasOsPassword: Boolean): AppSettings {
    return copy(
        accountGate = AccountGate.Console,
        signInMode = signInModeKeepingLocalAdmin(hasOsPassword),
        accountName = user.fullName,
        accountEmail = user.email.trim(),
        accountRole = "Resident",
        identityUserId = user.id,
        residentFirstName = user.firstName.trim().ifBlank { user.shortLabel },
        residentLastName = user.lastName.trim(),
        residentStatus = user.status.ifBlank { "ACTIVE" },
        dataSource = DataSourceMode.Live,
    )
}

object TenantLabel {
    fun short(firstName: String, lastName: String): String {
        val first = firstName.trim()
        val initial = lastName.trim().firstOrNull()?.uppercaseChar()
        return when {
            first.isBlank() && initial == null -> "Resident"
            first.isBlank() -> "${initial}."
            initial == null -> first
            else -> "$first $initial."
        }
    }

    fun matchesFilter(user: IdentityUser, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isBlank()) return true
        return user.firstName.lowercase().contains(q) ||
            user.lastName.lowercase().contains(q) ||
            user.shortLabel.lowercase().contains(q)
    }

    fun emailsEqual(typed: String, onFile: String): Boolean =
        typed.trim().lowercase() == onFile.trim().lowercase()
}

/** What this resident may use, from the UniFi Access API. Not stored in the site file. */
data class ResidentAccess(
    val groups: List<String> = emptyList(),
    val policies: List<String> = emptyList(),
    val doors: List<String> = emptyList(),
    val cameras: List<String> = emptyList(),
    val detail: String = "",
) {
    fun merged(other: ResidentAccess): ResidentAccess = ResidentAccess(
        groups = (groups + other.groups).filter { it.isNotBlank() }.distinct(),
        policies = (policies + other.policies).filter { it.isNotBlank() }.distinct(),
        doors = (doors + other.doors).filter { it.isNotBlank() }.distinct(),
        cameras = (cameras + other.cameras).filter { it.isNotBlank() }.distinct(),
        detail = listOf(detail, other.detail).filter { it.isNotBlank() }.distinct().joinToString(" "),
    )
}

fun AppSettings.residentGreeting(): String {
    val name = residentFirstName.trim().ifBlank { accountName.substringBefore(' ').trim() }
    return if (name.isBlank()) "Hello" else "Hello, $name"
}

fun AppSettings.prefixSpeakerMessage(message: String): String {
    val first = residentFirstName.trim().ifBlank { accountName.substringBefore(' ').trim() }
    val body = message.trim()
    if (first.isBlank() || body.isBlank()) return body
    return "$first said: $body"
}
