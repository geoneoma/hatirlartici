package com.arda.hatirlatici

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val reqSpeech = 100
    private val tr: Locale = Locale.forLanguageTag("tr-TR")
    private lateinit var listView: ListView
    private lateinit var input: EditText
    private var items: List<Reminder> = emptyList()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(28), dp(16), dp(12))

        val title = TextView(this)
        title.text = "Hatırlatıcı"
        title.textSize = 28f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        val hint = TextView(this)
        hint.text = "Mikrofona dokun ve söyle:\n“yarın sabah 9'da ilacımı al”\n“20 dakika sonra çamaşırı çıkar”\n“her salı akşam 8'de piyano çalış”"
        hint.setPadding(0, dp(4), 0, dp(12))
        root.addView(hint)

        val mic = Button(this)
        mic.text = "🎤  Sesli not ekle"
        mic.textSize = 20f
        mic.setOnClickListener { startSpeech() }
        root.addView(mic, LinearLayout.LayoutParams(match, dp(72)))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        input = EditText(this)
        input.hint = "ya da yazarak ekle…"
        input.isSingleLine = true
        row.addView(input, LinearLayout.LayoutParams(0, wrap, 1f))
        val add = Button(this)
        add.text = "Ekle"
        add.setOnClickListener {
            val s = input.text.toString().trim()
            if (s.isNotEmpty()) handleText(s)
        }
        row.addView(add)
        root.addView(row)

        val empty = TextView(this)
        empty.text = "Henüz hatırlatma yok."
        empty.setPadding(0, dp(24), 0, 0)
        root.addView(empty)

        listView = ListView(this)
        root.addView(listView, LinearLayout.LayoutParams(match, 0, 1f))
        listView.emptyView = empty
        listView.setOnItemClickListener { _, _, pos, _ ->
            if (pos in items.indices) showItemMenu(items[pos])
        }

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = Store.all(this).sortedWith(compareBy<Reminder>({ it.done }, { it.time }))
        val now = System.currentTimeMillis()
        val labels = items.map { r ->
            val icon = if (r.done) "✅" else if (r.repeat == "NONE" && r.time < now) "🔔" else "⏳"
            "$icon  ${r.text}\n${formatWhen(r.time, r.repeat)}"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun formatWhen(time: Long, repeat: String): String {
        val s = SimpleDateFormat("d MMMM EEEE, HH:mm", tr).format(Date(time))
        return when (repeat) {
            "DAILY" -> "$s  (her gün)"
            "WEEKLY" -> "$s  (her hafta)"
            else -> s
        }
    }

    private fun startSpeech() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Örn: yarın sabah 9'da ilacımı al")
        try {
            startActivityForResult(i, reqSpeech)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Bu telefonda sesli giriş bulunamadı. Google uygulamasını kontrol et ya da yazarak ekle.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqSpeech && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) handleText(text)
        }
    }

    private fun handleText(raw: String) {
        val p = TrParser.parse(raw)
        val whenMs = p.time
        if (whenMs == null) {
            input.setText(raw)
            AlertDialog.Builder(this)
                .setTitle("Zamanı anlayamadım")
                .setMessage("“$raw”\n\nİçinde bir zaman bulamadım. Örnek: “yarın sabah 9'da …”, “20 dakika sonra …”, “her salı akşam 8'de …”.\n\nMetni aşağıdaki kutuya aktardım; düzeltip tekrar ekleyebilirsin.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }

        val box = CheckBox(this)
        box.text = "Yapana kadar tekrar tekrar hatırlat"
        box.isChecked = true

        val msg = TextView(this)
        msg.text = p.text + "\n\n" + formatWhen(whenMs, p.repeat)
        msg.textSize = 18f
        msg.setPadding(0, 0, 0, dp(12))

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(24), dp(16), dp(24), 0)
        layout.addView(msg)
        layout.addView(box)

        AlertDialog.Builder(this)
            .setTitle("Böyle kaydedeyim mi?")
            .setView(layout)
            .setPositiveButton("Kaydet") { _, _ ->
                val r = Store.add(this, p.text, whenMs, p.repeat, box.isChecked)
                Scheduler.schedule(this, r)
                input.setText("")
                refresh()
                Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showItemMenu(r: Reminder) {
        val opts = if (r.done) arrayOf("Sil") else arrayOf("Yaptım", "Sil")
        AlertDialog.Builder(this)
            .setTitle(r.text)
            .setItems(opts) { _, which ->
                if (opts[which] == "Sil") {
                    Scheduler.cancel(this, r.id)
                    Notifier.cancel(this, r.id)
                    Store.remove(this, r.id)
                } else {
                    Scheduler.cancelNag(this, r.id)
                    Notifier.cancel(this, r.id)
                    r.nagCount = 0
                    if (r.repeat == "NONE") r.done = true
                    Store.update(this, r)
                }
                refresh()
            }
            .show()
    }
}
