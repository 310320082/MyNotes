package com.example.myapplication

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar

class MainActivity : AppCompatActivity() {
    private val categories = listOf("Избранное", "Покупки", "Обычные")
    private val notes = mutableListOf<Note>()
    private val preferences by lazy { getSharedPreferences("notes_storage", MODE_PRIVATE) }
    private lateinit var homePage: View
    private lateinit var categoryPage: View
    private lateinit var notesContainer: LinearLayout
    private lateinit var noteEntryForm: View
    private lateinit var editTextNote: EditText
    private lateinit var currentCategory: String
    private var selectedReminder: Long? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        homePage = findViewById(R.id.homePage)
        categoryPage = findViewById(R.id.categoryPage)
        notesContainer = findViewById(R.id.notesContainer)
        noteEntryForm = findViewById(R.id.noteEntryForm)
        editTextNote = findViewById(R.id.editTextNote)
        ensureNotificationChannel()
        loadNotes()
        notes.filter { it.reminderAt != null && it.reminderAt!! > System.currentTimeMillis() }.forEach { scheduleReminder(it) }
        findViewById<Button>(R.id.buttonFavorites).setOnClickListener { openCategory(categories[0]) }
        findViewById<Button>(R.id.buttonShopping).setOnClickListener { openCategory(categories[1]) }
        findViewById<Button>(R.id.buttonTemporary).setOnClickListener { openCategory(categories[2]) }
        findViewById<Button>(R.id.buttonBack).setOnClickListener { showHome() }
        findViewById<Button>(R.id.buttonAdd).setOnClickListener { addNote() }
        findViewById<Button>(R.id.buttonReminder).setOnClickListener { chooseReminder() }
        showHome()
    }

    private fun openCategory(category: String) {
        currentCategory = category
        noteEntryForm.visibility = if (category == categories[0]) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.textViewCategoryTitle).text = category
        homePage.visibility = View.GONE
        categoryPage.visibility = View.VISIBLE
        showNotes()
    }

    private fun showHome() { categoryPage.visibility = View.GONE; homePage.visibility = View.VISIBLE }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::categoryPage.isInitialized && categoryPage.visibility == View.VISIBLE) showHome() else super.onBackPressed()
    }

    private fun chooseReminder() {
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val chosen = Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }
                if (chosen.timeInMillis <= System.currentTimeMillis()) {
                    editTextNote.error = "Выберите время в будущем"
                    selectedReminder = null
                    return@TimePickerDialog
                }
                selectedReminder = chosen.timeInMillis
                findViewById<Button>(R.id.buttonReminder).text = "Напомнить: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(chosen.time)}"
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun addNote() {
        val text = editTextNote.text.toString().trim()
        if (text.isEmpty()) { editTextNote.error = "Введите текст заметки"; return }
        val note = Note(text, currentCategory, selectedReminder)
        notes.add(note)
        if (note.reminderAt != null) scheduleReminder(note)
        saveNotes()
        editTextNote.text.clear()
        selectedReminder = null
        findViewById<Button>(R.id.buttonReminder).text = "Выбрать дату и время"
        showNotes()
    }

    private fun showNotes() {
        notesContainer.removeAllViews()
        val categoryNotes = notes.filter { it.category == currentCategory }
        if (categoryNotes.isEmpty()) {
            notesContainer.addView(TextView(this).apply { text = "Пока здесь нет заметок"; textSize = 16f })
            return
        }
        categoryNotes.forEach { note ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(12, 8, 4, 8) }
            val label = note.reminderAt?.let { "\n⏰ ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it)}" } ?: ""
            row.addView(TextView(this).apply { text = "• ${note.text}$label"; textSize = 18f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            if (note.category != categories[0]) {
                row.addView(Button(this).apply {
                    text = "☆"
                    contentDescription = "Добавить в избранное"
                    setOnClickListener {
                        val index = notes.indexOf(note)
                        if (index >= 0) notes[index] = note.copy(category = categories[0])
                        saveNotes()
                        showNotes()
                    }
                })
            }
            row.addView(Button(this).apply { text = "Удалить"; setOnClickListener { cancelReminder(note); notes.remove(note); saveNotes(); showNotes() } })
            notesContainer.addView(row)
        }
    }

    private fun reminderIntent(note: Note): PendingIntent {
        val intent = Intent(this, ReminderReceiver::class.java).putExtra("note_text", note.text).putExtra("note_id", note.id)
        return PendingIntent.getBroadcast(this, note.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun scheduleReminder(note: Note) {
        val time = note.reminderAt ?: return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val alarms = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = reminderIntent(note)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        else alarms.set(AlarmManager.RTC_WAKEUP, time, pending)
    }

    private fun cancelReminder(note: Note) {
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(reminderIntent(note))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel("note_reminders", "Напоминания о заметках", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun loadNotes() {
        val saved = preferences.getString("notes", null) ?: return
        try {
            val array = JSONArray(saved)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val savedCategory = item.optString("category", categories.last())
                val category = if (savedCategory == "Временные") categories.last() else savedCategory
                notes.add(Note(item.optString("text"), category, item.optLong("reminderAt").takeIf { item.has("reminderAt") && !item.isNull("reminderAt") }, item.optInt("id", index + 1)))
            }
        } catch (_: Exception) { notes.clear() }
    }

    private fun saveNotes() {
        val array = JSONArray()
        notes.forEach { note -> array.put(JSONObject().put("text", note.text).put("category", note.category).put("id", note.id).apply { note.reminderAt?.let { put("reminderAt", it) } ?: put("reminderAt", JSONObject.NULL) }) }
        preferences.edit().putString("notes", array.toString()).apply()
    }

    private data class Note(val text: String, val category: String, val reminderAt: Long? = null, val id: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
}
