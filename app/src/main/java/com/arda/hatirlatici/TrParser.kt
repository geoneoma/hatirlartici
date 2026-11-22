package com.arda.hatirlatici

import java.util.Calendar
import java.util.Locale

data class Parsed(
    val text: String,
    val time: Long?,
    val repeat: String,
    val place: String = "",
    val trigger: String = "ENTER",
    val countdown: Boolean = false
)

/**
 * Türkçe konuşma/yazıdan zaman ve hatırlatma metnini ayıklar.
 * Örnekler:
 *  "yarın sabah 9'da ilacımı al"        -> yarın 09:00, "İlacımı al"
 *  "20 dakika sonra çamaşırı çıkar"      -> şimdi + 20 dk
 *  "cuma günü saat 14:30'da toplantı"    -> bu/gelecek cuma 14:30
 *  "her salı akşam 8'de piyano çalış"    -> haftalık, salı 20:00
 *  "15 aralık sabah faturayı öde"        -> 15 Aralık 08:00
 */
object TrParser {
    private val tr: Locale = Locale.forLanguageTag("tr-TR")

    private val weekdays = listOf(
        "pazartesi" to Calendar.MONDAY,
        "salı" to Calendar.TUESDAY,
        "çarşamba" to Calendar.WEDNESDAY,
        "perşembe" to Calendar.THURSDAY,
        "cumartesi" to Calendar.SATURDAY,
        "cuma" to Calendar.FRIDAY,
        "pazar" to Calendar.SUNDAY
    )
    private val months = listOf(
        "ocak", "şubat", "mart", "nisan", "mayıs", "haziran",
        "temmuz", "ağustos", "eylül", "ekim", "kasım", "aralık"
    )
    private val units = mapOf(
        "bir" to 1, "iki" to 2, "üç" to 3, "dört" to 4, "beş" to 5,
        "altı" to 6, "yedi" to 7, "sekiz" to 8, "dokuz" to 9
    )
    private val tens = mapOf("on" to 10, "yirmi" to 20, "otuz" to 30, "kırk" to 40, "elli" to 50)
    private val suffixes = setOf("", "da", "de", "ta", "te", "a", "e", "ya", "ye")

    private val colonRe = Regex("^(\\d{1,2}):(\\d{2})(?:'?[a-zçğıöşü]{0,3})?$")
    private val suffixRe = Regex("^(\\d{1,2})'?(da|de|ta|te|ya|ye|a|e)$")

    private fun weekdayOf(t: String): Int? {
        for ((name, v) in weekdays) {
            if (t == name) return v
            // "pazar" kelimesi "pazara git" gibi cümlelerle karışmasın diye sadece tam eşleşir
            if (name != "pazar" && t.startsWith(name) && t.length - name.length <= 3) return v
        }
        return null
    }

    private fun isAfter(t: String): Boolean = t.startsWith("sonra") || t.startsWith("içinde")

    private fun readNumber(t: List<String>, i: Int): Pair<Int, Int>? {
        val w = t[i]
        val d = w.toIntOrNull()
        if (d != null) return Pair(d, 1)
        val ten = tens[w]
        if (ten != null) {
            val u = if (i + 1 < t.size) units[t[i + 1]] else null
            return if (u != null) Pair(ten + u, 2) else Pair(ten, 1)
        }
        val u1 = units[w]
        if (u1 != null) return Pair(u1, 1)
        return null
    }

    private fun wordHour(t: String): Int? {
        for ((w, v) in units + tens) {
            if (t.startsWith(w) && t.substring(w.length).trimStart('\'') in suffixes) return v
        }
        return null
    }

    fun parse(input: String, places: List<String> = emptyList(), now: Long = System.currentTimeMillis()): Parsed {
        val t = input.replace('’', '\'').replace('‘', '\'').lowercase(tr)
            .split(Regex("\\s+"))
            .map { it.trim(',', ';', '!', '?', '.', '"', '“', '”') }
            .filter { it.isNotEmpty() }
        val n = t.size
        val used = BooleanArray(n)
        // "geri sayım" geçiyor mu? ("25 dakika geri sayım", "geri sayım sınav 15 ocak")
        val cd = (0 until n).any { k -> t[k] == "geri" && k + 1 < n && t[k + 1].startsWith("sayım") }

        var relMs: Long? = null
        var dayOffset: Int? = null
        var weekday: Int? = null
        var nextWeek = false
        var dm: Pair<Int, Int>? = null
        var hour: Int? = null
        var minute = 0
        var daypart: String? = null
        var repeat = "NONE"
        var place = ""
        var trigger = "ENTER"

        var i = 0
        while (i < n) {
            val w = t[i]
            val nx = if (i + 1 < n) t[i + 1] else ""
            val nx2 = if (i + 2 < n) t[i + 2] else ""
            val prevSaat = i > 0 && t[i - 1] == "saat"

            if (w == "geri" && nx.startsWith("sayım")) {
                used[i] = true; used[i + 1] = true; i += 2; continue
            }

            // --- tekrar: "her gün", "her salı", "her sabah" ---
            if (w == "hergün") {
                repeat = "DAILY"; used[i] = true; i++; continue
            }
            if (w == "her" && nx.isNotEmpty()) {
                val wd = weekdayOf(nx)
                var handled = true
                if (wd != null) {
                    repeat = "WEEKLY"; weekday = wd
                } else if (nx.startsWith("gün")) {
                    repeat = "DAILY"
                } else if (nx.startsWith("hafta")) {
                    repeat = "WEEKLY"
                } else if (nx == "sabah" || nx == "akşam" || nx == "gece" || nx == "öğlen") {
                    repeat = "DAILY"; daypart = nx
                } else {
                    handled = false
                }
                if (handled) {
                    used[i] = true; used[i + 1] = true; i += 2; continue
                }
            }

            // --- aralıklı tekrar: "aralıklı tekrar ingilizce kelimeler" ---
            if (w.startsWith("aralıklı")) {
                repeat = "SPACED"
                used[i] = true
                i++
                if (i < n && t[i].startsWith("tekrar")) {
                    used[i] = true
                    i++
                }
                continue
            }

            // --- konum: "eve girince", "evden çıkınca" (kayıtlı yerlerle) ---
            if (places.isNotEmpty() && nx.isNotEmpty()) {
                val enter = nx.startsWith("girin") || nx.startsWith("gelin") || nx.startsWith("varın") ||
                    nx.startsWith("ulaşın") || nx.startsWith("yaklaşın") || nx.startsWith("gidin") ||
                    nx.startsWith("girdiğ") || nx.startsWith("geldiğ")
                val exit = nx.startsWith("çıkın") || nx.startsWith("ayrılın") || nx.startsWith("çıktığ")
                if (enter || exit) {
                    val hit = places.firstOrNull { pl ->
                        val q = pl.trim().lowercase(tr).substringBefore(' ')
                        q.isNotEmpty() && w.startsWith(q) && w.length - q.length <= 3
                    }
                    if (hit != null) {
                        place = hit
                        trigger = if (exit) "EXIT" else "ENTER"
                        used[i] = true
                        used[i + 1] = true
                        i += 2
                        continue
                    }
                }
            }

            // --- "yarım saat sonra" ---
            if (w == "yarım" && nx.startsWith("saat") && isAfter(nx2)) {
                relMs = (relMs ?: 0L) + 30 * 60_000L
                used.fill(true, i, i + 3); i += 3; continue
            }

            // --- sayı ile başlayanlar: "20 dakika sonra", "15 aralık", "9 buçukta", "saat 9" ---
            val num = readNumber(t, i)
            if (num != null) {
                val j = i + num.second
                if (j < n) {
                    val u = t[j]
                    val unit = if (u.startsWith("dakika") || u == "dk") 60_000L
                    else if (u.startsWith("saat")) 3_600_000L
                    else if (u.startsWith("gün")) 86_400_000L
                    else if (u.startsWith("hafta")) 604_800_000L
                    else 0L
                    val hasAfter = j + 1 < n && isAfter(t[j + 1])
                    if (unit > 0L && (hasAfter || cd)) {
                        val end = if (hasAfter) j + 2 else j + 1
                        relMs = (relMs ?: 0L) + num.first * unit
                        used.fill(true, i, end); i = end; continue
                    }
                }
                if (j < n && num.second == 1 && num.first in 1..31) {
                    val mi = months.indexOfFirst { t[j].startsWith(it) }
                    if (mi >= 0) {
                        dm = Pair(num.first, mi)
                        used.fill(true, i, j + 1); i = j + 1; continue
                    }
                }
                if (j < n && t[j].startsWith("buçuk") && num.first in 1..23) {
                    hour = num.first; minute = 30
                    used.fill(true, i, j + 1); i = j + 1; continue
                }
                if (prevSaat && num.first in 0..23) {
                    hour = num.first
                    used[i - 1] = true
                    used.fill(true, i, j); i = j; continue
                }
            }

            // --- saat biçimleri: "14:30", "9'da", "saat dokuzda" ---
            val cm = colonRe.find(w)
            if (cm != null) {
                val h = cm.groupValues[1].toInt()
                val m = cm.groupValues[2].toInt()
                if (h in 0..23 && m in 0..59) {
                    hour = h; minute = m
                    used[i] = true; if (prevSaat) used[i - 1] = true
                    i++; continue
                }
            }
            val sm = suffixRe.find(w)
            if (sm != null) {
                val h = sm.groupValues[1].toInt()
                if (h in 0..23) {
                    hour = h
                    used[i] = true; if (prevSaat) used[i - 1] = true
                    i++; continue
                }
            }
            if (prevSaat) {
                val h = wordHour(w)
                if (h != null) {
                    hour = h
                    used[i] = true; used[i - 1] = true
                    i++; continue
                }
            }

            // --- gün sözcükleri ---
            if (w == "yarından" && nx.startsWith("sonra")) {
                dayOffset = 2; used[i] = true; used[i + 1] = true; i += 2; continue
            }
            if (w == "öbür" && nx.startsWith("gün")) {
                dayOffset = 2; used[i] = true; used[i + 1] = true; i += 2; continue
            }
            if (w.startsWith("yarın")) {
                dayOffset = 1; used[i] = true; i++; continue
            }
            if (w.startsWith("bugün")) {
                dayOffset = 0; used[i] = true; i++; continue
            }
            if (w == "bu" && (nx == "akşam" || nx == "sabah" || nx == "gece" || nx.startsWith("öğle"))) {
                if (dayOffset == null) dayOffset = 0
                used[i] = true; i++; continue
            }
            if (w.startsWith("haftaya")) {
                nextWeek = true; used[i] = true; i++; continue
            }
            val wd = weekdayOf(w)
            if (wd != null) {
                weekday = wd; used[i] = true; i++
                if (i < n && t[i].startsWith("gün")) { used[i] = true; i++ }
                continue
            }

            // --- günün bölümleri ---
            if (w.startsWith("öğleden")) {
                daypart = "öğleden"; used[i] = true; i++
                if (i < n && t[i].startsWith("sonra")) { used[i] = true; i++ }
                continue
            }
            val isPart = w.startsWith("sabah") || w.startsWith("öğle") ||
                w.startsWith("akşam") || w.startsWith("gece")
            if (isPart && !(w.startsWith("akşam") && nx.startsWith("yemeğ"))) {
                daypart = if (w.startsWith("sabah")) "sabah"
                else if (w.startsWith("öğle")) "öğlen"
                else if (w.startsWith("akşam")) "akşam"
                else "gece"
                used[i] = true; i++; continue
            }

            i++
        }

        // ---- zamanı hesapla ----
        val rel = relMs
        val off = dayOffset
        val wk = weekday
        val dmv = dm
        val part = daypart
        var h = hour
        var mi = minute
        val hasDate = off != null || wk != null || dmv != null

        var target: Long? = null
        if (rel != null) {
            target = now + rel
        } else {
            if (h == null && part != null) {
                h = when (part) {
                    "sabah" -> 8
                    "öğleden" -> 15
                    "öğlen" -> 12
                    "akşam" -> 19
                    else -> 22
                }
                mi = 0
            } else if (h != null) {
                if (part == "akşam" || part == "öğleden") {
                    if (h in 1..11) h += 12
                } else if (part == "gece") {
                    if (h in 6..11) h += 12 else if (h == 12) h = 0
                } else if (part == "öğlen" && h in 1..5) {
                    h += 12
                }
            }
            if (h == null && (hasDate || repeat != "NONE")) {
                h = if (cd) 0 else 9
                mi = 0
            }
            if (h != null) {
                val c = Calendar.getInstance()
                c.timeInMillis = now
                c.set(Calendar.HOUR_OF_DAY, h)
                c.set(Calendar.MINUTE, mi)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                if (repeat == "SPACED") {
                    c.add(Calendar.DAY_OF_YEAR, 1)
                } else if (dmv != null) {
                    c.set(Calendar.MONTH, dmv.second)
                    c.set(Calendar.DAY_OF_MONTH, dmv.first)
                    if (c.timeInMillis <= now) c.add(Calendar.YEAR, 1)
                } else if (wk != null) {
                    val diff = (wk - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
                    c.add(Calendar.DAY_OF_YEAR, diff)
                    if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 7)
                    if (nextWeek) c.add(Calendar.DAY_OF_YEAR, 7)
                } else if (off != null) {
                    c.add(Calendar.DAY_OF_YEAR, off)
                    if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
                } else {
                    if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
                }
                target = c.timeInMillis
            }
        }

        // ---- hatırlatma metni ----
        val skip = setOf("bana", "beni", "lütfen", "diye")
        val words = t.filterIndexed { idx, s ->
            !used[idx] && !s.startsWith("hatırlat") && s !in skip
        }
        var text = words.joinToString(" ").trim()
        val emptyName = if (cd) "Geri sayım" else "Hatırlatma"
        text = if (text.isEmpty()) emptyName else text.replaceFirstChar { it.titlecase(tr) }
        return Parsed(text, if (place.isNotEmpty()) null else target, repeat, place, trigger, cd)
    }
}
