package com.arda.hatirlatici

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ReminderWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx))
    }

    companion object {
        private val tr: Locale = Locale.forLanguageTag("tr-TR")

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ReminderWidget::class.java))
            if (ids.isEmpty()) return
            val rv = build(ctx)
            for (id in ids) mgr.updateAppWidget(id, rv)
        }

        private fun sameDay(a: Calendar, b: Calendar): Boolean =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        private fun rowText(r: Reminder): String {
            if (r.isLocation()) return "📍 " + r.placeName + "  " + r.text
            val a = Calendar.getInstance()
            val b = Calendar.getInstance()
            b.timeInMillis = r.time
            val hm = SimpleDateFormat("HH:mm", tr).format(Date(r.time))
            val label: String
            if (sameDay(a, b)) {
                label = hm
            } else {
                a.add(Calendar.DAY_OF_YEAR, 1)
                label = if (sameDay(a, b)) "Yarın $hm"
                else SimpleDateFormat("d MMM", tr).format(Date(r.time)) + " " + hm
            }
            val icon = if (r.repeat == "SPACED") "🧠" else if (r.repeat != "NONE") "🔁" else "⏰"
            return icon + " " + label + "  " + r.text
        }

        fun build(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            val items = Store.all(ctx)
                .filter { !it.done && it.repeat != "COUNTDOWN" }
                .sortedBy { if (it.isLocation()) Long.MAX_VALUE else it.time }
            val rows = intArrayOf(R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4)

            rv.setTextViewText(
                R.id.w_sub,
                if (items.isEmpty()) "her şey tamam" else items.size.toString() + " bekleyen"
            )
            for (i in rows.indices) {
                if (i < items.size) {
                    rv.setTextViewText(rows[i], rowText(items[i]))
                    rv.setViewVisibility(rows[i], View.VISIBLE)
                } else {
                    rv.setViewVisibility(rows[i], View.GONE)
                }
            }
            rv.setViewVisibility(R.id.w_empty, if (items.isEmpty()) View.VISIBLE else View.GONE)

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.w_root, open)
            return rv
        }
    }
}
