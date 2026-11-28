package com.arda.hatirlatici

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

data class NoteItem(var id: Long, var text: String, var checked: Boolean)

data class Note(
    var id: Long,
    val type: String, // TEXT veya LIST
    var title: String,
    var body: String,
    val createdAt: Long,
    var updatedAt: Long,
    var items: MutableList<NoteItem> = mutableListOf()
) {
    fun isList(): Boolean = type == "LIST"

    fun doneCount(): Int = items.count { it.checked }

    /** q: küçük harfe çevrilmiş, kırpılmış arama metni. Boşsa her not eşleşir. */
    fun matches(q: String): Boolean {
        if (q.isEmpty()) return true
        val tr = Locale.forLanguageTag("tr-TR")
        return title.lowercase(tr).contains(q) ||
            body.lowercase(tr).contains(q) ||
            items.any { it.text.lowercase(tr).contains(q) }
    }
}

/** Notların yerel SQLite veritabanı (uygulama kapanınca kaybolmaz). */
class NotesDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "notlar.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "type TEXT NOT NULL, " +
                "title TEXT NOT NULL DEFAULT '', " +
                "body TEXT NOT NULL DEFAULT '', " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE note_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE, " +
                "text TEXT NOT NULL, " +
                "checked INTEGER NOT NULL DEFAULT 0, " +
                "position INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_items_note ON note_items(note_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Şimdilik tek sürüm var. Şema değişirse geçiş kodu buraya eklenecek.
    }

    /** Tüm notlar, en son değiştirilen en üstte; liste maddeleriyle birlikte. */
    @Synchronized
    fun allNotes(): MutableList<Note> {
        val db = readableDatabase
        val notes = LinkedHashMap<Long, Note>()
        db.rawQuery(
            "SELECT id, type, title, body, created_at, updated_at FROM notes ORDER BY updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val n = Note(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5))
                notes[n.id] = n
            }
        }
        db.rawQuery(
            "SELECT id, note_id, text, checked FROM note_items ORDER BY note_id, position",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val n = notes[c.getLong(1)]
                if (n != null) n.items.add(NoteItem(c.getLong(0), c.getString(2), c.getInt(3) == 1))
            }
        }
        return ArrayList(notes.values)
    }

    @Synchronized
    fun getNote(id: Long): Note? = allNotes().firstOrNull { it.id == id }

    /** Not yeniyse (id == 0) ekler, değilse günceller. Liste maddelerini baştan yazar. id'yi döndürür. */
    @Synchronized
    fun save(n: Note): Long {
        val db = writableDatabase
        n.updatedAt = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val cv = ContentValues()
            cv.put("type", n.type)
            cv.put("title", n.title)
            cv.put("body", n.body)
            cv.put("updated_at", n.updatedAt)
            if (n.id == 0L) {
                cv.put("created_at", n.createdAt)
                n.id = db.insert("notes", null, cv)
            } else {
                db.update("notes", cv, "id = ?", arrayOf(n.id.toString()))
            }
            db.delete("note_items", "note_id = ?", arrayOf(n.id.toString()))
            var pos = 0
            for (item in n.items) {
                val iv = ContentValues()
                iv.put("note_id", n.id)
                iv.put("text", item.text)
                iv.put("checked", if (item.checked) 1 else 0)
                iv.put("position", pos)
                pos++
                item.id = db.insert("note_items", null, iv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n.id
    }

    @Synchronized
    fun delete(id: Long) {
        val db = writableDatabase
        db.delete("note_items", "note_id = ?", arrayOf(id.toString()))
        db.delete("notes", "id = ?", arrayOf(id.toString()))
    }

    /** Başlık, metin ve liste maddelerinde arar (Türkçe harflerde büyük/küçük harf duyarsız). */
    fun search(query: String): List<Note> {
        val q = query.trim().lowercase(Locale.forLanguageTag("tr-TR"))
        return allNotes().filter { it.matches(q) }
    }

    companion object {
        @Volatile
        private var instance: NotesDb? = null

        fun get(ctx: Context): NotesDb =
            instance ?: synchronized(this) {
                instance ?: NotesDb(ctx).also { instance = it }
            }
    }
}
