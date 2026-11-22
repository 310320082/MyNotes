package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.hasErrorText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import org.hamcrest.Matcher
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class NotesFlowInstrumentedTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun prepareCleanTestState() {
        notesPreferences().edit().clear().commit()
        context.deleteDatabase(NotesDatabaseHelper.DATABASE_NAME)
        if (Build.VERSION.SDK_INT >= 33) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS")
            try {
                automation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }
    }

    @After
    fun closeApp() {
        if (::scenario.isInitialized) scenario.close()
        notesPreferences().edit().clear().commit()
        context.deleteDatabase(NotesDatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun noteCanBeAddedPersistedFavoritedAndDeleted() {
        launchApp()
        onView(withId(R.id.buttonShopping)).perform(click())
        onView(withId(R.id.textViewCategoryTitle)).check(matches(withText("Покупки")))
        onView(withId(R.id.editTextNote)).perform(replaceText("Тестовая покупка"), closeSoftKeyboard())
        onView(withId(R.id.buttonAdd)).perform(click())
        onView(withText(startsWith("• Тестовая покупка"))).check(matches(isDisplayed()))

        scenario.recreate()
        onView(withId(R.id.buttonShopping)).perform(click())
        onView(withText(startsWith("• Тестовая покупка"))).check(matches(isDisplayed()))
        onView(withContentDescription("Добавить в избранное")).perform(click())
        onView(withText(startsWith("• Тестовая покупка"))).check(doesNotExist())

        onView(withId(R.id.buttonBack)).perform(click())
        onView(withId(R.id.buttonFavorites)).perform(click())
        onView(withId(R.id.noteEntryForm)).check(matches(not(isDisplayed())))
        onView(withText(startsWith("• Тестовая покупка"))).check(matches(isDisplayed()))
        onView(withText("Удалить")).perform(click())
        onView(withText(startsWith("• Тестовая покупка"))).check(doesNotExist())
    }

    @Test
    fun reminderRejectsPastTimeThenAcceptsFutureTimeAndPersistsIt() {
        launchApp()
        onView(withId(R.id.buttonTemporary)).perform(click())
        onView(withId(R.id.editTextNote)).perform(replaceText("Заметка с напоминанием"), closeSoftKeyboard())

        val today = Calendar.getInstance()
        onView(withId(R.id.buttonReminder)).perform(click())
        onView(isAssignableFrom(DatePicker::class.java)).perform(setDate(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH)))
        onView(withId(android.R.id.button1)).perform(click())
        onView(isAssignableFrom(TimePicker::class.java)).perform(setTime(0, 0))
        onView(withId(android.R.id.button1)).perform(click())
        onView(withId(R.id.editTextNote)).check(matches(hasErrorText("Выберите дату и время в будущем")))

        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        onView(withId(R.id.buttonReminder)).perform(click())
        onView(isAssignableFrom(DatePicker::class.java)).perform(setDate(tomorrow.get(Calendar.YEAR), tomorrow.get(Calendar.MONTH), tomorrow.get(Calendar.DAY_OF_MONTH)))
        onView(withId(android.R.id.button1)).perform(click())
        onView(isAssignableFrom(TimePicker::class.java)).perform(setTime(0, 5))
        onView(withId(android.R.id.button1)).perform(click())
        onView(withId(R.id.buttonReminder)).check(matches(withText(startsWith("Напомнить:"))))
        onView(withId(R.id.editTextNote)).check(matches(not(hasErrorText("Выберите дату и время в будущем"))))

        onView(withId(R.id.buttonAdd)).perform(click())
        onView(withText(startsWith("• Заметка с напоминанием\n⏰"))).check(matches(isDisplayed()))
        val savedNote = savedNotes().single()
        assertTrue(savedNote.reminderAt!! > System.currentTimeMillis())
        assertEquals("Обычные", savedNote.category)
    }

    @Test
    fun shoppingReminderUsesDateOnly() {
        launchApp()
        onView(withId(R.id.buttonShopping)).perform(click())
        onView(withId(R.id.editTextNote)).perform(replaceText("Покупка по дате"), closeSoftKeyboard())

        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        onView(withId(R.id.buttonReminder)).perform(click())
        onView(isAssignableFrom(DatePicker::class.java)).perform(setDate(tomorrow.get(Calendar.YEAR), tomorrow.get(Calendar.MONTH), tomorrow.get(Calendar.DAY_OF_MONTH)))
        onView(withId(android.R.id.button1)).perform(click())
        onView(isAssignableFrom(TimePicker::class.java)).check(doesNotExist())
        onView(withId(R.id.buttonReminder)).check(matches(withText(startsWith("Дата:"))))

        onView(withId(R.id.buttonAdd)).perform(click())
        onView(withText(startsWith("• Покупка по дате\n📅"))).check(matches(isDisplayed()))
        val savedNote = savedNotes().single()
        assertTrue(savedNote.dateOnly)
        assertTrue(savedNote.reminderAt!! > System.currentTimeMillis())
    }

    @Test
    fun dailyExpensesAccumulateAndUpdateMonthlyAndYearlyTotals() {
        launchApp()
        onView(withId(R.id.buttonExpenses)).perform(click())
        onView(withId(R.id.noteEntryForm)).check(matches(not(isDisplayed())))
        onView(withId(R.id.expenseEntryForm)).check(matches(isDisplayed()))

        onView(withId(R.id.buttonExpenseDate)).perform(click())
        val today = Calendar.getInstance()
        onView(isAssignableFrom(DatePicker::class.java)).perform(setDate(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH)))
        onView(withId(android.R.id.button1)).perform(click())
        onView(withId(R.id.editTextExpenseDescription)).perform(replaceText("Продукты"), closeSoftKeyboard())
        onView(withId(R.id.editTextExpenseAmount)).perform(replaceText("125,50"), closeSoftKeyboard())
        onView(withId(R.id.buttonAddExpense)).perform(click())
        onView(withText(containsString("Итого за месяц:"))).check(matches(isDisplayed()))
        onView(withText(startsWith("Итого за неделю:"))).check(matches(isDisplayed()))
        onView(withText(startsWith("• "))).check(matches(isDisplayed()))

        onView(withId(R.id.editTextExpenseDescription)).perform(replaceText("Кофе"), closeSoftKeyboard())
        onView(withId(R.id.editTextExpenseAmount)).perform(replaceText("75,50"), closeSoftKeyboard())
        onView(withId(R.id.buttonAddExpense)).perform(click())
        val savedExpenses = savedExpenses()
        assertEquals(2, savedExpenses.size)
        assertEquals(20100L, savedExpenses.sumOf { it.amountKopecks })
        val descriptions = savedExpenses.map { it.description }
        assertTrue(descriptions.containsAll(listOf("Продукты", "Кофе")))

        scenario.recreate()
        onView(withId(R.id.buttonExpenses)).perform(click())
        onView(withText(org.hamcrest.Matchers.containsString("Итого за неделю: 201,00"))).check(matches(isDisplayed()))
        onView(withText(org.hamcrest.Matchers.containsString("Итого за месяц: 201,00"))).check(matches(isDisplayed()))
        onView(withText(org.hamcrest.Matchers.containsString("Итого за ${today.get(Calendar.YEAR)} год: 201,00"))).check(matches(isDisplayed()))
        onView(withText(containsString("Продукты — 125,50"))).check(matches(isDisplayed()))
        onView(withText(containsString("Кофе — 75,50"))).check(matches(isDisplayed()))
    }

    @Test
    fun legacyTemporaryNotesAreShownInOrdinaryCategory() {
        val oldNotes = JSONArray().put(JSONObject().put("text", "Старая заметка").put("category", "Временные"))
        notesPreferences().edit().putString("notes", oldNotes.toString()).commit()
        launchApp()

        onView(withId(R.id.buttonTemporary)).perform(click())
        onView(withText(startsWith("• Старая заметка"))).check(matches(isDisplayed()))
    }

    @Test
    fun reminderReceiverPostsTheExpectedNotification() {
        launchApp()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = 73421
        val reminderText = "Текст тестового напоминания"
        ReminderReceiver().onReceive(
            context,
            Intent(context, ReminderReceiver::class.java)
                .putExtra("note_text", reminderText)
                .putExtra("note_id", notificationId)
        )

        var notification: Notification? = null
        val deadline = SystemClock.uptimeMillis() + 3_000L
        while (notification == null && SystemClock.uptimeMillis() < deadline) {
            notification = notificationManager.activeNotifications
                .firstOrNull { it.id == notificationId }?.notification
            if (notification == null) SystemClock.sleep(100L)
        }
        try {
            assertNotNull(notification)
            assertEquals("Напоминание", notification!!.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(reminderText, notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        } finally {
            notificationManager.cancel(notificationId)
        }
    }

    private fun notesPreferences() = context.getSharedPreferences("notes_storage", Context.MODE_PRIVATE)

    private fun savedNotes() = NotesDatabaseHelper(context).run {
        try { readNotes() } finally { close() }
    }

    private fun savedExpenses() = NotesDatabaseHelper(context).run {
        try { readExpenses() } finally { close() }
    }

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun setDate(year: Int, month: Int, day: Int) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
        override fun getDescription() = "set date to $year-${month + 1}-$day"
        override fun perform(uiController: UiController, view: View) {
            (view as DatePicker).updateDate(year, month, day)
        }
    }

    private fun setTime(hour: Int, minute: Int) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(TimePicker::class.java)
        override fun getDescription() = "set time to $hour:$minute"
        override fun perform(uiController: UiController, view: View) {
            (view as TimePicker).hour = hour
            view.minute = minute
        }
    }
}
