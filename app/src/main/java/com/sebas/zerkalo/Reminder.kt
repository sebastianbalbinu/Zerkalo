package com.sebas.zerkalo

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

/** Recordatorio diario y amable para leer, a la hora que elija la persona. */
object Reminder {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("zerkalo", Context.MODE_PRIVATE)

    fun save(ctx: Context, on: Boolean, hour: Int, minute: Int) =
        prefs(ctx).edit().putBoolean("remOn", on).putInt("remH", hour).putInt("remM", minute).apply()

    fun setBook(ctx: Context, title: String) = prefs(ctx).edit().putString("remBook", title).apply()

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 11, Intent(ctx, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx))
        val p = prefs(ctx)
        if (!p.getBoolean("remOn", false)) return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, p.getInt("remH", 21))
            set(Calendar.MINUTE, p.getInt("remM", 30))
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        // Inexacto a propósito: llega con calma, sin despertar el teléfono a la fuerza.
        am.setInexactRepeating(AlarmManager.RTC, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pending(ctx))
    }

    fun show(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("lectura", "Hora de lectura", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val book = prefs(ctx).getString("remBook", "") ?: ""
        val open = PendingIntent.getActivity(
            ctx, 12, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, "lectura")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Tu hora de lectura")
            .setContentText(if (book.isNotBlank()) "$book te espera, cuando quieras." else "Tu libro te espera, cuando quieras.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(5151, n) } catch (_: SecurityException) {}
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminder.show(context)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminder.schedule(context)
            Updates.schedule(context)
        }
    }
}
