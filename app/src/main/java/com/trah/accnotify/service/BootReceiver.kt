package com.trah.accnotify.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trah.accnotify.bark.BarkPrefs
import com.trah.accnotify.bark.BarkPushService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                if (!BarkPrefs(context).isReady()) return
                BarkPushService.start(context)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start Bark push service after boot", e)
            }
        }
    }
}
