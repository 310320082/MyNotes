package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

class NotesDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notes (
                id INTEGER PRIMARY KEY,
                position INTEGER NOT NULL,
                text TEXT NOT NULL,
                category TEXT NOT NULL,
                reminder_at INTEGER,
                date_only INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date INTEGER NOT NULL,
                amount_kopecks INTEGER NOT NULL,
                description TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE TABLE app_metadata (name TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun migrateLegacyPreferences(preferences: SharedPreferences) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val alreadyMigrated = db.rawQuery(
                "SELECT 1 FROM app_metadata WHERE name = ?",
                arrayOf(LEGACY_MIGRATION_KEY)
            ).use { it.moveToFirst() }

            if (!alreadyMigrated) {
                importLegacyNotes(db, preferences.getString("notes", null))
                importLegacyExpenses(db, preferences.getString("expenses", null))
                db.insertOrThrow(
                    "app_metadata",
                    null,
                    ContentValues().apply {
                        put("name", LEGACY_MIGRATION_KEY)
                        put("value", "1")
                    }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        preferences.edit().remove("notes").remove("expenses").apply()
    }

    fun readNotes(): List<NoteRecord> = readableDatabase.rawQuery(
        "SELECT id, text, category, reminder_at, date_only FROM notes ORDER BY position ASC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    NoteRecord(
                        id = cursor.getInt(0),
                        text = cursor.getString(1),
                        category = cursor.getString(2),
                        reminderAt = if (cursor.isNull(3)) null else cursor.getLong(3),
                        dateOnly = cursor.getInt(4) != 0
                    )
                )
            }
        }
    }

    fun replaceNotes(notes: List<NoteRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("notes", null, null)
            notes.forEachIndexed { index, note ->
                db.insertOrThrow(
                    "notes",
                    null,
                    ContentValues().apply {
                        put("id", note.id)
                        put("position", index)
                        put("text", note.text)
                        put("category", note.category)
                        if (note.reminderAt == null) putNull("reminder_at") else put("reminder_at", note.reminderAt)
                        put("date_only", if (note.dateOnly) 1 else 0)
                    }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun readExpenses(): List<ExpenseRecord> = readableDatabase.rawQuery(
        "SELECT date, amount_kopecks, description FROM expenses ORDER BY id ASC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(ExpenseRecord(cursor.getLong(0), cursor.getLong(1), cursor.getString(2)))
            }
        }
    }

    fun replaceExpenses(expenses: List<ExpenseRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("expenses", null, null)
            expenses.forEach { expense ->
                db.insertOrThrow(
                    "expenses",
                    null,
                    ContentValues().apply {
                        put("date", expense.date)
                        put("amount_kopecks", expense.amountKopecks)
                        put("description", expense.description)
                    }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun importLegacyNotes(db: SQLiteDatabase, json: String?) {
        val array = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return
        for (index in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            val text = item.optString("text").trim()
            if (text.isEmpty()) continue
            val savedCategory = item.optString("category", ORDINARY_CATEGORY)
            val category = if (savedCategory == "Временные") ORDINARY_CATEGORY else savedCategory
            val reminderAt = if (item.has("reminderAt") && !item.isNull("reminderAt")) item.optLong("reminderAt") else null
            db.insertWithOnConflict(
                "notes",
                null,
                ContentValues().apply {
                    put("id", item.optInt("id", index + 1))
                    put("position", index)
                    put("text", text)
                    put("category", category)
                    if (reminderAt == null) putNull("reminder_at") else put("reminder_at", reminderAt)
                    put("date_only", if (item.optBoolean("dateOnly", category == SHOPPING_CATEGORY)) 1 else 0)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    private fun importLegacyExpenses(db: SQLiteDatabase, json: String?) {
        val array = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return
        for (index in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            if (!item.has("date") || !item.has("amountKopecks")) continue
            db.insert(
                "expenses",
                null,
                ContentValues().apply {
                    put("date", item.optLong("date"))
                    put("amount_kopecks", item.optLong("amountKopecks"))
                    put("description", item.optString("description", "Без описания").ifBlank { "Без описания" })
                }
            )
        }
    }

    companion object {
        const val DATABASE_NAME = "notes.db"
        private const val DATABASE_VERSION = 1
        private const val LEGACY_MIGRATION_KEY = "legacy_preferences_imported"
        private const val ORDINARY_CATEGORY = "Обычные"
        private const val SHOPPING_CATEGORY = "Покупки"
    }
}

data class NoteRecord(
    val id: Int,
    val text: String,
    val category: String,
    val reminderAt: Long?,
    val dateOnly: Boolean
)

data class ExpenseRecord(val date: Long, val amountKopecks: Long, val description: String)
