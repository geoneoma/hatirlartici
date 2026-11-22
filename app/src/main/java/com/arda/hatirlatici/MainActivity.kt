package com.arda.hatirlatici

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val reqSpeech = 100
    private val reqLocation = 2
    private val reqBackground = 3
    private val tr: Locale = Locale.forLanguageTag("tr-TR")
    private val dark = android.R.style.Theme_Material_Dialog_Alert

    private lateinit var content: LinearLayout
    private lateinit var input: EditText
    private var tab = 0 // 0 Aktif, 1 Sürekli, 2 Geri sayım, 3 Tamamlanan
    private val handler = Handler(Looper.getMainLooper())
    private val tickers = ArrayList<Pair<TextView, Long>>()
    private var recoverTried = false
    private val tick: Runnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            var late = false
            for (pr in tickers) {
                val rem = pr.second - now
                pr.first.text = formatRemaining(rem)
                if (rem <= -8000L) late = true
            }
            if (late && !recoverTried) {
                // geri sayım bitti ama alarm hâlâ çalmadıysa uygulama kendisi haber versin
                recoverTried = true
                recoverMissed()
                refresh()
                return
            }
            handler.postDelayed(this, 1000L)
        }
    }
    private var pendingAfterPerm: (() -> Unit)? = null

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
        t.setPadding(dp(13), dp(9), dp(13), dp(9))
        t.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(wrap, wrap)
        lp.marginStart = dp(6)
        t.layoutParams = lp
        return t
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
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
        recoverTried = false
        recoverMissed()
        Pinned.update(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
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
        tickers.clear()
        val all = Store.all(this)
        val oneTime = all.filter { !it.done && it.repeat == "NONE" }
        val recurring = all.filter {
            !it.done && (it.repeat == "DAILY" || it.repeat == "WEEKLY" || it.repeat == "SPACED")
        }
        val countdowns = all.filter { !it.done && it.repeat == "COUNTDOWN" }
        val done = all.filter { it.done }

        content.addView(buildHeader(oneTime.size + recurring.size))
        if (!notificationsOn()) content.addView(buildWarning())
        content.addView(buildTabs(oneTime.size, recurring.size, countdowns.size, done.size))

        if (tab == 0) renderActive(oneTime)
        else if (tab == 1) renderRecurring(recurring)
        else if (tab == 2) renderCountdowns(countdowns)
        else renderDone(done)

        addDiag()

        handler.removeCallbacks(tick)
        if (tickers.isNotEmpty()) handler.post(tick)
    }

    private fun renderActive(list: List<Reminder>) {
        if (list.isEmpty()) {
            content.addView(buildEmpty("🌤️", "Aktif hatırlatma yok", "Mikrofona dokun ve söyle:\n“yarın sabah 9'da ilacı al”\n“20 dakika sonra çamaşırı çıkar”\n“eve girince çöpü çıkar”"))
            return
        }
        val now = System.currentTimeMillis()
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

        val timed = list.filter { !it.isLocation() }.sortedBy { it.time }
        val located = list.filter { it.isLocation() }
        addSection("Çaldı · onay bekliyor", timed.filter { it.time < now })
        val upcoming = timed.filter { it.time >= now }
        addSection("Bugün", upcoming.filter { it.time < startTomorrow })
        addSection("Yarın", upcoming.filter { it.time >= startTomorrow && it.time < startAfter })
        addSection("Daha sonra", upcoming.filter { it.time >= startAfter })
        addSection("Konuma bağlı", located)
    }

    private fun renderRecurring(list: List<Reminder>) {
        if (list.isEmpty()) {
            content.addView(buildEmpty("🔁", "Sürekli hatırlatma yok", "Şöyle söyleyebilirsin:\n“her sabah 8'de su iç”\n“her salı akşam 8'de piyano çalış”\n“aralıklı tekrar ingilizce kelimeler”"))
            return
        }
        val sorted = list.sortedBy { it.time }
        addSection("Her gün", sorted.filter { it.repeat == "DAILY" })
        addSection("Her hafta", sorted.filter { it.repeat == "WEEKLY" })
        addSection("Aralıklı tekrar · 1, 3, 7, 21. gün", sorted.filter { it.repeat == "SPACED" })
    }

    private fun renderCountdowns(list: List<Reminder>) {
        if (list.isEmpty()) {
            content.addView(buildEmpty("⏳", "Geri sayım yok", "Şöyle söyleyebilirsin:\n“geri sayım sınav 15 ocak”\n“geri sayım doğum günü 25 aralık”\n“25 dakika geri sayım ders çalış”"))
            return
        }
        val h = text("YAKLAŞAN".uppercase(tr), 12f, cSub, true)
        h.letterSpacing = 0.08f
        h.setPadding(dp(4), dp(22), 0, dp(10))
        content.addView(h)
        for (r in list.sortedBy { it.time }) content.addView(buildCountdownCard(r))
    }

    private fun buildCountdownCard(r: Reminder): View {
        val now = System.currentTimeMillis()
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#5B3FD9"), Color.parseColor("#0A84C8")))
        g.cornerRadius = dp(26).toFloat()
        card.background = g
        card.setPadding(dp(22), dp(20), dp(22), dp(20))
        val lp = LinearLayout.LayoutParams(match, wrap)
        lp.bottomMargin = dp(12)
        card.layoutParams = lp
        card.setOnClickListener { showItemMenu(r) }

        card.addView(text("⏳  " + r.text, 18f, Color.WHITE, true))

        val remain = text(formatRemaining(r.time - now), 32f, Color.WHITE, true)
        remain.setPadding(0, dp(10), 0, dp(4))
        card.addView(remain)
        tickers.add(Pair(remain, r.time))

        val dateStr = SimpleDateFormat("d MMMM EEEE, HH:mm", tr).format(Date(r.time))
        card.addView(text(dateStr, 13f, Color.parseColor("#D6D9FF")))

        // ilerleme çubuğu
        val total = r.time - r.startTime
        var p = if (r.startTime > 0L && total > 0L) (now - r.startTime).toFloat() / total.toFloat() else 0f
        if (p < 0f) p = 0f
        if (p > 1f) p = 1f
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = rounded(Color.parseColor("#33FFFFFF"), 4)
        val barLp = LinearLayout.LayoutParams(match, dp(6))
        barLp.topMargin = dp(14)
        bar.layoutParams = barLp
        val fill = View(this)
        fill.background = rounded(Color.WHITE, 4)
        bar.addView(fill, LinearLayout.LayoutParams(0, match, p))
        bar.addView(View(this), LinearLayout.LayoutParams(0, match, 1f - p))
        card.addView(bar)
        return card
    }

    private fun formatRemaining(ms: Long): String {
        if (ms <= 0L) return "Süre doldu 🎉"
        val totalSec = ms / 1000L
        val d = totalSec / 86400L
        val h = (totalSec % 86400L) / 3600L
        val m = (totalSec % 3600L) / 60L
        val sec = totalSec % 60L
        val hms = String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
        return if (d > 0L) "$d gün $hms" else hms
    }

    private fun renderDone(list: List<Reminder>) {
        if (list.isEmpty()) {
            content.addView(buildEmpty("✅", "Henüz tamamlanan yok", "Yaptığın hatırlatmalar burada birikir."))
            return
        }
        val clear = text("🗑  Tamamlananları temizle", 13f, cSub, true)
        clear.setPadding(dp(4), dp(20), 0, dp(2))
        clear.setOnClickListener {
            AlertDialog.Builder(this, dark)
                .setTitle("Temizlensin mi?")
                .setMessage(list.size.toString() + " tamamlanan hatırlatma silinecek.")
                .setPositiveButton("Temizle") { _, _ ->
                    for (r in list) {
                        Scheduler.cancel(this, r.id)
                        Store.remove(this, r.id)
                    }
                    refresh()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        content.addView(clear)
        addSection("Tamamlanan", list.sortedByDescending { it.time })
    }

    private fun buildHeader(pending: Int): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text("Hatırlatıcı", 28f, Color.WHITE, true))
        val dateStr = SimpleDateFormat("d MMMM EEEE", tr).format(Date())
        val state = if (pending == 0) "her şey tamam" else pending.toString() + " bekleyen"
        col.addView(text("$dateStr  ·  $state", 13f, cSub))
        row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))

        row.addView(pill("📍") { showPlaces() })
        row.addView(pill("⚙") { showSettings() })
        row.addView(pill("?") { showHelp() })
        return row
    }

    private fun buildTabs(a: Int, b: Int, c: Int, d: Int): View {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val hsLp = LinearLayout.LayoutParams(match, wrap)
        hsLp.topMargin = dp(20)
        hs.layoutParams = hsLp

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val labels = arrayOf("Aktif", "Sürekli", "Geri sayım", "Tamamlanan")
        val counts = intArrayOf(a, b, c, d)
        for (i in 0..3) {
            val t = TextView(this)
            t.text = labels[i] + (if (counts[i] > 0) "  " + counts[i] else "")
            t.textSize = 14f
            t.gravity = Gravity.CENTER
            t.setPadding(dp(18), dp(11), dp(18), dp(11))
            if (i == tab) {
                val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cA1, cA2))
                g.cornerRadius = dp(22).toFloat()
                t.background = g
                t.setTextColor(Color.WHITE)
                t.setTypeface(null, Typeface.BOLD)
            } else {
                t.background = rounded(cBar, 22)
                t.setTextColor(cSub)
            }
            t.setOnClickListener {
                tab = i
                refresh()
            }
            val lp = LinearLayout.LayoutParams(wrap, wrap)
            lp.marginEnd = dp(8)
            row.addView(t, lp)
        }
        hs.addView(row)
        return hs
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

    private fun buildEmpty(emoji: String, title: String, sub: String): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(0, dp(70), 0, 0)
        val e = text(emoji, 56f, Color.WHITE)
        e.gravity = Gravity.CENTER
        c.addView(e)
        val t = text(title, 20f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(12), 0, dp(6))
        c.addView(t)
        val s = text(sub, 14f, cSub)
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

    private fun describe(r: Reminder): String {
        if (r.repeat == "COUNTDOWN") return "geri sayım  ·  " + formatWhen(r.time, "NONE")
        if (r.isLocation()) {
            return "📍 " + r.placeName + (if (r.trigger == "EXIT") " · ayrılınca" else " · varınca")
        }
        if (r.repeat == "SPACED") {
            if (r.done) return "aralıklı tekrar tamamlandı"
            return formatWhen(r.time, "NONE") + "  ·  " + (r.stage + 1) + "/" + Spaced.DAYS.size + ". tekrar"
        }
        return formatWhen(r.time, r.repeat)
    }

    private fun buildCard(r: Reminder): View {
        val now = System.currentTimeMillis()
        val overdue = !r.done && r.repeat == "NONE" && !r.isLocation() && r.time < now

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
        val icon = if (r.done) "✅"
        else if (overdue) "🔔"
        else if (r.isLocation()) "📍"
        else if (r.repeat == "SPACED") "🧠"
        else if (r.repeat != "NONE") "🔁"
        else "⏰"
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
        val sub = text(describe(r), 13f, if (overdue) cAmber else cSub)
        sub.setPadding(0, dp(3), 0, 0)
        col.addView(sub)
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
        if (r.finishesOnDone()) r.done = true
        Store.update(this, r)
        if (r.isLocation()) Geo.remove(this, r.id)
    }

    private fun deleteReminder(r: Reminder) {
        Scheduler.cancel(this, r.id)
        Notifier.cancel(this, r.id)
        if (r.isLocation()) Geo.remove(this, r.id)
        Store.remove(this, r.id)
    }

    private fun showItemMenu(r: Reminder) {
        val finishLabel = if (r.repeat == "COUNTDOWN") "Bitir" else "Yaptım"
        val opts = if (r.done) arrayOf("Sil") else arrayOf(finishLabel, "Sil")
        AlertDialog.Builder(this, dark)
            .setTitle(r.text)
            .setItems(opts) { _, which ->
                if (opts[which] == "Sil") deleteReminder(r) else markDone(r)
                refresh()
            }
            .show()
    }

    private fun showSettings() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(24), dp(12), dp(24), 0)

        val pin = Switch(this)
        pin.text = "Kilit ekranında kalıcı bildirim"
        pin.setTextColor(Color.WHITE)
        pin.isChecked = AppSettings.pinned(this)
        pin.setOnCheckedChangeListener { _, on ->
            AppSettings.setPinned(this, on)
            Pinned.update(this)
        }
        layout.addView(pin, LinearLayout.LayoutParams(match, wrap))
        val pinHint = text("Geri sayım varsa saniye saniye akar, yoksa sıradaki hatırlatmayı gösterir.", 12f, cSub)
        pinHint.setPadding(0, dp(2), 0, dp(16))
        layout.addView(pinHint)

        val loud = Switch(this)
        loud.text = "Sessiz ve titreşim modunda da çınlasın"
        loud.setTextColor(Color.WHITE)
        loud.isChecked = AppSettings.loud(this)
        loud.setOnCheckedChangeListener { _, on -> AppSettings.setLoud(this, on) }
        layout.addView(loud, LinearLayout.LayoutParams(match, wrap))
        val loudHint = text("Kapalıyken çınlama sadece telefon sesliyken çalar, sessizdeyken yalnızca titrer.", 12f, cSub)
        loudHint.setPadding(0, dp(2), 0, dp(8))
        layout.addView(loudHint)

        val test = text("🔔  Test bildirimi gönder (10 sn sonra)", 14f, cA2, true)
        test.setPadding(0, dp(14), 0, dp(10))
        test.setOnClickListener { scheduleTest() }
        layout.addView(test)

        AlertDialog.Builder(this, dark)
            .setTitle("Ayarlar")
            .setView(layout)
            .setPositiveButton("Tamam", null)
            .show()
    }

    /** Alarm sistem tarafından çalıştırılamadıysa (telefon uykuya almış olabilir) uygulama açılınca ya da açıkken haber verir. */
    private fun recoverMissed() {
        val now = System.currentTimeMillis()
        for (r in Store.all(this)) {
            if (r.done || r.isLocation() || r.firedAt > 0L) continue
            if (r.repeat != "NONE" && r.repeat != "COUNTDOWN") continue
            if (r.time > now - 8000L) continue
            Notifier.show(this, r)
            r.firedAt = now
            if (!r.persistent) r.done = true
            Store.update(this, r)
            if (r.persistent) Scheduler.nag(this, r, 5)
            Diag.record(this, "alarm çalmamıştı, uygulama haber verdi (Samsung arka planı kısıtlıyor olabilir)")
        }
    }

    // 10 saniye sonra gerçek alarm yolunu dener (ekranı kilitleyip bekleyebilirsin)
    private fun scheduleTest() {
        val r = Store.add(this, "Deneme bildirimi 🎉", System.currentTimeMillis() + 10_000L, "NONE", false)
        Scheduler.schedule(this, r)
        tab = 0
        refresh()
        toast("10 saniye sonra çalacak. İstersen ekranı kilitle.")
    }

    private fun showHelp() {
        AlertDialog.Builder(this, dark)
            .setTitle("Yardım")
            .setMessage(
                "Söyleyebileceklerin:\n" +
                    "• yarın sabah 9'da ilacı al\n" +
                    "• 20 dakika sonra çamaşırı çıkar\n" +
                    "• her salı akşam 8'de piyano çalış\n" +
                    "• eve girince çöpü çıkar  (önce 📍 ile yer kaydet)\n" +
                    "• aralıklı tekrar ingilizce kelimeler  (1, 3, 7 ve 21. günde hatırlatır)\n" +
                    "• geri sayım sınav 15 ocak  /  25 dakika geri sayım  (ana ekranda widget olarak da görünür)\n\n" +
                    "Bildirim gelmiyorsa:\n" +
                    "1) Bildirim iznini aç.\n" +
                    "2) Pil ayarlarında uygulamayı “Sınırsız” yap.\n" +
                    "3) ⚙ Ayarlar'daki test düğmesiyle dene; en alttaki “Son alarm” satırı neyin ters gittiğini gösterir."
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
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // ---------- konum / yerlerim ----------
    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ensureLocation(thenRun: () -> Unit) {
        if (hasLocationPermission()) {
            thenRun()
            return
        }
        pendingAfterPerm = thenRun
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            reqLocation
        )
    }

    private fun ensureBackground() {
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this, dark)
                .setTitle("Konum izni: Her zaman")
                .setMessage("Konum hatırlatmalarının uygulama kapalıyken de çalışması için açılacak ayar ekranında Konum → “Her zaman izin ver” seçeneğini seç.")
                .setPositiveButton("İzin ver") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), reqBackground)
                }
                .setNegativeButton("Sonra", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqLocation) {
            val ok = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            val next = pendingAfterPerm
            pendingAfterPerm = null
            if (ok && next != null) next() else toast("Konum hatırlatmaları için konum izni gerekli.")
        }
    }

    private fun showPlaces() {
        val list = Places.all(this)
        val labels = ArrayList<String>()
        for (p in list) labels.add("📍  " + p.name + "   (silmek için dokun)")
        labels.add("＋  Şu anki konumu yer olarak ekle")
        AlertDialog.Builder(this, dark)
            .setTitle("Yerlerim")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == list.size) addPlaceFlow() else confirmDeletePlace(list[which])
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun confirmDeletePlace(p: Place) {
        AlertDialog.Builder(this, dark)
            .setTitle(p.name + " silinsin mi?")
            .setPositiveButton("Sil") { _, _ -> Places.remove(this, p.name) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun addPlaceFlow() {
        ensureLocation {
            val et = EditText(this)
            et.hint = "Örn: Ev, İş, Market"
            et.setHintTextColor(cSub)
            et.setTextColor(Color.WHITE)
            et.isSingleLine = true
            val box = LinearLayout(this)
            box.setPadding(dp(24), dp(12), dp(24), 0)
            box.addView(et, LinearLayout.LayoutParams(match, wrap))
            AlertDialog.Builder(this, dark)
                .setTitle("Şu an bulunduğun yere ad ver")
                .setView(box)
                .setPositiveButton("Kaydet") { _, _ ->
                    val name = et.text.toString().trim()
                    if (name.isEmpty()) toast("Bir ad yazmalısın.") else saveCurrentLocationAs(name)
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun saveCurrentLocationAs(name: String) {
        toast("Konum alınıyor…")
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    Places.add(this, Place(name, loc.latitude, loc.longitude))
                    toast("“$name” kaydedildi. Artık “" + name.lowercase(tr) + "e girince …” diyebilirsin.")
                    ensureBackground()
                } else {
                    toast("Konum alınamadı. Telefonun konumunu açıp açık havada dene.")
                }
            }
            .addOnFailureListener { toast("Konum alınamadı: " + (it.message ?: "")) }
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
            toast("Bu telefonda sesli giriş bulunamadı. Google uygulamasını kontrol et ya da yazarak ekle.")
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
        val p = TrParser.parse(raw, Places.names(this))

        // konuma bağlı hatırlatma
        if (p.place.isNotEmpty()) {
            val pl = Places.find(this, p.place)
            if (pl != null) {
                confirmAndSaveLocation(p, pl)
                return
            }
        }

        val whenMs = p.time
        if (whenMs == null) {
            input.setText(raw)
            val low = raw.lowercase(tr)
            val wantsPlace = low.contains("girince") || low.contains("gelince") ||
                low.contains("çıkınca") || low.contains("varınca") || low.contains("ayrılınca")
            val msg = if (wantsPlace) {
                "“$raw”\n\nBu bir konum hatırlatması gibi ama o yeri tanımıyorum. Üstteki 📍 düğmesinden önce yeri kaydet (örneğin “Ev”), sonra “eve girince …” de."
            } else {
                "“$raw”\n\nİçinde bir zaman bulamadım. Örnek: “yarın sabah 9'da …”, “20 dakika sonra …”, “her salı akşam 8'de …”.\n\nMetni alttaki kutuya aktardım; düzeltip tekrar gönderebilirsin."
            }
            AlertDialog.Builder(this, dark)
                .setTitle(if (wantsPlace) "Yer kayıtlı değil" else "Zamanı anlayamadım")
                .setMessage(msg)
                .setPositiveButton("Tamam", null)
                .show()
            return
        }

        if (p.countdown) {
            confirmCountdown(p, whenMs)
            return
        }

        val box = CheckBox(this)
        box.text = "Yapana kadar tekrar tekrar hatırlat"
        box.setTextColor(Color.WHITE)
        box.isChecked = true

        var body = p.text + "\n\n" + formatWhen(whenMs, p.repeat)
        if (p.repeat == "SPACED") {
            body = p.text + "\n\n🧠 Aralıklı tekrar\n" + formatWhen(whenMs, "NONE") +
                "\nsonra 3., 7. ve 21. günlerde de hatırlatırım"
        }
        val msg = text(body, 18f, Color.WHITE)
        msg.setPadding(0, 0, 0, dp(14))

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(24), dp(16), dp(24), 0)
        layout.addView(msg)
        layout.addView(box)

        AlertDialog.Builder(this, dark)
            .setTitle("Böyle kaydedeyim mi?")
            .setView(layout)
            .setPositiveButton("Kaydet") { _, _ ->
                val r = Store.add(this, p.text, whenMs, p.repeat, box.isChecked)
                Scheduler.schedule(this, r)
                input.setText("")
                tab = if (p.repeat == "NONE") 0 else 1
                refresh()
                toast("Kaydedildi")
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun confirmCountdown(p: Parsed, whenMs: Long) {
        val msg = text(
            "⏳ " + p.text + "\n\n" + formatWhen(whenMs, "NONE") + "\n" +
                formatRemaining(whenMs - System.currentTimeMillis()) + " kaldı",
            18f, Color.WHITE
        )
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(24), dp(16), dp(24), dp(4))
        layout.addView(msg)

        AlertDialog.Builder(this, dark)
            .setTitle("Geri sayım başlasın mı?")
            .setView(layout)
            .setPositiveButton("Başlat") { _, _ ->
                val r = Store.add(this, p.text, whenMs, "COUNTDOWN", false)
                Scheduler.schedule(this, r)
                input.setText("")
                tab = 2
                refresh()
                toast("Geri sayım başladı")
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun confirmAndSaveLocation(p: Parsed, pl: Place) {
        val box = CheckBox(this)
        box.text = "Yapana kadar tekrar tekrar hatırlat"
        box.setTextColor(Color.WHITE)
        box.isChecked = true

        val where = if (p.trigger == "EXIT") pl.name + " konumundan ayrılınca" else pl.name + " konumuna varınca"
        val msg = text(p.text + "\n\n📍 " + where, 18f, Color.WHITE)
        msg.setPadding(0, 0, 0, dp(14))

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(24), dp(16), dp(24), 0)
        layout.addView(msg)
        layout.addView(box)

        AlertDialog.Builder(this, dark)
            .setTitle("Böyle kaydedeyim mi?")
            .setView(layout)
            .setPositiveButton("Kaydet") { _, _ ->
                ensureLocation {
                    val r = Store.add(
                        this, p.text, System.currentTimeMillis(), "NONE", box.isChecked,
                        pl.name, pl.lat, pl.lng, p.trigger
                    )
                    Geo.register(this, r)
                    ensureBackground()
                    input.setText("")
                    tab = 0
                    refresh()
                    toast("Kaydedildi")
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }
}
