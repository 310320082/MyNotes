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
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val favoritesCategory = "Избранное"
    private val shoppingCategory = "Покупки"
    private val ordinaryCategory = "Обычные"
    private val expensesCategory = "Расходы"
    private val categories = listOf(favoritesCategory, shoppingCategory, ordinaryCategory, expensesCategory)
    private val notes = mutableListOf<Note>()
    private val expenses = mutableListOf<Expense>()
    private val preferences by lazy { getSharedPreferences("notes_storage", MODE_PRIVATE) }
    private lateinit var notesDatabase: NotesDatabaseHelper
    private lateinit var homePage: View
    private lateinit var categoryPage: View
    private lateinit var notesContainer: LinearLayout
    private lateinit var noteEntryForm: View
    private lateinit var expenseEntryForm: View
    private lateinit var editTextNote: EditText
    private lateinit var editTextExpenseDescription: EditText
    private lateinit var editTextExpenseAmount: EditText
    private lateinit var currentCategory: String
    private var selectedReminder: Long? = null
    private var selectedExpenseDate: Long = 0L
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        notesDatabase = NotesDatabaseHelper(applicationContext)
        notesDatabase.migrateLegacyPreferences(preferences)
        homePage = findViewById(R.id.homePage)
        categoryPage = findViewById(R.id.categoryPage)
        notesContainer = findViewById(R.id.notesContainer)
        noteEntryForm = findViewById(R.id.noteEntryForm)
        expenseEntryForm = findViewById(R.id.expenseEntryForm)
        editTextNote = findViewById(R.id.editTextNote)
        editTextExpenseDescription = findViewById(R.id.editTextExpenseDescription)
        editTextExpenseAmount = findViewById(R.id.editTextExpenseAmount)
        ensureNotificationChannel()
        loadNotes()
        loadExpenses()
        notes.filter { it.reminderAt != null && it.reminderAt!! > System.currentTimeMillis() }.forEach { scheduleReminder(it) }
        findViewById<Button>(R.id.buttonFavorites).setOnClickListener { openCategory(categories[0]) }
        findViewById<Button>(R.id.buttonShopping).setOnClickListener { openCategory(categories[1]) }
        findViewById<Button>(R.id.buttonTemporary).setOnClickListener { openCategory(categories[2]) }
        findViewById<Button>(R.id.buttonExpenses).setOnClickListener { openCategory(expensesCategory) }
        findViewById<Button>(R.id.buttonBack).setOnClickListener { showHome() }
        findViewById<Button>(R.id.buttonAdd).setOnClickListener { addNote() }
        findViewById<Button>(R.id.buttonReminder).setOnClickListener { chooseReminder() }
        findViewById<Button>(R.id.buttonExpenseDate).setOnClickListener { chooseExpenseDate() }
        findViewById<Button>(R.id.buttonAddExpense).setOnClickListener { addExpense() }
        showHome()
    }

    private fun openCategory(category: String) {
        currentCategory = category
        val isFavorites = category == favoritesCategory
        val isExpenses = category == expensesCategory
        noteEntryForm.visibility = if (isFavorites || isExpenses) View.GONE else View.VISIBLE
        expenseEntryForm.visibility = if (isExpenses) View.VISIBLE else View.GONE
        selectedReminder = null
        findViewById<Button>(R.id.buttonReminder).text = reminderButtonLabel(category)
        if (isExpenses) {
            selectedExpenseDate = startOfDay(System.currentTimeMillis())
            findViewById<Button>(R.id.buttonExpenseDate).text = "Дата: ${formatDate(selectedExpenseDate)}"
            editTextExpenseDescription.text.clear()
            editTextExpenseAmount.text.clear()
        }
        findViewById<TextView>(R.id.textViewCategoryTitle).text = category
        homePage.visibility = View.GONE
        categoryPage.visibility = View.VISIBLE
        showNotes()
    }

    private fun showHome() { categoryPage.visibility = View.GONE; homePage.visibility = View.VISIBLE }

    override fun onDestroy() {
        if (::notesDatabase.isInitialized) notesDatabase.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::categoryPage.isInitialized && categoryPage.visibility == View.VISIBLE) showHome() else super.onBackPressed()
    }

    private fun reminderButtonLabel(category: String) =
        if (category == shoppingCategory) "Выбрать дату" else "Выбрать дату и время"

    private fun chooseReminder() {
        if (currentCategory == shoppingCategory) {
            chooseShoppingDate()
            return
        }
        val now = Calendar.getInstance()
        val firstAllowedDate = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val chosen = Calendar.getInstance().apply {
                    clear()
                    set(year, month, day, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (chosen.timeInMillis <= System.currentTimeMillis()) {
                    selectedReminder = null
                    findViewById<Button>(R.id.buttonReminder).text = "Выбрать дату и время"
                    editTextNote.error = "Выберите дату и время в будущем"
                    return@TimePickerDialog
                }
                selectedReminder = chosen.timeInMillis
                editTextNote.error = null
                findViewById<Button>(R.id.buttonReminder).text = "Напомнить: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(chosen.time)}"
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = firstAllowedDate.timeInMillis
        }.show()
    }

    private fun chooseShoppingDate() {
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            val chosen = Calendar.getInstance().apply {
                clear()
                set(year, month, day, 9, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (chosen.timeInMillis <= System.currentTimeMillis()) {
                selectedReminder = null
                editTextNote.error = "Выберите будущую дату"
                findViewById<Button>(R.id.buttonReminder).text = "Выбрать дату"
                return@DatePickerDialog
            }
            selectedReminder = chosen.timeInMillis
            editTextNote.error = null
            findViewById<Button>(R.id.buttonReminder).text = "Дата: ${formatDate(chosen.timeInMillis)}"
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = startOfDay(now.timeInMillis)
        }.show()
    }

    private fun addNote() {
        val text = editTextNote.text.toString().trim()
        if (text.isEmpty()) { editTextNote.error = "Введите текст заметки"; return }
        val note = Note(text, currentCategory, selectedReminder, dateOnly = currentCategory == shoppingCategory)
        notes.add(note)
        if (note.reminderAt != null) scheduleReminder(note)
        saveNotes()
        editTextNote.text.clear()
        selectedReminder = null
        editTextNote.error = null
        findViewById<Button>(R.id.buttonReminder).text = reminderButtonLabel(currentCategory)
        showNotes()
    }

    private fun showNotes() {
        notesContainer.removeAllViews()
        if (currentCategory == expensesCategory) {
            showExpenses()
            return
        }
        val categoryNotes = notes.filter { it.category == currentCategory }
        if (categoryNotes.isEmpty()) {
            notesContainer.addView(TextView(this).apply { text = "Пока здесь нет заметок"; textSize = 16f })
            return
        }
        categoryNotes.forEach { note ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(12, 8, 4, 8) }
            val label = note.reminderAt?.let {
                val formatted = if (note.dateOnly) formatDate(it) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it)
                "\n${if (note.dateOnly) "📅" else "⏰"} $formatted"
            } ?: ""
            row.addView(TextView(this).apply { text = "• ${note.text}$label"; textSize = 18f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            if (note.category != favoritesCategory) {
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

    private fun chooseExpenseDate() {
        val selected = Calendar.getInstance().apply { timeInMillis = selectedExpenseDate }
        DatePickerDialog(this, { _, year, month, day ->
            selectedExpenseDate = startOfDay(year, month, day)
            findViewById<Button>(R.id.buttonExpenseDate).text = "Дата: ${formatDate(selectedExpenseDate)}"
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun addExpense() {
        val description = editTextExpenseDescription.text.toString().trim()
        if (description.isEmpty()) {
            editTextExpenseDescription.error = "Введите, на что потрачено"
            return
        }
        val rawAmount = editTextExpenseAmount.text.toString().trim().replace(" ", "").replace(',', '.')
        val amountKopecks = try {
            BigDecimal(rawAmount).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (_: Exception) {
            null
        }
        if (amountKopecks == null || amountKopecks <= 0L) {
            editTextExpenseAmount.error = "Введите сумму больше 0"
            return
        }

        expenses.add(Expense(selectedExpenseDate, amountKopecks, description))
        saveExpenses()
        editTextExpenseDescription.text.clear()
        editTextExpenseDescription.error = null
        editTextExpenseAmount.text.clear()
        editTextExpenseAmount.error = null
        showExpenses()
    }

    private fun showExpenses() {
        notesContainer.removeAllViews()
        val today = Calendar.getInstance()
        val weekStart = Calendar.getInstance().apply {
            timeInMillis = startOfDay(today.timeInMillis)
            val daysSinceMonday = (get(Calendar.DAY_OF_WEEK) + 5) % 7
            add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
        }.timeInMillis
        val nextWeekStart = Calendar.getInstance().apply {
            timeInMillis = weekStart
            add(Calendar.DAY_OF_YEAR, 7)
        }.timeInMillis
        val weekTotal = expenses.filter { it.date >= weekStart && it.date < nextWeekStart }.sumOf { it.amountKopecks }
        val monthTotal = expenses.filter { expense ->
            val date = Calendar.getInstance().apply { timeInMillis = expense.date }
            date.get(Calendar.YEAR) == today.get(Calendar.YEAR) && date.get(Calendar.MONTH) == today.get(Calendar.MONTH)
        }.sumOf { it.amountKopecks }
        val yearTotal = expenses.filter { expense ->
            Calendar.getInstance().apply { timeInMillis = expense.date }.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        }.sumOf { it.amountKopecks }
        notesContainer.addView(TextView(this).apply {
            text = "Итого за неделю: ${formatMoney(weekTotal)}\nИтого за месяц: ${formatMoney(monthTotal)}\nИтого за ${today.get(Calendar.YEAR)} год: ${formatMoney(yearTotal)}"
            textSize = 18f
            setPadding(12, 12, 12, 20)
        })

        if (expenses.isEmpty()) {
            notesContainer.addView(TextView(this).apply { text = "Пока нет расходов"; textSize = 16f; setPadding(12, 8, 4, 8) })
            return
        }
        expenses.groupBy { startOfDay(it.date) }.entries.sortedByDescending { it.key }.forEach { (date, dayExpenses) ->
            val total = dayExpenses.sumOf { it.amountKopecks }
            notesContainer.addView(TextView(this).apply {
                text = "• ${formatDate(date)} — ${formatMoney(total)}"
                textSize = 18f
                setPadding(12, 8, 4, 8)
            })
            dayExpenses.asReversed().forEach { expense ->
                notesContainer.addView(TextView(this).apply {
                    text = "   ${expense.description} — ${formatMoney(expense.amountKopecks)}"
                    textSize = 16f
                    setPadding(20, 4, 4, 8)
                })
            }
        }
    }

    private fun formatDate(time: Long) =
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.forLanguageTag("ru-RU")).format(Date(time))

    private fun formatMoney(amountKopecks: Long) =
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU")).format(BigDecimal.valueOf(amountKopecks, 2))

    private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfDay(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, month, day, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

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
        notes.clear()
        notesDatabase.readNotes().forEach { saved ->
            notes.add(Note(saved.text, saved.category, saved.reminderAt, saved.id, saved.dateOnly))
        }
    }

    private fun loadExpenses() {
        expenses.clear()
        notesDatabase.readExpenses().forEach { saved ->
            expenses.add(Expense(saved.date, saved.amountKopecks, saved.description))
        }
    }

    private fun saveNotes() {
        notesDatabase.replaceNotes(notes.map { note ->
            NoteRecord(note.id, note.text, note.category, note.reminderAt, note.dateOnly)
        })
    }

    private fun saveExpenses() {
        notesDatabase.replaceExpenses(expenses.map { expense ->
            ExpenseRecord(expense.date, expense.amountKopecks, expense.description)
        })
    }

    private data class Note(val text: String, val category: String, val reminderAt: Long? = null, val id: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), val dateOnly: Boolean = false)
    private data class Expense(val date: Long, val amountKopecks: Long, val description: String)
}
