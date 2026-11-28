package com.arda.hatirlatici

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Notlar" sekmesi: arama, filtre çipleri, not kartları ve yeni not düğmesi. */
class NotesScreen(private val act: Activity) {
    private val tr: Locale = Locale.forLanguageTag("tr-TR")
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val dark = android.R.style.Theme_Material_Dialog_Alert
    private var filter = 0 // 0 Tümü, 1 Notlar, 2 Listeler

    val view: FrameLayout = FrameLayout(act)
    private val countText: TextView
    private val search: EditText
    private val clearBtn: TextView
    private val chipsRow: LinearLayout
    private val listBox: LinearLayout

    private fun dp(v: Int): Int = Ui.dp(act, v)

    init {
        view.setBackgroundColor(Ui.bg)

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(20), dp(20), 0)

        col.addView(Ui.text(act, "Notlar", 28f, Color.WHITE, true))
        countText = Ui.text(act, "", 13f, Ui.sub)
        col.addView(countText)

        // arama kutusu
        val sb = LinearLayout(act)
        sb.orientation = LinearLayout.HORIZONTAL
        sb.gravity = Gravity.CENTER_VERTICAL
        sb.background = Ui.rounded(act, Ui.bar, 26)
        sb.setPadding(dp(18), dp(4), dp(8), dp(4))
        val sbLp = LinearLayout.LayoutParams(match, wrap)
        sbLp.topMargin = dp(18)
        sb.layoutParams = sbLp
        sb.addView(Ui.text(act, "🔍", 16f, Color.WHITE))

        search = EditText(act)
        search.hint = "Notlarda ara…"
        search.setHintTextColor(Ui.sub)
        search.setTextColor(Color.WHITE)
        search.textSize = 16f
        search.background = null
        search.isSingleLine = true
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.setPadding(dp(10), dp(10), dp(6), dp(10))
        sb.addView(search, LinearLayout.LayoutParams(0, wrap, 1f))

        clearBtn = Ui.text(act, "✕", 16f, Ui.sub, true)
        clearBtn.setPadding(dp(12), dp(8), dp(12), dp(8))
        clearBtn.visibility = View.GONE
        clearBtn.setOnClickListener { search.setText("") }
        sb.addView(clearBtn)
        col.addView(sb)

        // filtre çipleri
        chipsRow = LinearLayout(act)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        val chipsLp = LinearLayout.LayoutParams(match, wrap)
        chipsLp.topMargin = dp(14)
        chipsRow.layoutParams = chipsLp
        col.addView(chipsRow)

        // not listesi
        val scroll = ScrollView(act)
        scroll.isVerticalScrollBarEnabled = false
        listBox = LinearLayout(act)
        listBox.orientation = LinearLayout.VERTICAL
        listBox.setPadding(0, dp(6), 0, dp(110))
        scroll.addView(listBox)
        col.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))

        view.addView(col, FrameLayout.LayoutParams(match, match))

        // yeni not düğmesi
        val fab = Ui.circle(act, "＋", 60, 30f, Ui.gradientOval()) { showNewDialog() }
        fab.elevation = dp(8).toFloat()
        val fabLp = FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END)
        fabLp.setMargins(0, 0, dp(22), dp(22))
        view.addView(fab, fabLp)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh()
            }
        })
    }

    // ---------- liste ----------
    fun refresh() {
        val q = search.text.toString().trim().lowercase(tr)
        clearBtn.visibility = if (search.text.isEmpty()) View.GONE else View.VISIBLE

        val all = NotesDb.get(act).allNotes()
        var notes = all.filter { it.matches(q) }
        if (filter == 1) notes = notes.filter { !it.isList() }
        else if (filter == 2) notes = notes.filter { it.isList() }

        countText.text = if (q.isEmpty() && filter == 0) all.size.toString() + " not"
        else notes.size.toString() + " sonuç"

        buildChips(all)

        listBox.removeAllViews()
        if (notes.isEmpty()) {
            if (all.isEmpty()) {
                listBox.addView(emptyView("📝", "Henüz not yok", "Sağ alttaki ＋ düğmesiyle sesli, yazılı\nya da tamamlamalı not ekle."))
            } else {
                listBox.addView(emptyView("🔍", "Sonuç bulunamadı", "Başka bir kelime dene ya da filtreyi değiştir."))
            }
            return
        }
        for (n in notes) listBox.addView(card(n))
    }

    private fun buildChips(all: List<Note>) {
        chipsRow.removeAllViews()
        val labels = arrayOf("Tümü", "Notlar", "Listeler")
        val counts = intArrayOf(all.size, all.count { !it.isList() }, all.count { it.isList() })
        for (i in 0..2) {
            val t = TextView(act)
            t.text = labels[i] + "  " + counts[i]
            t.textSize = 13f
            t.gravity = Gravity.CENTER
            t.setPadding(dp(16), dp(9), dp(16), dp(9))
            if (i == filter) {
                t.background = Ui.gradientRounded(act, 20)
                t.setTextColor(Color.WHITE)
                t.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                t.background = Ui.rounded(act, Ui.bar, 20)
                t.setTextColor(Ui.sub)
            }
            t.setOnClickListener {
                filter = i
                refresh()
            }
            val lp = LinearLayout.LayoutParams(wrap, wrap)
            lp.marginEnd = dp(8)
            chipsRow.addView(t, lp)
        }
    }

    private fun emptyView(emoji: String, title: String, sub: String): View {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(0, dp(60), 0, 0)
        val e = Ui.text(act, emoji, 52f, Color.WHITE)
        e.gravity = Gravity.CENTER
        c.addView(e)
        val t = Ui.text(act, title, 20f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(12), 0, dp(6))
        c.addView(t)
        val s = Ui.text(act, sub, 14f, Ui.sub)
        s.gravity = Gravity.CENTER
        c.addView(s)
        return c
    }

    private fun card(n: Note): View {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.background = Ui.rounded(act, Ui.card, 22)
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(match, wrap)
        lp.bottomMargin = dp(10)
        c.layoutParams = lp
        c.setOnClickListener { open(n.id, n.type, false) }
        c.setOnLongClickListener {
            confirmDelete(n)
            true
        }

        val badge = TextView(act)
        badge.text = if (n.isList()) "☑️" else "📝"
        badge.textSize = 20f
        badge.gravity = Gravity.CENTER
        badge.background = Ui.oval(if (n.isList()) Color.parseColor("#332ED47A") else Color.parseColor("#337C5CFF"))
        badge.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        c.addView(badge)

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, 0, 0)

        // başlık yoksa metnin ilk satırı başlık olur
        var title = n.title
        var preview = n.body.trim()
        if (!n.isList() && title.isBlank() && preview.isNotEmpty()) {
            val first = preview.lines().first().trim()
            title = if (first.length > 60) first.substring(0, 60) + "…" else first
            preview = preview.removePrefix(preview.lines().first()).trim()
        }
        if (title.isBlank()) title = if (n.isList()) "Başlıksız liste" else "Başlıksız not"

        val tv = Ui.text(act, title, 16f, Color.WHITE, true)
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        col.addView(tv)

        if (n.isList()) {
            val total = n.items.size
            val done = n.doneCount()
            val prog = Ui.text(
                act, "$done/$total tamamlandı", 12f,
                if (total > 0 && done == total) Ui.green else Ui.sub
            )
            prog.setPadding(0, dp(3), 0, dp(2))
            col.addView(prog)
            for (item in n.items.filter { x -> !x.checked }.take(3)) {
                val line = Ui.text(act, "○  " + item.text, 13f, Ui.sub)
                line.maxLines = 1
                line.ellipsize = TextUtils.TruncateAt.END
                col.addView(line)
            }
        } else if (preview.isNotEmpty()) {
            val pv = Ui.text(act, preview, 13f, Ui.sub)
            pv.maxLines = 2
            pv.ellipsize = TextUtils.TruncateAt.END
            pv.setPadding(0, dp(3), 0, 0)
            col.addView(pv)
        }

        val d = Ui.text(act, SimpleDateFormat("d MMM · HH:mm", tr).format(Date(n.updatedAt)), 11f, Color.parseColor("#6E7499"))
        d.setPadding(0, dp(6), 0, 0)
        col.addView(d)

        c.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
        return c
    }

    private fun open(id: Long, type: String, dictate: Boolean) {
        val i = Intent(act, NoteEditorActivity::class.java)
        i.putExtra("id", id)
        i.putExtra("type", type)
        i.putExtra("dictate", dictate)
        act.startActivity(i)
    }

    private fun confirmDelete(n: Note) {
        AlertDialog.Builder(act, dark)
            .setTitle("Not silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                NotesDb.get(act).delete(n.id)
                refresh()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---------- yeni not: 3 seçenek ----------
    private fun showNewDialog() {
        val holder = arrayOfNulls<AlertDialog>(1)

        val layout = LinearLayout(act)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(20), dp(8), dp(20), 0)

        fun option(emoji: String, tint: Int, title: String, sub: String, onClick: () -> Unit): View {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = Ui.rounded(act, Ui.bar, 20)
            row.setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(match, wrap)
            lp.bottomMargin = dp(10)
            row.layoutParams = lp

            val b = TextView(act)
            b.text = emoji
            b.textSize = 22f
            b.gravity = Gravity.CENTER
            b.background = Ui.oval(tint)
            b.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            row.addView(b)

            val col = LinearLayout(act)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(14), 0, 0, 0)
            col.addView(Ui.text(act, title, 16f, Color.WHITE, true))
            col.addView(Ui.text(act, sub, 12f, Ui.sub))
            row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))

            row.setOnClickListener {
                holder[0]?.dismiss()
                onClick()
            }
            return row
        }

        layout.addView(option("🎤", Color.parseColor("#33FF5C7A"), "Sesli not", "Konuş, ben yazıya dökeyim") {
            open(0L, "TEXT", true)
        })
        layout.addView(option("✍️", Color.parseColor("#337C5CFF"), "Yazılı not", "Başlık ve metinle klasik not") {
            open(0L, "TEXT", false)
        })
        layout.addView(option("☑️", Color.parseColor("#332ED47A"), "Tamamlamalı not", "Alışveriş listesi gibi: kutuya tik atınca satır üstü çizilir") {
            open(0L, "LIST", false)
        })

        val d = AlertDialog.Builder(act, dark)
            .setTitle("Yeni not")
            .setView(layout)
            .setNegativeButton("Vazgeç", null)
            .create()
        holder[0] = d
        d.show()
    }
}
