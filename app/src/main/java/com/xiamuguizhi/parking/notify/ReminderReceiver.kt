package com.xiamuguizhi.parking.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * ReminderReceiver
 * 用途：接收到期广播并显示通知提醒用户回到停车位置。
 * 参数：无；通过 Intent extras 接收文案。
 * 返回值：无。
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "停车提醒"
        val text = intent.getStringExtra("text") ?: "您设置的停车计时已到期"
        val channelId = "parking_reminder"

        val nm = NotificationManagerCompat.from(context)
        // 创建通知渠道（Android O+）
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(channelId, "停车提醒", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            mgr.createNotificationChannel(ch)
        }

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

        nm.notify(1001, notif)
    }
}