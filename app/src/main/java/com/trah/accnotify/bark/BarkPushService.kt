package com.trah.accnotify.bark

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.google.gson.Gson
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.data.Message
import com.trah.accnotify.status.RuntimeStatusStore
import com.trah.accnotify.status.WebSocketRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Single persistent receiver for Bark pushes.
 *
 * Delivery strategy, channels are mutually exclusive to avoid duplicate
 * notifications:
 *   - WebSocket is primary. While connected, long polling stays parked.
 *   - When the socket fails/closes, an HTTP long-poll loop against /fetch
 *     takes over and acks each message; it yields automatically once the
 *     WebSocket reconnects.
 */
class BarkPushService : Service() {

    private val gson = Gson()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    @Volatile private var wsConnected = false
    @Volatile private var running = false
    private var webSocket: WebSocket? = null

    private lateinit var prefs: BarkPrefs

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Must exceed the server's 20s long-poll hold time.
            .readTimeout(35, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        prefs = BarkPrefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.isReady()) {
            Log.w(TAG, "not registered, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            startPollLoop()
            connectWebSocket()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        webSocket?.close(1000, "service destroyed")
        webSocket = null
        scope.cancel()
        releaseWakeLock()
        RuntimeStatusStore.updateWebSocket(WebSocketRuntimeState.STOPPED)
        super.onDestroy()
    }

    // ---- WebSocket (primary) -------------------------------------------------

    private fun connectWebSocket() {
        val base = prefs.serverUrl
        val key = prefs.deviceKey
        if (base.isEmpty() || key.isEmpty()) return

        val wsUrl = base
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws?device_key=$key"

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnected = true
                RuntimeStatusStore.updateWebSocket(WebSocketRuntimeState.CONNECTED)
                Log.i(TAG, "WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEnvelope(text, ackRequired = false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDrop("closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleDrop(t.message ?: "failure")
            }
        })
    }

    private fun handleDrop(reason: String) {
        if (!running) return
        wsConnected = false
        RuntimeStatusStore.updateWebSocket(WebSocketRuntimeState.RECONNECTING)
        Log.w(TAG, "WebSocket unavailable ($reason), relying on long poll")
        // Reconnect the socket; the poll loop covers the gap meanwhile.
        scope.launch {
            delay(3000)
            if (running && !wsConnected) connectWebSocket()
        }
    }

    // ---- HTTP long polling (fallback) ---------------------------------------

    private fun startPollLoop() {
        scope.launch {
            var backoff = 1000L
            while (running) {
                if (wsConnected) {
                    // Primary channel is active; stay parked but keep watching.
                    delay(2000)
                    continue
                }
                val envelope = try {
                    fetchOnce()
                } catch (t: Throwable) {
                    Log.w(TAG, "fetch error: ${t.message}")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                    continue
                }
                backoff = 1000L
                if (envelope != null) {
                    handleEnvelope(gson.toJson(envelope), ackRequired = true)
                }
            }
        }
    }

    /** Returns a parsed envelope, or null on a 204 long-poll timeout. */
    private fun fetchOnce(): BarkEnvelope? {
        val base = prefs.serverUrl
        val key = prefs.deviceKey
        val request = Request.Builder()
            .url("${base.trimEnd('/')}/fetch?device_key=$key")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 204) return null
            if (!response.isSuccessful) {
                throw IllegalStateException("fetch HTTP ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            return gson.fromJson(text, BarkEnvelope::class.java)
        }
    }

    private fun ack(messageId: String) {
        val base = prefs.serverUrl
        val body = """{"device_key":"${prefs.deviceKey}","id":"$messageId"}"""
        val request = Request.Builder()
            .url("${base.trimEnd('/')}/ack")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        runCatching { httpClient.newCall(request).execute().close() }
            .onFailure { Log.w(TAG, "ack failed: ${it.message}") }
    }

    // ---- Common processing ---------------------------------------------------

    private fun handleEnvelope(raw: String, ackRequired: Boolean) {
        acquireWakeLock()
        try {
            val envelope = gson.fromJson(raw, BarkEnvelope::class.java) ?: return
            if (envelope.type != "message" || envelope.data == null) return
            val message = envelope.toLocalMessage()

            // isArchive defaults to true; "0"/"false" keeps the notice out of history.
            val archive = envelope.data.extString("isarchive")?.let {
                it != "0" && !it.equals("false", ignoreCase = true)
            } ?: true

            scope.launch {
                if (archive && !envelope.data.isDelete()) {
                    runCatching {
                        AccnotifyApp.getInstance().database.messageDao().insert(
                            Message(
                                messageId = message.localId,
                                title = message.title,
                                body = message.body,
                                group = message.group,
                                icon = message.icon,
                                url = message.url,
                                image = message.image,
                                sound = message.sound,
                                timestamp = message.timestamp,
                            )
                        )
                    }
                }
                BarkNotifier.show(this@BarkPushService, envelope)
                if (ackRequired) envelope.id?.let { ack(it) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handleEnvelope failed", t)
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::wake")
                    .apply { setReferenceCounted(false) }
            }
            wakeLock?.acquire(30_000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    companion object {
        private const val TAG = "BarkPushService"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun start(context: Context) {
            // Plain started service: the LSPosed system_server bridge keeps the
            // process alive, so no foreground notification is required.
            context.startService(Intent(context, BarkPushService::class.java))
        }
    }
}
