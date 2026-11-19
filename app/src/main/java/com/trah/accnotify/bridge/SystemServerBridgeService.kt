package com.trah.accnotify.bridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.trah.accnotify.bark.BarkPushService
import com.trah.accnotify.status.RuntimeStatusStore

class SystemServerBridgeService : Service() {
    private val binder = object : IAccnotifyBridge.Stub() {
        override fun ensureWebSocketRunning() {
            val callingUid = Binder.getCallingUid()
            if (callingUid != Process.SYSTEM_UID) {
                throw SecurityException("Only system_server may control the Accnotify bridge")
            }

            val identity = Binder.clearCallingIdentity()
            try {
                RuntimeStatusStore.markSystemServerConnected()
                BarkPushService.start(applicationContext)
            } catch (t: Throwable) {
                RuntimeStatusStore.markSystemServerDisconnected()
                Log.e(TAG, "Failed to ensure WebSocket service is running", t)
                throw t
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        RuntimeStatusStore.markSystemServerDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        RuntimeStatusStore.markSystemServerDisconnected()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AccnotifyBridge"
    }
}
