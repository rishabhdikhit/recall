package app.recall

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Entry(
    val id: String,
    val title: String,
    val summary: String,
    val transcript: String,
    val url: String,
    val source: String,
    val topic: String,
    val subtags: String,
    val language: String,
    val hasSpeech: Int,
    val createdAt: Long,
    val starred: Int,
)

private class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "recall.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE entries (
              id TEXT PRIMARY KEY NOT NULL,
              title TEXT NOT NULL,
              summary TEXT NOT NULL,
              transcript TEXT NOT NULL DEFAULT '',
              url TEXT NOT NULL UNIQUE,
              source TEXT NOT NULL,
              topic TEXT NOT NULL,
              subtags TEXT NOT NULL DEFAULT '',
              language TEXT NOT NULL DEFAULT '',
              hasSpeech INTEGER NOT NULL DEFAULT 1,
              createdAt INTEGER NOT NULL,
              starred INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_topic ON entries(topic)")
        db.execSQL("CREATE INDEX idx_created ON entries(createdAt)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

object Repo {
    private lateinit var helper: DbHelper

    fun init(ctx: Context) {
        helper = DbHelper(ctx.applicationContext)
    }

    private fun Cursor.toEntry(): Entry = Entry(
        id = getString(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        summary = getString(getColumnIndexOrThrow("summary")),
        transcript = getString(getColumnIndexOrThrow("transcript")),
        url = getString(getColumnIndexOrThrow("url")),
        source = getString(getColumnIndexOrThrow("source")),
        topic = getString(getColumnIndexOrThrow("topic")),
        subtags = getString(getColumnIndexOrThrow("subtags")),
        language = getString(getColumnIndexOrThrow("language")),
        hasSpeech = getInt(getColumnIndexOrThrow("hasSpeech")),
        createdAt = getLong(getColumnIndexOrThrow("createdAt")),
        starred = getInt(getColumnIndexOrThrow("starred")),
    )

    private fun query(where: String?, args: Array<String>?): List<Entry> {
        val sql = buildString {
            append("SELECT * FROM entries")
            if (where != null) append(" WHERE ").append(where)
            append(" ORDER BY starred DESC, createdAt DESC")
        }
        val list = ArrayList<Entry>()
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) list.add(c.toEntry())
        }
        return list
    }

    fun save(e: Entry) {
        val cv = ContentValues().apply {
            put("id", e.id)
            put("title", e.title)
            put("summary", e.summary)
            put("transcript", e.transcript)
            put("url", e.url)
            put("source", e.source)
            put("topic", e.topic)
            put("subtags", e.subtags)
            put("language", e.language)
            put("hasSpeech", e.hasSpeech)
            put("createdAt", e.createdAt)
            put("starred", e.starred)
        }
        helper.writableDatabase.insertWithOnConflict("entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun all(): List<Entry> = query(null, null)

    fun byTopic(topic: String): List<Entry> = query("topic = ?", arrayOf(topic))

    fun search(q: String): List<Entry> {
        val like = "%$q%"
        return query(
            "title LIKE ? OR summary LIKE ? OR transcript LIKE ? OR subtags LIKE ?",
            arrayOf(like, like, like, like),
        )
    }

    fun getByUrl(url: String): Entry? {
        helper.readableDatabase.rawQuery("SELECT * FROM entries WHERE url = ?", arrayOf(url)).use { c ->
            return if (c.moveToFirst()) c.toEntry() else null
        }
    }

    fun delete(id: String) {
        helper.writableDatabase.delete("entries", "id = ?", arrayOf(id))
    }

    fun setStar(id: String, starred: Int) {
        val cv = ContentValues().apply { put("starred", starred) }
        helper.writableDatabase.update("entries", cv, "id = ?", arrayOf(id))
    }
}
