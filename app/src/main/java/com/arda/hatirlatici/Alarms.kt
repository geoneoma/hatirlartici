package com.arda.hatirlatici

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import java.util.Calendar

/** Tekrarlayan hatırlatmanın bir sonraki zamanını hesaplar. */
fun advance(time: Long, repeat: String): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = time
    val step = if (repeat == "WEEKLY") 7 else 1
    val now = System.currentTimeMillis()
    do {
        c.add(Calendar.DAY_OF_YEAR, step)
    } while (c.timeInMillis <= now)
    return c.timeInMillis
}

object Scheduler {
    const val ACTION_FIRE = "com.arda.hatirlatici.FIRE"
    const val ACTION_NAG = "com.arda.hatirlatici.NAG"

    private fun pi(ctx: Context, id: Int, nag: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        i.action = if (nag) ACTION_NAG else ACTION_FIRE
        i.putExtra("id", id)
        val code = id * 2 + (if (nag) 1 else 0)
        return PendingIntent.getBroadcast(
            ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun alarmManager(ctx: Context): AlarmManager =
        ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // setAlarmClock: en güvenilir yöntem; ek izin gerektirmez, uyku modunda da çalar.
    private fun set(ctx: Context, at: Long, op: PendingIntent) {
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
    }

    fun schedule(ctx: Context, r: Reminder) {
        if (!r.done) set(ctx, r.time, pi(ctx, r.id, false))
    }

    fun nag(ctx: Context, r: Reminder, minutes: Int) {
        set(ctx, System.currentTimeMillis() + minutes * 60_000L, pi(ctx, r.id, true))
    }

    fun cancelNag(ctx: Context, id: Int) {
        alarmManager(ctx).cancel(pi(ctx, id, true))
    }

    fun cancel(ctx: Context, id: Int) {
        alarmManager(ctx).cancel(pi(ctx, id, false))
        alarmManager(ctx).cancel(pi(ctx, id, true))
    }
}

object Notifier {
    private const val CH = "reminders_v1"

    private fun manager(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun show(ctx: Context, r: Reminder) {
        val nm = manager(ctx)
        if (nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 500, 300, 500)
            nm.createNotificationChannel(ch)
        }

        val open = PendingIntent.getActivity(
            ctx, r.id, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun act(action: String, code: Int): PendingIntent {
            val i = Intent(ctx, ActionReceiver::class.java)
            i.action = action
            i.putExtra("id", r.id)
            return PendingIntent.getBroadcast(
                ctx, r.id * 4 + code, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Hatırlatma")
            .setContentText(r.text)
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(false)
            .addAction(0, "✔ Yaptım", act(ActionReceiver.DONE, 1))
            .addAction(0, "10 dk ertele", act(ActionReceiver.SNOOZE, 2))
            .build()
        nm.notify(r.id, n)
    }

    fun cancel(ctx: Context, id: Int) {
        manager(ctx).cancel(id)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val r = Store.find(ctx, id) ?: return
        if (r.done) return

        when (intent.action) {
            Scheduler.ACTION_FIRE -> {
                r.nagCount = 0
                Notifier.show(ctx, r)
                if (r.repeat != "NONE") {
                    r.time = advance(r.time, r.repeat)
                    Scheduler.schedule(ctx, r)
                } else if (!r.persistent) {
                    r.done = true
                }
                Store.update(ctx, r)
                if (r.persistent) Scheduler.nag(ctx, r, 5)
            }
            Scheduler.ACTION_NAG -> {
                Notifier.show(ctx, r)
                r.nagCount++
                Store.update(ctx, r)
                if (r.persistent && r.nagCount < 12) {
                    Scheduler.nag(ctx, r, if (r.nagCount < 2) 3 else 2)
                }
            }
        }
    }
}

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val DONE = "com.arda.hatirlatici.DONE"
        const val SNOOZE = "com.arda.hatirlatici.SNOOZE"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        Notifier.cancel(ctx, id)
        val r = Store.find(ctx, id) ?: return
        when (intent.action) {
            DONE -> {
                Scheduler.cancelNag(ctx, id)
                r.nagCount = 0
                if (r.repeat == "NONE") r.done = true
                Store.update(ctx, r)
            }
            SNOOZE -> {
                r.nagCount = 0
                Store.update(ctx, r)
                Scheduler.nag(ctx, r, 10)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val now = System.currentTimeMillis()
        for (r in Store.all(ctx)) {
            if (r.done) continue
            if (r.time <= now && r.repeat != "NONE") {
                r.time = advance(r.time, r.repeat)
                Store.update(ctx, r)
            }
            // Geçmişte kalmış tek seferlik hatırlatma hemen çalar.
            Scheduler.schedule(ctx, r)
        }
    }
}
