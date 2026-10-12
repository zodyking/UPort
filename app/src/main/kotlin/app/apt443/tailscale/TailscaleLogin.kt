package app.apt443.tailscale

import org.json.JSONObject

/**
 * JSON for Tailscale's local API. These functions never log or echo the auth key
 * except inside the request body that is sent only to the in-app engine.
 */
object TailscaleLogin {
    const val STATE_NEEDS_LOGIN = 2
    const val STATE_NEEDS_MACHINE_AUTH = 3
    const val STATE_RUNNING = 6
    const val NOTIFY_INITIAL_STATE = 2L

    const val PERMISSION_DENIED = "Allow the VPN on this TV so it can join Tailscale."
    const val LIBRARY_MISSING = "Tailscale could not start on this TV."
    const val AUTH_REJECTED = "Tailscale did not accept the auth key."
    const val TIMED_OUT = "Tailscale did not connect. Check the auth key and try again."
    const val NEEDS_APPROVAL = "This TV is waiting for approval in the Tailscale admin console."
    const val TUNNEL_FAILED = "Tailscale signed in, but the VPN on this TV did not start."
    const val CONSOLE_UNREACHABLE =
        "Tailscale is connected, and this address did not answer. On the home network, enter the console's home address. A 100.x address works only when that machine is the console itself."

    fun consoleUnreachable(host: String, reason: String, lanConsole: String?): String {
        val where = host.trim().ifBlank { "this address" }
        val why = reason.trim().let { if (it.isEmpty()) "" else " $it" }
        val next = if (lanConsole.isNullOrBlank()) {
            "On the home network, enter the console's home address (often 192.168.0.1). A 100.x address works only when that machine is the console itself."
        } else {
            "This TV is on the home network, and the console at $lanConsole did answer. Enter that address."
        }
        return "Tailscale is connected, but $where did not answer.$why $next"
    }

    fun maskedPrefsJson(loginServer: String): String {
        val body = JSONObject()
            .put("LoggedOut", false)
            .put("LoggedOutSet", true)
        val server = loginServer.trim()
        if (server.isNotEmpty()) {
            body.put("ControlURL", server)
            body.put("ControlURLSet", true)
        }
        return body.toString()
    }

    fun withWantRunning(prefsJson: String): String {
        val prefs = JSONObject(prefsJson)
        prefs.put("WantRunning", true)
        return prefs.toString()
    }

    fun startJson(prefsJson: String, authKey: String): String {
        val body = JSONObject().put("UpdatePrefs", JSONObject(withWantRunning(prefsJson)))
        val key = authKey.trim()
        if (key.isNotEmpty()) body.put("AuthKey", key)
        return body.toString()
    }

    fun stateFromNotify(json: String): Int? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!root.has("State") || root.isNull("State")) return null
        return root.optInt("State")
    }

    fun safeFailure(error: Throwable, authKey: String): String {
        var current: Throwable? = error
        while (current != null) {
            if (current is UnsatisfiedLinkError) return LIBRARY_MISSING
            val message = current.message.orEmpty()
            if (message.contains("libgojni", ignoreCase = true) || message.contains("libtailscale.Libtailscale")) {
                return LIBRARY_MISSING
            }
            current = current.cause
        }
        return safeFailure(error.message, authKey)
    }

    fun safeFailure(raw: String?, authKey: String): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return AUTH_REJECTED
        if (text.contains("libtailscale.Libtailscale") || text.contains("libgojni", ignoreCase = true)) {
            return LIBRARY_MISSING
        }
        if (authKey.isNotBlank() && text.contains(authKey)) return AUTH_REJECTED
        if (text.contains("auth key", ignoreCase = true) || text.contains("authkey", ignoreCase = true)) {
            return AUTH_REJECTED
        }
        return text
    }
}
