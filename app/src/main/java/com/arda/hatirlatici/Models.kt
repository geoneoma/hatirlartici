package com.arda.hatirlatici

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val id: Int,
    var text: String,
    var time: Long,
    var repeat: String = "NONE", // NONE, DAILY, WEEKLY, SPACED, COUNTDOWN
    var persistent: Boolean = true,
    var done: Boolean = false,
    var nagCount: Int = 0,
    var stage: Int = 0,          // SPACED: kaç tekrar çaldı
    var placeName: String = "",  // doluysa konuma bağlı hatırlatma
    var lat: Double = 0.0,
    var lng: Double = 0.0,
    var trigger: String = "ENTER", // ENTER veya EXIT
    var startTime: Long = 0L      // oluşturulma zamanı (geri sayım ilerleme çubuğu için)
) {
    fun isLocation(): Boolean = placeName.isNotEmpty()

    /** "Yaptım" denince hatırlatma tamamen bitmeli mi? */
    fun finishesOnDone(): Boolean =
        repeat == "NONE" || repeat == "COUNTDOWN" || (repeat == "SPACED" && stage >= Spaced.DAYS.size)
}

object Store {
    private const val PREFS = "hatirlatici"
    private const val KEY = "list"
    private const val KEY_ID = "next_id"

    @Synchronized
    fun all(ctx: Context): MutableList<Reminder> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Reminder>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Reminder(
                    id = o.getInt("id"),
                    text = o.getString("text"),
                    time = o.getLong("time"),
                    repeat = o.optString("repeat", "NONE"),
                    persistent = o.optBoolean("persistent", true),
                    done = o.optBoolean("done", false),
                    nagCount = o.optInt("nagCount", 0),
                    stage = o.optInt("stage", 0),
                    placeName = o.optString("place", ""),
                    lat = o.optDouble("lat", 0.0),
                    lng = o.optDouble("lng", 0.0),
                    trigger = o.optString("trigger", "ENTER"),
                    startTime = o.optLong("start", 0L)
                )
            )
        }
        return out
    }

    @Synchronized
    fun saveAll(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("text", r.text)
            o.put("time", r.time)
            o.put("repeat", r.repeat)
            o.put("persistent", r.persistent)
            o.put("done", r.done)
            o.put("nagCount", r.nagCount)
            o.put("stage", r.stage)
            o.put("place", r.placeName)
            o.put("lat", r.lat)
            o.put("lng", r.lng)
            o.put("trigger", r.trigger)
            o.put("start", r.startTime)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        try {
            ReminderWidget.refresh(ctx)
            CountdownWidget.refresh(ctx)
        } catch (e: Exception) {
            // widget güncellenemese de veri kaydedildi
        }
    }

    @Synchronized
    fun find(ctx: Context, id: Int): Reminder? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun update(ctx: Context, r: Reminder) {
        val l = all(ctx)
        val i = l.indexOfFirst { it.id == r.id }
        if (i >= 0) {
            l[i] = r
            saveAll(ctx, l)
        }
    }

    @Synchronized
    fun add(
        ctx: Context,
        text: String,
        time: Long,
        repeat: String,
        persistent: Boolean,
        placeName: String = "",
        lat: Double = 0.0,
        lng: Double = 0.0,
        trigger: String = "ENTER"
    ): Reminder {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getInt(KEY_ID, 1)
        p.edit().putInt(KEY_ID, id + 1).apply()
        val r = Reminder(
            id = id, text = text, time = time, repeat = repeat, persistent = persistent,
            placeName = placeName, lat = lat, lng = lng, trigger = trigger,
            startTime = System.currentTimeMillis()
        )
        val l = all(ctx)
        l.add(r)
        saveAll(ctx, l)
        return r
    }

    @Synchronized
    fun remove(ctx: Context, id: Int) {
        saveAll(ctx, all(ctx).filter { it.id != id })
    }
}

/** Kayıtlı yerler: Ev, İş, Market ... */
data class Place(val name: String, val lat: Double, val lng: Double)

object Places {
    private const val PREFS = "hatirlatici"
    private const val KEY = "places"

    @Synchronized
    fun all(ctx: Context): MutableList<Place> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lng")))
        }
        return out
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Place>) {
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("name", p.name)
            o.put("lat", p.lat)
            o.put("lng", p.lng)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun add(ctx: Context, p: Place) {
        val l = all(ctx).filter { !it.name.equals(p.name, ignoreCase = true) }.toMutableList()
        l.add(p)
        save(ctx, l)
    }

    @Synchronized
    fun remove(ctx: Context, name: String) {
        save(ctx, all(ctx).filter { !it.name.equals(name, ignoreCase = true) })
    }

    fun names(ctx: Context): List<String> = all(ctx).map { it.name }

    fun find(ctx: Context, name: String): Place? =
        all(ctx).firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** Bildirim sorunlarını anlamak için: son alarmın ne zaman çaldığı ve sonucu. */
object Diag {
    private const val PREFS = "hatirlatici"

    fun record(ctx: Context, msg: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("diag_time", System.currentTimeMillis())
            .putString("diag_msg", msg)
            .apply()
    }

    fun time(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("diag_time", 0L)

    fun msg(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("diag_msg", "") ?: ""
}
