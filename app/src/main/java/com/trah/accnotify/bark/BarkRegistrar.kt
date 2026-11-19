package com.trah.accnotify.bark

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Registers the device with a bark-server and stores the returned device key.
 * Mirrors the official bark-server /register contract; device_type is fixed to
 * "android" so the server routes pushes to the WebSocket/queue instead of APNs.
 */
class BarkRegistrar(private val prefs: BarkPrefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Registers against [serverUrl] and persists the key. Reusing the same
     * device token lets the server keep a stable key across re-registration.
     * Returns the device key on success.
     */
    suspend fun register(serverUrl: String): String = withContext(Dispatchers.IO) {
        val base = serverUrl.trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "服务器地址必须以 http:// 或 https:// 开头"
        }

        val payload = JsonObject().apply {
            addProperty("device_token", prefs.deviceToken)
            addProperty("device_type", "android")
        }
        val request = Request.Builder()
            .url("$base/register")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("注册失败：HTTP ${response.code} $bodyText")
            }
            val root = JsonParser.parseString(bodyText).asJsonObject
            // Success envelope: { "code":200, "data": { "key": "...", "device_key": "..." } }
            val data: JsonObject? = root.getAsJsonObject("data")
            val key = data?.get("device_key")?.takeIf { !it.isJsonNull }?.asString
                ?: data?.get("key")?.takeIf { !it.isJsonNull }?.asString
                ?: throw IllegalStateException("注册响应缺少 device key：$bodyText")

            prefs.serverUrl = base
            prefs.deviceKey = key
            prefs.registered = true
            key
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
