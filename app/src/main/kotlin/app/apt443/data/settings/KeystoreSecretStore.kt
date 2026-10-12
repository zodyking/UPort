package app.apt443.data.settings

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets stay in an app-private file encrypted by an AES key that never
 * leaves the Android Keystore. Non-secret settings use DataStore instead.
 */
interface SecretStore {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun clear(key: String)
}

object SecretKeys {
    const val PROTECT_API_KEY = "protect_api_key"
    const val NETWORK_API_KEY = "network_api_key"
    const val ACCESS_TOKEN = "access_token"
    const val SITE_MANAGER_KEY = "site_manager_key"
    const val OS_PASSWORD = "os_password"
    const val ACCESS_CODE = "access_code"
    const val TELEPORT_CONFIG = "teleport_config"
    const val TAILSCALE_AUTH_KEY = "tailscale_auth_key"
}

class KeystoreSecretStore(
    context: Context,
    fileName: String = "apt443-secrets.json",
) : SecretStore {
    private val file = context.noBackupFilesDir.resolve(fileName)
    private val lock = Any()

    override suspend fun put(key: String, value: String) = putNow(key, value)

    override suspend fun get(key: String): String? = getNow(key)

    override suspend fun clear(key: String) = clearNow(key)

    fun putNow(key: String, value: String) {
        synchronized(lock) {
            val all = readAll()
            all.put(key, encrypt(value))
            writeAll(all)
        }
    }

    fun getNow(key: String): String? {
        return synchronized(lock) {
            val encoded = readAll().optString(key, "")
            if (encoded.isBlank()) null else decrypt(encoded)
        }
    }

    fun clearNow(key: String) {
        synchronized(lock) {
            val all = readAll()
            all.remove(key)
            writeAll(all)
        }
    }

    fun keysNow(): Set<String> = synchronized(lock) {
        val all = readAll()
        all.keys().asSequence().map { it.toString() }.toSet()
    }

    fun clearAllNow() {
        synchronized(lock) {
            writeAll(JSONObject())
        }
    }

    private fun readAll(): JSONObject {
        if (!file.exists()) return JSONObject()
        val text = file.readText()
        if (text.isBlank()) return JSONObject()
        return JSONObject(text)
    }

    private fun writeAll(json: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = JSONObject()
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ct", Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
        return payload.toString()
    }

    private fun decrypt(encoded: String): String {
        val payload = JSONObject(encoded)
        val iv = Base64.decode(payload.getString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(payload.getString("ct"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_ALIAS = "apt443.secrets"
    }
}
