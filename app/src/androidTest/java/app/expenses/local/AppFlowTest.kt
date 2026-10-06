package app.expenses.local

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule(order = 0)
    val cleanEmulator =
        object : org.junit.rules.ExternalResource() {
            override fun before() {
                org.junit.Assume.assumeTrue(
                    "UI setup test runs only on an emulator",
                    android.os.Build.HARDWARE.contains("ranchu") ||
                        android.os.Build.HARDWARE.contains("goldfish"),
                )
                val c = InstrumentationRegistry.getInstrumentation().targetContext
                c.deleteDatabase("expenses.db")
                listOf("security", "appearance", "drafts").forEach {
                    c.getSharedPreferences(it, 0).edit().clear().commit()
                }
            }
        }
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String) {
        try {
            ui.waitUntil(30_000) {
                ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                    ui.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            var status = ""
            ui.runOnUiThread {
                val s = ViewModelProvider(ui.activity)[ExpensesViewModel::class.java].state.value
                status =
                    "locked=${s.locked}, busy=${s.busy}, profiles=${s.profiles.size}, error=${s.error}"
            }
            throw AssertionError(
                "Waiting for $text: $status\n${ui.onRoot(useUnmergedTree=true).printToString()}",
                e,
            )
        }
    }

    private fun enter(label: String, value: String) {
        runCatching { ui.onNodeWithText(label).performScrollTo() }
        ui.onNodeWithText(label).performTextInput(value)
    }

    private fun showRecent(text: String) {
        ui.waitUntil(30_000) {
            val s = ViewModelProvider(ui.activity)[ExpensesViewModel::class.java].state.value
            !s.busy && s.recent.any { it.description == text }
        }
        ui.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        waitFor(text)
    }

    private fun click(text: String) {
        val node =
            if (ui.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty())
                ui.onNodeWithContentDescription(text)
            else ui.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun screenshot(name: String) {
        ui.runOnUiThread { ui.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        ui.waitForIdle()
        Thread.sleep(300)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val output = java.io.File(ui.activity.getExternalFilesDir(null), name)
        output.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        ui.runOnUiThread { ui.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    @Test
    fun setupExpenseSecurityRecoveryAndThemes() {
        waitFor("Create secure app")
        enter("PIN/password", "123456")
        enter("Confirm PIN/password", "123456")
        click("Create secure app")
        waitFor("Save your recovery code")
        var recovery = ""
        ui.runOnUiThread {
            recovery =
                ViewModelProvider(ui.activity)[ExpensesViewModel::class.java].state.value.recovery!!
        }
        ui.onAllNodes(isToggleable()).onFirst().performClick()
        click("Continue")
        click("Create profile")
        enter("Profile name", "Personal")
        click("Save")
        waitFor("Add entry")
        click("Add entry")
        screenshot("entry-choice.png")
        click("Expense")
        val descriptionTop =
            ui.onNodeWithText("What was this expense for?").fetchSemanticsNode().boundsInRoot.top
        val amountTop = ui.onNodeWithText("Amount · INR").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Description must precede amount", descriptionTop < amountTop)
        screenshot("entry-form.png")
        enter("Amount · INR", "12.34")
        enter("What was this expense for?", "Groceries test")
        click("Save")
        showRecent("Groceries test")
        click("Add entry")
        click("Income")
        enter("Amount · INR", "50")
        enter("Where did this income come from?", "Tuition payment")
        click("Save")
        showRecent("Tuition payment")
        click("Add entry")
        click("Income")
        enter("Amount · INR", "25")
        enter("Where did this income come from?", "Tailoring payment")
        click("Save")
        showRecent("Tailoring payment")
        ui.runOnUiThread {
            val s =
                ViewModelProvider(ui.activity)[ExpensesViewModel::class.java].state.value.summary
            assertEquals(7500L, s.income)
            assertEquals(1234L, s.total)
            assertEquals(6266L, s.remaining)
        }
        showRecent("Tuition payment")
        click("Tuition payment")
        ui.onNodeWithText("Amount · INR").performTextReplacement("60")
        click("Save")
        ui.waitUntil(30_000) {
            ViewModelProvider(ui.activity)[ExpensesViewModel::class.java]
                .state
                .value
                .summary
                .income == 8500L
        }
        ui.onNodeWithText("Set salary & budget").assertDoesNotExist()
        ui.onNodeWithText("Budget").assertDoesNotExist()
        ui.onNode(hasScrollAction()).performScrollToIndex(0)
        screenshot("dashboard-dark.png")
        ui.onNodeWithContentDescription("Toggle light/dark mode").performClick()
        screenshot("dashboard-light.png")
        click("Entries")
        click("Income")
        ui.waitUntil(30_000) {
            ViewModelProvider(ui.activity)[ExpensesViewModel::class.java].state.value.expenses.all {
                it.type == EntryType.INCOME
            }
        }
        ui.onNodeWithText("Groceries test").assertDoesNotExist()
        click("All")
        waitFor("Groceries test")
        ui.onNodeWithContentDescription("Delete Groceries test").performClick()
        waitFor("Delete expense permanently?")
        ui.onAllNodes(isToggleable()).onFirst().performClick()
        enter("App PIN/password", "000000")
        click("Delete permanently")
        waitFor("Authentication failed. Check your credentials or use your recovery code.")
        click("Cancel")
        showRecent("Groceries test")
        ui.onNodeWithText("Groceries test").assertExists()
        var vm: ExpensesViewModel? = null
        ui.runOnUiThread {
            vm = ViewModelProvider(ui.activity)[ExpensesViewModel::class.java]
            vm!!.lock()
        }
        waitFor("Unlock")
        ui.onNodeWithText("Groceries test").assertDoesNotExist()
        assertTrue(vm!!.state.value.profiles.isEmpty())
        assertTrue(vm!!.state.value.expenses.isEmpty())
        click("Forgot PIN/password?")
        enter("Offline recovery code", recovery)
        enter("New PIN/password", "654321")
        enter("Confirm PIN/password", "654321")
        click("Recover and unlock")
        waitFor("Add entry")
        click("Entries")
        showRecent("Groceries test")
        ui.onNode(hasScrollAction()).performScrollToIndex(4)
        ui.onNodeWithContentDescription("Delete Groceries test").performClick()
        waitFor("Delete expense permanently?")
        ui.onAllNodes(isToggleable()).onFirst().performClick()
        enter("App PIN/password", "654321")
        click("Delete permanently")
        ui.waitUntil(30_000) { vm!!.state.value.summary.total == 0L }
        showRecent("Tailoring payment")
        ui.onNode(hasScrollAction()).performScrollToIndex(4)
        click("Delete Tailoring payment")
        waitFor("Delete income permanently?")
        ui.onAllNodes(isToggleable()).onFirst().performClick()
        enter("App PIN/password", "654321")
        click("Delete permanently")
        ui.waitUntil(30_000) { vm!!.state.value.summary.income == 6000L }
        assertEquals(6000L, vm!!.state.value.summary.remaining)
        click("Profiles")
        click("Create profile")
        enter("Profile name", "Spending")
        click("Expenses only")
        click("Save")
        waitFor("Spending")
        click("Spending")
        waitFor("Add entry")
        assertFalse(vm!!.state.value.profile!!.incomeEnabled)
        ui.onNodeWithText("Total income").assertDoesNotExist()
        ui.onNodeWithText("Remaining").assertDoesNotExist()
        click("Add entry")
        waitFor("Add expense")
        ui.onNodeWithText("Income").assertDoesNotExist()
        click("Choose date")
        waitFor("Use date")
        val chosenDay = java.time.YearMonth.now().atDay(1)
        val dayLabel =
            android.icu.text.DateFormat.getInstanceForSkeleton("yMMMMEEEEd")
                .apply { timeZone = android.icu.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(chosenDay.toEpochDay() * 86_400_000L))
        try {
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText(dayLabel, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            throw AssertionError(
                "Expected calendar day $dayLabel\n${ui.onAllNodes(isRoot(), useUnmergedTree = true)[2].printToString(maxDepth = 20)}",
                e,
            )
        }
        ui.onNodeWithText(dayLabel, substring = true).performClick()
        click("Use date")
        enter("What was this expense for?", "Calendar expense")
        enter("Amount · INR", "20")
        click("Save")
        showRecent("Calendar expense")
        assertEquals(
            java.time.YearMonth.now().atDay(1).toString(),
            vm!!.state.value.recent.single().date,
        )
        ui.runOnUiThread {
            assertTrue(
                ui.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            )
            vm!!.lock()
        }
    }
}
