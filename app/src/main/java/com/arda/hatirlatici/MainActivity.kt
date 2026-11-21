package com.arda.hatirlatici

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val reqSpeech = 100
    private val tr: Locale = Locale.forLanguageTag("tr-TR")
    private lateinit var content: LinearLayout
    private lateinit var input: EditText

    private val cBg = Color.parseColor("#0E1020")
    private val cCard = Color.parseColor("#1A1E36")
    private val cCardDone = Color.parseColor("#13162A")
    private val cBar = Color.parseColor("#232848")
    private val cSub = Color.parseColor("#9AA0C0")
    private val cA1 = Color.parseColor("#7C5CFF")
    private val cA2 = Color.parseColor("#00C2FF")
    private val cAmber = Color.parseColor("#FFB020")
    private val cGreen = Color.parseColor("#2ED47A")

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- çizim yardımcıları ----------
    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun oval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    private fun gradientOval(): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cA1, cA2))
        g.shape = GradientDrawable.OVAL
        return g
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun circle(label: String, sizeDp: Int, textSize: Float, bg: GradientDrawable, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = textSize
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.background = bg
        t.setOnClickListener { onClick() }
        t.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return t
    }

    private fun pill(label: String, onClick: () -> Unit): TextView {
        val t = text(label, 14f, Color.WHITE, true)
        t.background = rounded(cBar, 18)
        t.setPadding(dp(16), dp(9), dp(16), dp(9))
        t.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(wrap, wrap)
        lp.marginStart = dp(8)
        t.layoutParams = lp
        return t
    }

    // ---------- yaşam döngüsü ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        Notifier.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val frame = FrameLayout(this)
        frame.setBackgroundColor(cBg)

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20), dp(20), dp(20), dp(130))
        scroll.addView(content)
        frame.addView(scroll, FrameLayout.LayoutParams(match, match))

        val barLp = FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM)
        barLp.setMargins(dp(14), 0, dp(14), dp(14))
        frame.addView(buildBottomBar(), barLp)

        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- alt çubuk: yazı kutusu + gönder + mikrofon ----------
    private fun buildBottomBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = rounded(cBar, 34)
        bar.setPadding(dp(20), dp(8), dp(8), dp(8))
        bar.elevation = dp(8).toFloat()

        input = EditText(this)
        input.hint = "Yaz ya da mikrofona dokun…"
        input.setHintTextColor(cSub)
        input.setTextColor(Color.WHITE)
        input.textSize = 16f
        input.background = null
        input.isSingleLine = true
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, _, _ ->
            submitTyped()
            true
        }
        bar.addView(input, LinearLayout.LayoutParams(0, wrap, 1f))

        val send = circle("➤", 44, 18f, oval(Color.parseColor("#2F3560"))) { submitTyped() }
        (send.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        bar.addView(send)

        bar.addView(circle("🎤", 56, 24f, gradientOval()) { startSpeech() })
        return bar
    }

    private fun submitTyped() {
        val s = input.text.toString().trim()
        if (s.isNotEmpty()) handleText(s)
    }

    // ---------- ana liste ----------
    private fun notificationsOn(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    private fun refresh() {
        content.removeAllViews()
        val all = Store.all(this)
        val now = System.currentTimeMillis()
        val pending = all.count { !it.done }

        content.addView(buildHeader(pending))
        if (!notificationsOn()) content.addView(buildWarning())

        if (all.isEmpty()) {
            content.addView(buildEmpty())
            addDiag()
            return
        }

        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val startTomorrow = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val startAfter = cal.timeInMillis

        val active = all.filter { !it.done }.sortedBy { it.time }
        val overdue = active.filter { it.repeat == "NONE" && it.time < now }
        val rest = active.filter { !(it.repeat == "NONE" && it.time < now) }
        val today = rest.filter { it.time < startTomorrow }
        val tomorrow = rest.filter { it.time >= startTomorrow && it.time < startAfter }
        val later = rest.filter { it.time >= startAfter }
        val done = all.filter { it.done }.sortedByDescending { it.time }

        addSection("Çaldı · onay bekliyor", overdue)
        addSection("Bugün", today)
        addSection("Yarın", tomorrow)
        addSection("Daha sonra", later)
        addSection("Tamamlanan", done)
        addDiag()
    }

    private fun buildHeader(pending: Int): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text("Hatırlatıcı", 30f, Color.WHITE, true))
        val dateStr = SimpleDateFormat("d MMMM EEEE", tr).format(Date())
        val state = if (pending == 0) "her şey tamam" else "$pending bekleyen"
        col.addView(text("$dateStr  ·  $state", 14f, cSub))
        row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))

        row.addView(pill("Test") { scheduleTest() })
        row.addView(pill("?") { showHelp() })
        return row
    }

    private fun buildWarning(): View {
        val w = LinearLayout(this)
        w.orientation = LinearLayout.VERTICAL
        w.background = rounded(Color.parseColor("#33FFB020"), 18)
        w.setPadding(dp(18), dp(16), dp(18), dp(16))
        w.addView(text("⚠️  Bildirimler kapalı", 16f, cAmber, true))
        w.addView(text("Alarm çalsa bile bildirim görünmez.", 14f, Color.WHITE))
        val btn = text("Ayarları aç", 14f, cAmber, true)
        btn.setPadding(0, dp(10), 0, 0)
        btn.setOnClickListener { openNotificationSettings() }
        w.addView(btn)
        val lp = LinearLayout.LayoutParams(match, wrap)
        lp.topMargin = dp(18)
        w.layoutParams = lp
        return w
    }

    private fun buildEmpty(): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(0, dp(90), 0, 0)
        val e = text("🗓️", 56f, Color.WHITE)
        e.gravity = Gravity.CENTER
        c.addView(e)
        val t = text("Henüz hatırlatma yok", 20f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(12), 0, dp(6))
        c.addView(t)
        val s = text("Mikrofona dokun ve söyle:\n“yarın sabah 9'da ilacı al”\n“20 dakika sonra çamaşırı çıkar”\n“her salı akşam 8'de piyano çalış”", 14f, cSub)
        s.gravity = Gravity.CENTER
        c.addView(s)
        return c
    }

    private fun addSection(title: String, list: List<Reminder>) {
        if (list.isEmpty()) return
        val h = text(title.uppercase(tr), 12f, cSub, true)
        h.letterSpacing = 0.08f
        h.setPadding(dp(4), dp(22), 0, dp(10))
        content.addView(h)
        for (r in list) content.addView(buildCard(r))
    }

    private fun addDiag() {
        val t = Diag.time(this)
        if (t == 0L) return
        val s = SimpleDateFormat("d MMM HH:mm", tr).format(Date(t))
        val d = text("Son alarm: $s · ${Diag.msg(this)}", 11f, cSub)
        d.setPadding(dp(4), dp(28), 0, 0)
        content.addView(d)
    }

    private fun buildCard(r: Reminder): View {
        val now = System.currentTimeMillis()
        val overdue = !r.done && r.repeat == "NONE" && r.time < now

        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = rounded(if (r.done) cCardDone else cCard, 22)
        card.setPadding(dp(16), dp(14), dp(14), dp(14))
        val lp = LinearLayout.LayoutParams(match, wrap)
        lp.bottomMargin = dp(10)
        card.layoutParams = lp
        card.setOnClickListener { showItemMenu(r) }

        val tint = if (r.done) Color.parseColor("#332ED47A")
        else if (overdue) Color.parseColor("#33FFB020")
        else Color.parseColor("#337C5CFF")
        val icon = if (r.done) "✅" else if (overdue) "🔔" else if (r.repeat != "NONE") "🔁" else "⏰"
        val badge = TextView(this)
        badge.text = icon
        badge.textSize = 20f
        badge.gravity = Gravity.CENTER
        badge.background = oval(tint)
        badge.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        card.addView(badge)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, dp(8), 0)
        val title = text(r.text, 16f, if (r.done) cSub else Color.WHITE, true)
        if (r.done) title.paintFlags = title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        col.addView(title)
        val whenText = text(formatWhen(r.time, r.repeat), 13f, if (overdue) cAmber else cSub)
        whenText.setPadding(0, dp(3), 0, 0)
        col.addView(whenText)
        card.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))

        if (!r.done) {
            val check = circle("✓", 40, 18f, oval(Color.parseColor("#332ED47A"))) {
                markDone(r)
                refresh()
            }
            check.setTextColor(cGreen)
            card.addView(check)
        }
        return card
    }

    private fun dayLabel(time: Long): String {
        val a = Calendar.getInstance()
        val b = Calendar.getInstance()
        b.timeInMillis = time
        fun same(x: Calendar, y: Calendar): Boolean =
            x.get(Calendar.YEAR) == y.get(Calendar.YEAR) &&
                x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
        if (same(a, b)) return "Bugün"
        a.add(Calendar.DAY_OF_YEAR, 1)
        if (same(a, b)) return "Yarın"
        return SimpleDateFormat("d MMMM EEEE", tr).format(Date(time))
    }

    private fun formatWhen(time: Long, repeat: String): String {
        val hm = SimpleDateFormat("HH:mm", tr).format(Date(time))
        val s = dayLabel(time) + ", " + hm
        return when (repeat) {
            "DAILY" -> "$s  ·  her gün"
            "WEEKLY" -> "$s  ·  her hafta"
            else -> s
        }
    }

    // ---------- eylemler ----------
    private fun markDone(r: Reminder) {
        Scheduler.cancelNag(this, r.id)
        Notifier.cancel(this, r.id)
        r.nagCount = 0
        if (r.repeat == "NONE") r.done = true
        Store.update(this, r)
    }

    private fun showItemMenu(r: Reminder) {
        val opts = if (r.done) arrayOf("Sil") else arrayOf("Yaptım", "Sil")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(r.text)
            .setItems(opts) { _, which ->
                if (opts[which] == "Sil") {
                    Scheduler.cancel(this, r.id)
                    Notifier.cancel(this, r.id)
                    Store.remove(this, r.id)
                } else {
                    markDone(r)
                }
                refresh()
            }
            .show()
    }

    // 10 saniye sonra gerçek alarm yolunu dener (ekranı kilitleyip bekleyebilirsin)
    private fun scheduleTest() {
        val r = Store.add(this, "Deneme bildirimi 🎉", System.currentTimeMillis() + 10_000L, "NONE", false)
        Scheduler.schedule(this, r)
        refresh()
        Toast.makeText(this, "10 saniye sonra çalacak. İstersen ekranı kilitle.", Toast.LENGTH_LONG).show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Bildirim gelmiyorsa")
            .setMessage(
                "1) Bildirim iznini aç.\n\n" +
                    "2) Pil ayarlarında bu uygulamayı “Kısıtlama yok / Optimize etme” yap.\n\n" +
                    "3) Xiaomi, Oppo, Vivo, Huawei gibi telefonlarda uygulamaya “Otomatik başlatma” izni ver ve uygulamayı son kullanılanlarda kilitle.\n\n" +
                    "Sonra sağ üstteki “Test” düğmesiyle dene. En altta yazan “Son alarm” satırı neyin ters gittiğini gösterir."
            )
            .setPositiveButton("Bildirim ayarları") { _, _ -> openNotificationSettings() }
            .setNeutralButton("Pil ayarları") { _, _ -> openBatterySettings() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun openNotificationSettings() {
        try {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(i)
        } catch (e: Exception) {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            startActivity(i)
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            startActivity(i)
        }
    }

    // ---------- sesli giriş ----------
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
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) handleText(spoken)
        }
    }

    private fun handleText(raw: String) {
        val p = TrParser.parse(raw)
        val whenMs = p.time
        if (whenMs == null) {
            input.setText(raw)
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Zamanı anlayamadım")
                .setMessage("“$raw”\n\nİçinde bir zaman bulamadım. Örnek: “yarın sabah 9'da …”, “20 dakika sonra …”, “her salı akşam 8'de …”.\n\nMetni alttaki kutuya aktardım; düzeltip tekrar gönderebilirsin.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }

        val box = CheckBox(this)
        box.text = "Yapana kadar tekrar tekrar hatırlat"
        box.setTextColor(Color.WHITE)
        box.isChecked = true

        val msg = text(p.text + "\n\n" + formatWhen(whenMs, p.repeat), 18f, Color.WHITE)
        msg.setPadding(0, 0, 0, dp(14))

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(24), dp(16), dp(24), 0)
        layout.addView(msg)
        layout.addView(box)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
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
}
