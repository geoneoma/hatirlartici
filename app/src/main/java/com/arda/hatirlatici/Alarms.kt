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
import android.net.Uri
import java.util.Calendar

/** Aralıklı tekrar: 1, 3, 7 ve 21. günlerde hatırlatır. */
object Spaced {
    val DAYS = intArrayOf(1, 3, 7, 21)

    /** stage: şimdi çalan tekrarın numarası (1..3). Bir sonraki tekrarın zamanını verir. */
    fun next(time: Long, stage: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = time
        c.add(Calendar.DAY_OF_YEAR, DAYS[stage] - DAYS[stage - 1])
        return c.timeInMillis
    }
}

/** Günlük/haftalık tekrarın bir sonraki zamanı. */
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
        if (r.done || r.isLocation()) return
        set(ctx, r.time, pi(ctx, r.id, false))
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
    private const val CH = "reminders_v3"
    private const val CH_LOUD = "reminders_v3_loud"

    private fun manager(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun makeChannel(ctx: Context, nm: NotificationManager, id: String, name: String, usage: Int) {
        if (nm.getNotificationChannel(id) != null) return
        val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Zamanı gelen hatırlatmalar (kısa çınlama sesi)"
        ch.setSound(
            Uri.parse("android.resource://" + ctx.packageName + "/" + R.raw.chime),
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 150, 100, 150)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    fun ensureChannel(ctx: Context) {
        val nm = manager(ctx)
        // eski (alarm sesli) kanalları kaldır; kanal sesi sonradan değiştirilemez
        nm.deleteNotificationChannel("reminders_v1")
        nm.deleteNotificationChannel("reminders_v2")
        // normal: telefon sessizken çalmaz. loud: alarm ses seviyesinden çalar, sessizde de duyulur.
        makeChannel(ctx, nm, CH, "Hatırlatmalar", AudioAttributes.USAGE_NOTIFICATION)
        makeChannel(ctx, nm, CH_LOUD, "Hatırlatmalar (sessizde de çalar)", AudioAttributes.USAGE_ALARM)
    }

    fun show(ctx: Context, r: Reminder) {
        val nm = manager(ctx)
        try {
            ensureChannel(ctx)
            val chId = if (AppSettings.loud(ctx)) CH_LOUD else CH

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

            val title = if (r.isLocation()) "📍 " + r.placeName
            else if (r.repeat == "SPACED") "🧠 Aralıklı tekrar " + r.stage + "/" + Spaced.DAYS.size
            else if (r.repeat == "COUNTDOWN") "⏳ Geri sayım bitti"
            else "Hatırlatma"

            val n = Notification.Builder(ctx, chId)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF7C5CFF.toInt())
                .setContentTitle(title)
                .setContentText(r.text)
                .setStyle(Notification.BigTextStyle().bigText(r.text))
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
                .addAction(0, "✔ Yaptım", act(ActionReceiver.DONE, 1))
                .addAction(0, "10 dk ertele", act(ActionReceiver.SNOOZE, 2))
                .build()
            nm.notify(r.id, n)

            val chan = nm.getNotificationChannel(chId)
            val msg = if (!nm.areNotificationsEnabled()) {
                "bildirim izni KAPALI, gösterilemedi"
            } else if (chan != null && chan.importance == NotificationManager.IMPORTANCE_NONE) {
                "bildirim kanalı KAPALI, gösterilemedi"
            } else {
                "bildirim gönderildi"
            }
            Diag.record(ctx, msg)
        } catch (e: Exception) {
            Diag.record(ctx, "HATA: " + e.javaClass.simpleName + " " + (e.message ?: ""))
        }
    }

    fun cancel(ctx: Context, id: Int) {
        manager(ctx).cancel(id)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        Diag.record(ctx, "alarm tetiklendi")
        val r = Store.find(ctx, id)
        if (r == null) {
            Diag.record(ctx, "alarm tetiklendi ama hatırlatma bulunamadı")
            return
        }
        if (r.done) return

        when (intent.action) {
            Scheduler.ACTION_FIRE -> {
                r.nagCount = 0
                r.firedAt = System.currentTimeMillis()
                if (r.repeat == "SPACED") r.stage++
                Notifier.show(ctx, r)
                if (r.repeat == "DAILY" || r.repeat == "WEEKLY") {
                    r.time = advance(r.time, r.repeat)
                    Scheduler.schedule(ctx, r)
                } else if (r.repeat == "SPACED") {
                    if (r.stage < Spaced.DAYS.size) {
                        r.time = Spaced.next(r.time, r.stage)
                        Scheduler.schedule(ctx, r)
                    } else if (!r.persistent) {
                        r.done = true
                    }
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
                if (r.finishesOnDone()) r.done = true
                Store.update(ctx, r)
                if (r.isLocation()) Geo.remove(ctx, id)
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
        Pinned.update(ctx)
        for (r in Store.all(ctx)) {
            if (r.done) continue
            if (r.isLocation()) {
                Geo.register(ctx, r)
                continue
            }
            if (r.time <= now && (r.repeat == "DAILY" || r.repeat == "WEEKLY")) {
                r.time = advance(r.time, r.repeat)
                Store.update(ctx, r)
            }
            // Geçmişte kalmış tek seferlik hatırlatma hemen çalar.
            Scheduler.schedule(ctx, r)
        }
    }
}
