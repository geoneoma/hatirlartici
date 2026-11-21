package com.arda.hatirlatici

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val id: Int,
    var text: String,
    var time: Long,
    var repeat: String = "NONE", // NONE, DAILY, WEEKLY
    var persistent: Boolean = true,
    var done: Boolean = false,
    var nagCount: Int = 0
)

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
                    nagCount = o.optInt("nagCount", 0)
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
            arr.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
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
    fun add(ctx: Context, text: String, time: Long, repeat: String, persistent: Boolean): Reminder {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getInt(KEY_ID, 1)
        p.edit().putInt(KEY_ID, id + 1).apply()
        val r = Reminder(id, text, time, repeat, persistent)
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
