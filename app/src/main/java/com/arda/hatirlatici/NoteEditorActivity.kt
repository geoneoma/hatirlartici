package com.arda.hatirlatici

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Not düzenleme ekranı. İki tür:
 *  - TEXT: başlık + metin
 *  - LIST: tamamlamalı liste (kutuya tik atınca satır üstü çizilir ve en alta iner)
 * Çıkarken otomatik kaydeder.
 */
class NoteEditorActivity : Activity() {
    private val reqSpeech = 200
    private val tr: Locale = Locale.forLanguageTag("tr-TR")
    private val dark = android.R.style.Theme_Material_Dialog_Alert
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var db: NotesDb
    private lateinit var note: Note
    private lateinit var titleEdit: EditText
    private var bodyEdit: EditText? = null
    private var itemsBox: LinearLayout? = null
    private lateinit var progressText: TextView
    private val rows = ArrayList<Row>()
    private var deleted = false

    private class Row(val item: NoteItem, val box: TextView, val edit: EditText, val view: View)

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.bg
        window.navigationBarColor = Ui.bg

        db = NotesDb.get(this)
        val id = intent.getLongExtra("id", 0L)
        val type = intent.getStringExtra("type") ?: "TEXT"
        val loaded = if (id != 0L) db.getNote(id) else null
        val now = System.currentTimeMillis()
        note = loaded ?: Note(0L, type, "", "", now, now)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg)

        // ---- üst çubuk ----
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(14), dp(14), dp(14), dp(6))

        val back = Ui.circle(this, "←", 44, 20f, Ui.oval(Ui.bar)) { finish() }
        top.addView(back)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        if (note.isList()) {
            top.addView(Ui.pill(this, "🧹 Temizle") { showClearDialog() })
        }
        val mic = Ui.circle(this, "🎤", 44, 18f, Ui.gradientOval()) { startDictation() }
        val micLp = LinearLayout.LayoutParams(dp(44), dp(44))
        micLp.marginStart = dp(8)
        mic.layoutParams = micLp
        top.addView(mic)
        if (note.id != 0L) {
            top.addView(Ui.pill(this, "🗑") { confirmDelete() })
        }
        root.addView(top)

        // ---- içerik ----
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(8), dp(24), dp(40))

        titleEdit = EditText(this)
        titleEdit.hint = "Başlık"
        titleEdit.setHintTextColor(Color.parseColor("#6E7499"))
        titleEdit.setTextColor(Color.WHITE)
        titleEdit.textSize = 26f
        titleEdit.setTypeface(null, Typeface.BOLD)
        titleEdit.background = null
        titleEdit.setPadding(0, dp(6), 0, dp(6))
        titleEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        titleEdit.imeOptions = EditorInfo.IME_ACTION_NEXT
        titleEdit.setText(note.title)
        col.addView(titleEdit, LinearLayout.LayoutParams(match, wrap))

        val dateStr = SimpleDateFormat("d MMMM EEEE, HH:mm", tr).format(Date(note.createdAt))
        col.addView(Ui.text(this, dateStr, 13f, Ui.sub))

        progressText = Ui.text(this, "", 13f, Ui.green, true)
        progressText.setPadding(0, dp(4), 0, 0)
        col.addView(progressText)

        if (note.isList()) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(0, dp(14), 0, 0)
            itemsBox = box
            col.addView(box, LinearLayout.LayoutParams(match, wrap))

            val add = Ui.text(this, "＋  Madde ekle", 16f, Ui.a2, true)
            add.setPadding(0, dp(14), 0, dp(14))
            add.setOnClickListener { addItemAt(note.items.count { !it.checked }, "") }
            col.addView(add)

            if (note.items.isEmpty()) note.items.add(NoteItem(0L, "", false))
            renderRows()
        } else {
            val body = EditText(this)
            body.hint = "Notunu yaz…"
            body.setHintTextColor(Color.parseColor("#6E7499"))
            body.setTextColor(Color.parseColor("#E6E8FF"))
            body.textSize = 17f
            body.setLineSpacing(dp(4).toFloat(), 1f)
            body.background = null
            body.gravity = Gravity.TOP or Gravity.START
            body.minHeight = dp(300)
            body.setPadding(0, dp(18), 0, 0)
            body.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            body.setText(note.body)
            bodyEdit = body
            col.addView(body, LinearLayout.LayoutParams(match, wrap))
            updateProgress()
        }

        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)

        val isNew = loaded == null
        val dictate = intent.getBooleanExtra("dictate", false)
        if (isNew && dictate) {
            startDictation()
        } else if (isNew) {
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            titleEdit.requestFocus()
        }
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    // ---------- kaydetme ----------
    private fun save() {
        if (deleted) return
        syncItems()
        val title = titleEdit.text.toString().trim()
        val body = bodyEdit?.text?.toString()?.trimEnd() ?: ""
        val items = note.items.filter { it.text.isNotBlank() }.map { NoteItem(it.id, it.text.trim(), it.checked) }
        val empty = title.isEmpty() && (if (note.isList()) items.isEmpty() else body.isEmpty())
        if (empty) {
            // boş not saklanmaz; var olan not tamamen boşaltıldıysa silinir
            if (note.id != 0L) {
                db.delete(note.id)
                note.id = 0L
            }
            return
        }
        val toSave = Note(note.id, note.type, title, body, note.createdAt, note.updatedAt, items.toMutableList())
        db.save(toSave)
        note.id = toSave.id
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this, dark)
            .setTitle("Not silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                deleted = true
                if (note.id != 0L) db.delete(note.id)
                finish()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---------- tamamlamalı liste ----------
    private fun syncItems() {
        for (r in rows) r.item.text = r.edit.text.toString()
    }

    private fun updateProgress() {
        if (!note.isList() || note.items.none { it.text.isNotBlank() }) {
            progressText.text = ""
            progressText.visibility = View.GONE
            return
        }
        val total = note.items.count { it.text.isNotBlank() }
        val done = note.items.count { it.checked && it.text.isNotBlank() }
        progressText.text = "$done/$total tamamlandı"
        progressText.visibility = View.VISIBLE
    }

    private fun renderRows() {
        val box = itemsBox ?: return
        box.removeAllViews()
        rows.clear()
        for (item in note.items) {
            val r = makeRow(item)
            rows.add(r)
            box.addView(r.view)
        }
        updateProgress()
    }

    private fun makeRow(item: NoteItem): Row {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.HORIZONTAL
        v.gravity = Gravity.CENTER_VERTICAL
        v.setPadding(0, dp(5), 0, dp(5))

        val box = TextView(this)
        box.gravity = Gravity.CENTER
        box.textSize = 16f
        box.setTextColor(Color.WHITE)
        box.setTypeface(null, Typeface.BOLD)
        v.addView(box, LinearLayout.LayoutParams(dp(28), dp(28)))

        val edit = EditText(this)
        edit.textSize = 17f
        edit.background = null
        edit.setPadding(0, dp(6), 0, dp(6))
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.imeOptions = EditorInfo.IME_ACTION_NEXT
        edit.setText(item.text)
        edit.hint = "Madde"
        edit.setHintTextColor(Color.parseColor("#6E7499"))
        val editLp = LinearLayout.LayoutParams(0, wrap, 1f)
        editLp.marginStart = dp(14)
        v.addView(edit, editLp)

        val del = Ui.text(this, "✕", 16f, Ui.sub)
        del.setPadding(dp(10), dp(6), dp(4), dp(6))
        v.addView(del)

        val row = Row(item, box, edit, v)
        styleRow(row)

        box.setOnClickListener {
            syncItems()
            item.checked = !item.checked
            // işaretliler en alta iner, işaretsizler üstte kalır
            val openItems = note.items.filter { !it.checked }
            val doneItems = note.items.filter { it.checked }
            note.items = (openItems + doneItems).toMutableList()
            renderRows()
        }
        del.setOnClickListener {
            syncItems()
            note.items.remove(item)
            renderRows()
        }
        edit.setOnEditorActionListener { _, _, _ ->
            syncItems()
            if (item.text.isNotBlank()) {
                val idx = note.items.indexOf(item)
                val openCount = note.items.count { !it.checked }
                val at = if (item.checked) openCount else minOf(idx + 1, openCount)
                addItemAt(at, "")
            }
            true
        }
        return row
    }

    private fun styleRow(r: Row) {
        val checked = r.item.checked
        val g = if (checked) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.a1, Ui.a2))
        else GradientDrawable()
        g.cornerRadius = dp(8).toFloat()
        if (checked) {
            r.box.text = "✓"
        } else {
            g.setColor(Color.TRANSPARENT)
            g.setStroke(dp(2), Ui.sub)
            r.box.text = ""
        }
        r.box.background = g
        r.edit.setTextColor(if (checked) Ui.sub else Color.WHITE)
        r.edit.paintFlags = if (checked) r.edit.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else r.edit.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
    }

    private fun addItemAt(at: Int, text: String) {
        syncItems()
        val item = NoteItem(0L, text, false)
        note.items.add(at.coerceIn(0, note.items.size), item)
        renderRows()
        val row = rows.firstOrNull { it.item === item }
        if (row != null) {
            row.edit.post {
                row.edit.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(row.edit, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun showClearDialog() {
        syncItems()
        val doneCount = note.items.count { it.checked }
        val opts = arrayOf("Tamamlananları sil ($doneCount)", "Tüm maddeleri sil")
        AlertDialog.Builder(this, dark)
            .setTitle("Listeyi temizle")
            .setItems(opts) { _, which ->
                syncItems()
                if (which == 0) note.items.removeAll { it.checked } else note.items.clear()
                if (note.items.isEmpty()) note.items.add(NoteItem(0L, "", false))
                renderRows()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---------- sesli yazma (Android'in ses tanıma ekranı) ----------
    private fun startDictation() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        i.putExtra(
            RecognizerIntent.EXTRA_PROMPT,
            if (note.isList()) "Maddeleri söyle (ayırmak için “ve” de)" else "Notunu söyle"
        )
        try {
            startActivityForResult(i, reqSpeech)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Bu telefonda sesli giriş bulunamadı. Google uygulamasını kontrol et.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqSpeech && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) applyDictation(spoken)
        }
    }

    private fun cap(s: String): String = s.replaceFirstChar { it.titlecase(tr) }

    /** "süt, ekmek ve yumurta" -> ["süt", "ekmek", "yumurta"] */
    private fun splitItems(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (w in s.replace(",", " , ").trim().split(Regex("\\s+"))) {
            if (w == "," || w.lowercase(tr) == "ve") {
                if (cur.isNotBlank()) out.add(cur.toString().trim())
                cur.setLength(0)
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(w)
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }

    private fun applyDictation(spoken: String) {
        if (note.isList()) {
            syncItems()
            note.items.removeAll { it.text.isBlank() }
            var at = note.items.count { !it.checked }
            for (p in splitItems(spoken)) {
                note.items.add(at, NoteItem(0L, cap(p), false))
                at++
            }
            renderRows()
        } else {
            val b = bodyEdit ?: return
            val cur = b.text.toString()
            val add = if (cur.isBlank()) cap(spoken) else spoken
            val sep = if (cur.isBlank() || cur.endsWith("\n") || cur.endsWith(" ")) "" else " "
            b.setText(cur + sep + add)
            b.setSelection(b.text.length)
        }
    }
}
