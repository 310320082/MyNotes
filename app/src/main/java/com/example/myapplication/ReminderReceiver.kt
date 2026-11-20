package com.example.myapplication

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("note_text") ?: return
        val id = intent.getIntExtra("note_id", 0)
        val notification = NotificationCompat.Builder(context, "note_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Напоминание")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }
}
