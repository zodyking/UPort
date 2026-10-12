package app.apt443.data

/** Covers the app until Tailscale and the console are back after a restart. */
sealed interface Startup {
    data object Idle : Startup
    data class Loading(val detail: String) : Startup
    data object Ready : Startup
}
