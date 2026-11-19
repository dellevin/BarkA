package com.trah.accnotify.bark

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import java.util.UUID

/**
 * Minimal, plain-text preferences for the Bark-style client.
 *
 * Holds only what Bark needs: the server address, a self-generated device
 * token (sent during /register), the device key returned by the server, and a
 * handful of client-side toggles. Deliberately replaces the old encrypted
 * KeyManager: there are no user secrets here, only an opaque device key.
 */
class BarkPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Base URL of the self-hosted bark-server, e.g. https://bark.example.com */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "")?.trimEnd('/').orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    /**
     * Random UUID generated once on first launch and reused as the APNs-style
     * device_token during registration. The server only needs a stable,
     * non-empty token; the WebSocket is actually addressed by device_key.
     */
    val deviceToken: String
        get() {
            prefs.getString(KEY_DEVICE_TOKEN, null)?.let { return it }
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_TOKEN, generated).apply()
            return generated
        }

    var deviceKey: String
        get() = prefs.getString(KEY_DEVICE_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DEVICE_KEY, value).apply()

    var registered: Boolean
        get() = prefs.getBoolean(KEY_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_REGISTERED, value).apply()

    /** AppCompatDelegate night mode; follows the system until the user toggles it. */
    var nightMode: Int
        get() = prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = prefs.edit().putInt(KEY_NIGHT_MODE, value).apply()

    /** True when the client has everything required to open a connection. */
    fun isReady(): Boolean = serverUrl.isNotEmpty() && registered && deviceKey.isNotEmpty()

    fun clearRegistration() {
        prefs.edit()
            .remove(KEY_DEVICE_KEY)
            .putBoolean(KEY_REGISTERED, false)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "bark_client_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_REGISTERED = "registered"
        private const val KEY_NIGHT_MODE = "night_mode"
    }
}
