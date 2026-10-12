package app.apt443.signin

/**
 * Signed-in status lines for the site VPN. These functions never receive secrets.
 */
object VpnReachCopy {
    data class Line(val stateLabel: String, val summary: String)

    fun tailscale(hasKey: Boolean, tunnelUp: Boolean, needsPermission: Boolean): Line = when {
        !hasKey -> Line(
            stateLabel = "Not connected",
            summary = "Tailscale is selected, and no auth key is saved on this TV.",
        )
        needsPermission -> Line(
            stateLabel = "Needs permission",
            summary = "Allow the VPN on this TV to start Tailscale.",
        )
        tunnelUp -> Line(
            stateLabel = "Connected",
            summary = "Tailscale is connected.",
        )
        else -> Line(
            stateLabel = "Not connected",
            summary = "The auth key is saved on this TV. This app joins Tailscale with it.",
        )
    }

    fun none(): Line = Line(stateLabel = "Not configured", summary = "")
}
