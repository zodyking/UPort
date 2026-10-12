package app.apt443.signin

import app.apt443.data.live.ProtectLegacySession
import app.apt443.data.live.UnifiHttp
import app.apt443.data.live.apiKeyHeader
import app.apt443.data.live.consoleHost
import app.apt443.domain.AppSettings
import app.apt443.domain.ConsoleAccount
import app.apt443.domain.ConsoleSignInMode
import app.apt443.domain.SignInResult
import javax.net.ssl.SSLException

/**
 * Connects the TV the same way Home Assistant does for UniFi Protect:
 * API key only (limited) or local console admin plus API key (full).
 */
class ConsoleSignIn(
    private val http: UnifiHttp,
    private val legacy: ProtectLegacySession,
) {
    suspend fun signIn(
        settings: AppSettings,
        apiKey: String,
        password: String,
        mode: ConsoleSignInMode,
    ): SignInResult {
        val host = consoleHost(settings)
        if (host.isBlank()) {
            return SignInResult.Failed("Enter the console address.", untrustedCertificate = false)
        }
        val key = apiKey.trim().ifBlank { ConsoleDiscovery.DEFAULT_API_KEY }
        if (key.isBlank()) {
            return SignInResult.Failed("Enter the Protect API key.", untrustedCertificate = false)
        }

        validateProtectKey(settings, key).getOrElse { error ->
            return when (error) {
                is KeyFailure -> SignInResult.Failed(error.detail, error.untrustedCertificate)
                else -> SignInResult.Failed(
                    "Can't reach that console from this TV. Check the address. Away from home, this TV uses the saved Tailscale auth key.",
                    untrustedCertificate = false,
                )
            }
        }

        return when (mode) {
            ConsoleSignInMode.ApiKeyOnly -> SignInResult.SignedIn(
                ConsoleAccount(
                    displayName = settings.locationName.ifBlank { "Home" },
                    username = "",
                    email = "",
                    role = "Protect viewer",
                ),
            )
            ConsoleSignInMode.LocalUserAndApiKey -> signInLocalUser(settings, password)
        }
    }

    private suspend fun signInLocalUser(settings: AppSettings, password: String): SignInResult {
        if (settings.osUsername.isBlank() || password.isBlank()) {
            return SignInResult.Failed(
                "Enter the local console username and password. Cloud ui.com accounts do not work here.",
                untrustedCertificate = false,
            )
        }
        return legacy.login(settings, password).fold(
            onSuccess = { SignInResult.SignedIn(it) },
            onFailure = { error ->
                val untrusted = generateSequence<Throwable>(error) { it.cause }.any { it is SSLException }
                val http = error.message?.let { message ->
                    Regex("HTTP (\\d+)").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                val message = when {
                    untrusted -> "This console uses its own certificate. Trust it to connect."
                    http == 401 || http == 403 -> "Invalid authentication. Use a local console admin account, not a ui.com cloud user."
                    http == 499 -> "Console dropped the sign-in connection. Turn off Verify SSL or trust the certificate."
                    http != null -> "The console refused sign-in (HTTP $http)."
                    else -> "Can't reach that console from this TV. Check the address."
                }
                SignInResult.Failed(message, untrusted)
            },
        )
    }

    private suspend fun validateProtectKey(settings: AppSettings, apiKey: String): Result<Unit> {
        val url = "https://${consoleHost(settings)}/proxy/protect/integration/v1/cameras"
        return http.text(settings, url, "GET", apiKeyHeader(apiKey)).fold(
            onSuccess = { response ->
                when (response.code) {
                    in 200..299 -> Result.success(Unit)
                    401, 403 -> Result.failure(
                        KeyFailure("That Protect API key was not accepted.", untrustedCertificate = false),
                    )
                    else -> Result.failure(
                        KeyFailure(
                            "The console refused the Protect key (HTTP ${response.code}).",
                            untrustedCertificate = false,
                        ),
                    )
                }
            },
            onFailure = { error ->
                val untrusted = generateSequence<Throwable>(error) { it.cause }.any { it is SSLException }
                Result.failure(
                    if (untrusted) {
                        KeyFailure("This console uses its own certificate. Trust it to connect.", untrustedCertificate = true)
                    } else {
                        error
                    },
                )
            },
        )
    }

    private data class KeyFailure(val detail: String, val untrustedCertificate: Boolean) : Exception(detail)
}
