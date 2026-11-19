package com.trah.accnotify

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.trah.accnotify.data.AppDatabase
import com.trah.accnotify.util.MessageNotificationChannels

class AccnotifyApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize database
        database = AppDatabase.getInstance(this)

        // Create notification channels
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Remove the legacy foreground-service channel left by older versions.
            notificationManager.deleteNotificationChannel(CHANNEL_SERVICE_LEGACY)
            MessageNotificationChannels.register(notificationManager)
        }
    }

    companion object {
        private const val CHANNEL_SERVICE_LEGACY = "accnotify_service"
        const val CHANNEL_MESSAGES = "accnotify_messages"

        @Volatile
        private var instance: AccnotifyApp? = null

        fun getInstance(): AccnotifyApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
