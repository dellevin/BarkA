package com.trah.accnotify.bark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.R
import com.trah.accnotify.ui.MainActivity
import com.trah.accnotify.util.NotificationImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Maps a Bark payload onto an Android notification.
 *
 * Supported Bark parameters: title, subtitle, body, markdown, group, url,
 * image, icon, level, id (replace), delete. iOS-specific features degrade as
 * follows: `level=critical` maps to a high-importance channel (Android cannot
 * bypass Do Not Disturb without special OEM permissions); `sound` names a .caf
 * file which Android cannot play, so the channel default sound is used;
 * `badge` depends on launcher support and is handled separately.
 */
object BarkNotifier {

    private const val CHANNEL_PASSIVE = "bark_passive"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun show(context: Context, envelope: BarkEnvelope) {
        val payload = envelope.data ?: return
        if (payload.isDelete()) {
            cancel(context, envelope)
            return
        }

        val message = envelope.toLocalMessage()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(manager)

        val title = buildTitle(payload)
        val body = message.body.orEmpty()
        val notificationId = message.localId.hashCode()

        val builder = NotificationCompat.Builder(context, channelForLevel(payload))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body).setBigContentTitle(title))
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(context, message, notificationId))
            .setPriority(priorityForLevel(payload))

        message.group?.trim()?.takeIf { it.isNotEmpty() }?.let { group ->
            builder.setGroup("bark_group:$group")
        }
        if (isPassive(payload)) {
            builder.setSilent(true)
        }
        message.url?.takeIf { it.isNotBlank() }?.let { url ->
            builder.addAction(
                R.drawable.ic_fluent_link,
                "打开链接",
                PendingIntent.getActivity(
                    context, notificationId + 10_000,
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        manager.notify(message.localId, notificationId, builder.build())
        loadAttachments(manager, builder, message)
    }

    fun cancel(context: Context, envelope: BarkEnvelope) {
        val message = envelope.toLocalMessage()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(message.localId, message.localId.hashCode())
    }

    private fun buildTitle(payload: BarkPayload): String {
        val main = payload.title?.takeIf { it.isNotEmpty() } ?: "BarkA"
        val subtitle = payload.subtitle?.takeIf { it.isNotEmpty() }
        return if (subtitle != null) "$main · $subtitle" else main
    }

    private fun buildContentIntent(
        context: Context,
        message: BarkMessage,
        notificationId: Int,
    ): PendingIntent {
        // A url wins and opens the browser; otherwise tapping opens the app history.
        val intent = message.url?.takeIf { it.isNotBlank() }?.let { url ->
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } ?: Intent(context, MainActivity::class.java).apply {
            putExtra("open_messages", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun loadAttachments(
        manager: NotificationManager,
        builder: NotificationCompat.Builder,
        message: BarkMessage,
    ) {
        val imageUrl = message.image ?: message.icon ?: return
        scope.launch {
            val bitmap = NotificationImageLoader.load(imageUrl) ?: return@launch
            val style = if (message.image != null) {
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .setBigContentTitle(message.title)
                    .setSummaryText(message.body)
            } else {
                null
            }
            builder.setLargeIcon(bitmap)
            if (style != null) builder.setStyle(style)
            builder.setOnlyAlertOnce(true)
            manager.notify(message.localId, message.localId.hashCode(), builder.build())
        }
    }

    private fun isPassive(payload: BarkPayload): Boolean =
        payload.extString("level")?.equals("passive", ignoreCase = true) == true

    private fun channelForLevel(payload: BarkPayload): String =
        if (isPassive(payload)) CHANNEL_PASSIVE else AccnotifyApp.CHANNEL_MESSAGES

    private fun priorityForLevel(payload: BarkPayload): Int =
        if (isPassive(payload)) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH

    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_PASSIVE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PASSIVE, "静默通知", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "BarkA 静默级别：仅入库，不响铃亮屏"
                    setShowBadge(true)
                }
            )
        }
    }
}
