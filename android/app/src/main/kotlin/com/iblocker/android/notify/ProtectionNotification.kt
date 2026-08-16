package com.iblocker.android.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.iblocker.android.MainActivity
import com.iblocker.android.R
import com.iblocker.android.quickaction.QuickActions
import java.text.NumberFormat

/** The ongoing notification Android requires for a foreground VPN service. */
object ProtectionNotification {

    const val ID = 1001
    private const val CHANNEL_ID = "protection"

    fun build(context: Context, blockedToday: Long, pausedUntilMillis: Long?): Notification {
        ensureChannel(context)

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val paused = pausedUntilMillis != null && pausedUntilMillis > System.currentTimeMillis()
        val text = if (paused) {
            context.getString(R.string.notification_paused)
        } else {
            context.getString(
                R.string.notification_blocked_today,
                NumberFormat.getInstance().format(blockedToday),
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (paused) {
            builder.addAction(0, context.getString(R.string.action_resume), QuickActions.pending(context, QuickActions.RESUME))
        } else {
            builder.addAction(0, context.getString(R.string.action_pause_5), QuickActions.pending(context, QuickActions.PAUSE_5))
        }
        builder.addAction(0, context.getString(R.string.action_turn_off), QuickActions.pending(context, QuickActions.DISABLE))

        return builder.build()
    }

    fun update(context: Context, blockedToday: Long, pausedUntilMillis: Long?) {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        runCatching { manager.notify(ID, build(context, blockedToday, pausedUntilMillis)) }
    }

    private fun ensureChannel(context: Context) {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
        )
    }
}
