package com.trah.accnotify.bark

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Wire envelope shared by both delivery channels.
 * The WebSocket hub and the long-poll /fetch endpoint emit the same shape:
 * { "type":"message", "id":"<uuid>", "timestamp":1700..., "data": <PushMessage> }
 */
data class BarkEnvelope(
    val type: String? = null,
    val id: String? = null,
    val timestamp: Long = 0L,
    val data: BarkPayload? = null,
)

/**
 * The JSON form of the server's apns.PushMessage. Standard Bark fields are
 * first-class; everything else (group, url, level, copy, icon, image, badge,
 * delete, markdown, ...) rides in ext_params with string-or-primitive values.
 */
data class BarkPayload(
    @SerializedName("id") val externalId: String? = null,
    @SerializedName("device_key") val deviceKey: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val body: String? = null,
    val sound: String? = null,
    @SerializedName("ext_params") val extParams: JsonObject? = null,
) {
    /** Reads an extension parameter as a plain string, tolerating primitives. */
    fun extString(name: String): String? {
        val element: JsonElement = extParams?.get(name) ?: return null
        if (element.isJsonNull) return null
        return if (element.isJsonPrimitive) element.asJsonPrimitive.asString else element.toString()
    }

    fun extStringOr(name: String, fallback: String): String = extString(name) ?: fallback

    fun extBoolean(name: String): Boolean {
        val element = extParams?.get(name) ?: return false
        if (!element.isJsonPrimitive) return false
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isString -> primitive.asString.equals("1", true) ||
                primitive.asString.equals("true", true)
            primitive.isNumber -> primitive.asInt != 0
            else -> false
        }
    }

    fun extInt(name: String, fallback: Int = 0): Int {
        val element = extParams?.get(name) ?: return fallback
        return runCatching {
            if (element.isJsonPrimitive) element.asJsonPrimitive.asInt else fallback
        }.getOrDefault(fallback)
    }

    /** Bark "markdown" takes precedence over body when present. */
    fun effectiveBody(): String? =
        extString("markdown")?.takeIf { it.isNotEmpty() } ?: body

    fun isDelete(): Boolean = extBoolean("delete")
}

/** A locally persisted, notification-ready view derived from an envelope. */
data class BarkMessage(
    val localId: String,
    val title: String?,
    val body: String?,
    val group: String?,
    val icon: String?,
    val url: String?,
    val image: String?,
    val sound: String?,
    val externalId: String?,
    val timestamp: Date,
)

/**
 * Builds the persistent model and a stable local id. The external Bark "id"
 * (replace/delete anchor) is preferred; otherwise the server envelope id is
 * used so each delivery is still de-duplicated.
 */
fun BarkEnvelope.toLocalMessage(): BarkMessage {
    val payload = requireNotNull(data) { "envelope has no data payload" }
    val anchor = payload.externalId?.takeIf { it.isNotEmpty() } ?: id.orEmpty()
    val localId = listOfNotNull(
        payload.deviceKey?.takeIf { k -> k.isNotEmpty() },
        anchor.takeIf { it.isNotEmpty() },
    ).joinToString(":")
    return BarkMessage(
        localId = localId,
        title = payload.title,
        body = payload.effectiveBody(),
        group = payload.extString("group"),
        icon = payload.extString("icon"),
        url = payload.extString("url"),
        image = payload.extString("image"),
        sound = payload.sound,
        externalId = payload.externalId,
        timestamp = Date(),
    )
}
