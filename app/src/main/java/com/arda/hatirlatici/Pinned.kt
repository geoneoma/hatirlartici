package com.arda.hatirlatici

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Kilit ekranında duran kalıcı bildirim.
 * Geri sayım varsa saniye saniye akan geri sayımı, yoksa sıradaki hatırlatmayı gösterir.
 */
object Pinned {
    private const val CH = "pinned_v1"
    private const val ID = 900001
    private val tr: Locale = Locale.forLanguageTag("tr-TR")

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun whenText(t: Long): String {
        val a = Calendar.getInstance()
        val b = Calendar.getInstance()
        b.timeInMillis = t
        val hm = SimpleDateFormat("HH:mm", tr).format(Date(t))
        if (sameDay(a, b)) return "Bugün $hm"
        a.add(Calendar.DAY_OF_YEAR, 1)
        if (sameDay(a, b)) return "Yarın $hm"
        return SimpleDateFormat("d MMMM EEEE", tr).format(Date(t)) + ", " + hm
    }

    fun update(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            if (!AppSettings.pinned(ctx)) {
                nm.cancel(ID)
                return
            }
            if (nm.getNotificationChannel(CH) == null) {
                // Sessiz ama "uyarı veren" grupta: kilit ekranında ayrıntılı görünür
                val ch = NotificationChannel(CH, "Kilit ekranı kalıcı bildirimi", NotificationManager.IMPORTANCE_DEFAULT)
                ch.description = "Geri sayım ya da sıradaki hatırlatma"
                ch.setSound(null, null)
                ch.enableVibration(false)
                ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                nm.createNotificationChannel(ch)
            }

            val now = System.currentTimeMillis()
            val active = Store.all(ctx).filter { !it.done }
            val cd = active.filter { it.repeat == "COUNTDOWN" && it.time > now }.minByOrNull { it.time }
            val next = active
                .filter { it.repeat != "COUNTDOWN" && !it.isLocation() && it.time > now }
                .minByOrNull { it.time }

            val open = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = Notification.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF7C5CFF.toInt())
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS)

            if (cd != null) {
                b.setContentTitle("⏳ " + cd.text)
                b.setContentText(whenText(cd.time))
                // saniye saniye akan geri sayım
                b.setWhen(cd.time)
                b.setShowWhen(true)
                b.setUsesChronometer(true)
                b.setChronometerCountDown(true)
            } else if (next != null) {
                b.setContentTitle("⏰ " + next.text)
                b.setContentText("Sıradaki hatırlatma · " + whenText(next.time))
                b.setShowWhen(false)
            } else {
                b.setContentTitle("Hatırlatıcı")
                b.setContentText("Yaklaşan hatırlatma yok")
                b.setShowWhen(false)
            }
            nm.notify(ID, b.build())
        } catch (e: Exception) {
            // bildirim kurulamasa da uygulama çalışmaya devam eder
        }
    }
}
