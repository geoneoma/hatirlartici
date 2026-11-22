package com.arda.hatirlatici

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CountdownWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx))
    }

    companion object {
        private val tr: Locale = Locale.forLanguageTag("tr-TR")
        private const val DAY = 86_400_000L

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CountdownWidget::class.java))
            if (ids.isEmpty()) return
            val rv = build(ctx)
            for (id in ids) mgr.updateAppWidget(id, rv)
        }

        private fun shortRemain(ms: Long): String {
            val d = ms / DAY
            val h = (ms % DAY) / 3_600_000L
            val m = (ms % 3_600_000L) / 60_000L
            return if (d > 0) "$d gün" else if (h > 0) "$h sa" else "$m dk"
        }

        fun build(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_countdown)
            val now = System.currentTimeMillis()
            val list = Store.all(ctx)
                .filter { !it.done && it.repeat == "COUNTDOWN" && it.time > now }
                .sortedBy { it.time }

            if (list.isEmpty()) {
                rv.setViewVisibility(R.id.cw_empty, View.VISIBLE)
                rv.setViewVisibility(R.id.cw_title, View.GONE)
                rv.setViewVisibility(R.id.cw_days, View.GONE)
                rv.setViewVisibility(R.id.cw_chrono, View.GONE)
                rv.setViewVisibility(R.id.cw_date, View.GONE)
                rv.setViewVisibility(R.id.cw_more, View.GONE)
            } else {
                val first = list[0]
                val remain = first.time - now
                rv.setViewVisibility(R.id.cw_empty, View.GONE)
                rv.setTextViewText(R.id.cw_title, first.text)
                rv.setViewVisibility(R.id.cw_title, View.VISIBLE)

                if (remain >= DAY) {
                    val d = remain / DAY
                    val h = (remain % DAY) / 3_600_000L
                    rv.setTextViewText(R.id.cw_days, "$d gün $h sa")
                    rv.setViewVisibility(R.id.cw_days, View.VISIBLE)
                    rv.setViewVisibility(R.id.cw_chrono, View.GONE)
                } else {
                    // 24 saatten azsa saniye saniye kendi akar (widget yenilemesi gerekmez)
                    rv.setChronometerCountDown(R.id.cw_chrono, true)
                    rv.setChronometer(R.id.cw_chrono, SystemClock.elapsedRealtime() + remain, null, true)
                    rv.setViewVisibility(R.id.cw_chrono, View.VISIBLE)
                    rv.setViewVisibility(R.id.cw_days, View.GONE)
                }

                val dateStr = SimpleDateFormat("d MMMM EEEE, HH:mm", tr).format(Date(first.time))
                rv.setTextViewText(R.id.cw_date, dateStr)
                rv.setViewVisibility(R.id.cw_date, View.VISIBLE)

                if (list.size > 1) {
                    val second = list[1]
                    rv.setTextViewText(R.id.cw_more, second.text + " · " + shortRemain(second.time - now))
                    rv.setViewVisibility(R.id.cw_more, View.VISIBLE)
                } else {
                    rv.setViewVisibility(R.id.cw_more, View.GONE)
                }
            }

            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.cw_root, open)
            return rv
        }
    }
}
