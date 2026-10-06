package app.expenses.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ExpensesDb
    private lateinit var repo: Repository
    private lateinit var name: String

    @Test
    fun expensesOnlyModeIsEnforcedAndBackedUp() = runBlocking {
        val p = Profile("only", "Spending", "INR", incomeEnabled = false)
        repo.dao.addProfile(p)
        val date = LocalDate.of(2026, 9, 1)
        repo.saveEntry(p.id, null, 2500, date, "Groceries")
        try {
            repo.saveEntry(p.id, null, 1000, date, "Payment", EntryType.INCOME)
            fail("Income accepted in expense-only mode")
        } catch (_: IllegalArgumentException) {}
        val csv = ByteArrayOutputStream()
        Exports.csv(repo, p, YearMonth.from(date), csv)
        assertFalse(csv.toString("UTF-8").contains("Total income"))
        assertFalse(csv.toString("UTF-8").contains("Remaining"))
        val backup = ByteArrayOutputStream()
        Backup.write(repo, "profile-mode-password", backup)
        repo.dao.deleteProfile(p.id)
        Backup.restore(repo, "profile-mode-password", ByteArrayInputStream(backup.toByteArray()))
        assertFalse(repo.dao.profiles().single().incomeEnabled)
        assertEquals(2500L, repo.summary(p.id, YearMonth.from(date)).total)
        try {
            repo.dao.addEntry(Entry("illegal", p.id, "2026-09", 1000, date.toString(), "Payment", type = EntryType.INCOME))
            fail("Database accepted forbidden income")
        } catch (_: Exception) {}
        repo.updateProfile(repo.dao.profiles().single().copy(incomeEnabled = true))
        repo.saveEntry(p.id, null, 3000, date, "Payment", EntryType.INCOME)
        try {
            repo.updateProfile(repo.dao.profiles().single().copy(incomeEnabled = false))
            fail("Existing income was hidden")
        } catch (_: IllegalArgumentException) {}
        assertTrue(repo.dao.profiles().single().incomeEnabled)
    }

    @Before
    fun before() {
        name = "test-${UUID.randomUUID()}.db"
        db = ExpensesDb.open(context, Crypto.bytes(32), name)
        repo = Repository(db)
    }

    @After
    fun after() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun isolationCrudHistoryAndEncryption() = runBlocking {
        repo.dao.addProfile(Profile("a", "Alpha", "INR"))
        repo.dao.addProfile(Profile("b", "Beta", "USD"))
        repo.saveEntry("a", null, 29, LocalDate.of(2024, 2, 29), "Groceries")
        val expense = repo.dao.entries("a", "2024-02").single()
        assertTrue(repo.dao.entries("b", "").isEmpty())
        repo.month("a", YearMonth.of(2024, 2))
        repo.month("a", YearMonth.of(2024, 2))
        assertEquals(1, repo.dao.history("a").size)
        repo.saveEntry("a", expense.id, 45, LocalDate.of(2025, 1, 1), "Changed")
        assertEquals(0, repo.dao.entries("a", "2024-02").size)
        assertEquals(45L, repo.summary("a", YearMonth.of(2025, 1)).total)
        assertFalse(
            context
                .getDatabasePath(name)
                .readBytes()
                .take(16)
                .toByteArray()
                .toString(Charsets.US_ASCII)
                .startsWith("SQLite format")
        )
        var blocked = false
        try {
            repo.updateProfile(repo.dao.profiles().first().copy(currency = "EUR"))
        } catch (e: IllegalArgumentException) {
            blocked = true
        }
        assertTrue(blocked)
        repo.dao.deleteEntry("b", expense.id)
        assertEquals(1, repo.dao.entries("a", "").size)
        repo.dao.deleteEntry("a", expense.id)
        assertTrue(repo.dao.entries("a", "").isEmpty())
    }

    @Test
    fun backupRoundTripTamperRollbackAndExports() = runBlocking {
        val p = Profile("a", "Personal", "INR")
        repo.dao.addProfile(p)
        repo.saveEntry("a", null, 1234, LocalDate.of(2026, 9, 1), "Test \"groceries\" ₹")
        repo.saveEntry(
            "a",
            null,
            5000,
            LocalDate.of(2026, 9, 8),
            "Tuition payment",
            EntryType.INCOME,
        )
        repo.saveEntry("a", null, 2500, LocalDate.of(2026, 9, 20), "Tailoring", EntryType.INCOME)
        val ym = YearMonth.of(2026, 9)
        val bytes = ByteArrayOutputStream()
        Backup.write(repo, "long-backup-password", bytes)
        val archive = bytes.toByteArray()
        repo.dao.addProfile(Profile("b", "Preserved", "USD"))
        val corrupt = archive.copyOf()
        corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
        try {
            Backup.restore(repo, "long-backup-password", ByteArrayInputStream(corrupt))
            fail("Tampering accepted")
        } catch (e: Exception) {}
        assertEquals(2, repo.dao.profiles().size)
        Backup.restore(repo, "long-backup-password", ByteArrayInputStream(archive))
        assertEquals(1, repo.dao.profiles().size)
        assertEquals(1234L, repo.summary("a", ym).total)
        assertEquals(7500L, repo.summary("a", ym).income)
        assertEquals(6266L, repo.summary("a", ym).remaining)
        assertEquals(2, repo.summary("a", ym).incomeCount)
        val csv = ByteArrayOutputStream()
        Exports.csv(repo, p, ym, csv)
        assertTrue(csv.toString("UTF-8").contains("12.34"))
        assertTrue(csv.toString("UTF-8").contains("\"Income\",\"50.00\",\"Tuition payment\""))
        assertTrue(csv.toString("UTF-8").contains("\"Remaining\",\"62.66\""))
        assertFalse(csv.toString("UTF-8").contains("Budget"))
        val pdf = ByteArrayOutputStream()
        Exports.pdf(repo, p, ym, pdf)
        assertTrue(pdf.toByteArray().take(4).toByteArray().toString(Charsets.US_ASCII) == "%PDF")
        val xlsx = ByteArrayOutputStream()
        Exports.xlsx(repo, p, ym, xlsx)
        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(xlsx.toByteArray())).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                entries.add(entry.name)
                if (entry.name == "xl/worksheets/sheet1.xml") {
                    val sheet = z.readBytes().toString(Charsets.UTF_8)
                    assertTrue(sheet.contains("Tuition payment"))
                    assertTrue(sheet.contains("Income"))
                    assertTrue(sheet.contains("62.66"))
                    assertFalse(sheet.contains("Budget"))
                }
            }
        }
        assertTrue(entries.contains("xl/worksheets/sheet1.xml"))
    }

    @Test
    fun incomeEditingFilteringAndProfileIsolation() = runBlocking {
        repo.dao.addProfile(Profile("a", "Tuition", "INR"))
        repo.dao.addProfile(Profile("b", "Tailoring", "INR"))
        val ym = YearMonth.of(2026, 9)
        repo.saveEntry("a", null, 20000, ym.atDay(3), "First payment", EntryType.INCOME)
        repo.saveEntry("a", null, 30000, ym.atDay(22), "Second payment", EntryType.INCOME)
        repo.saveEntry("a", null, 5000, ym.atDay(4), "", EntryType.EXPENSE)
        repo.saveEntry("b", null, 9000, ym.atDay(9), "Sewing", EntryType.INCOME)
        assertEquals(45000L, repo.summary("a", ym).remaining)
        assertEquals(9000L, repo.summary("b", ym).remaining)
        val incomes = repo.dao.entries("a", ym.toString(), type = EntryType.INCOME)
        assertEquals(2, incomes.size)
        assertEquals("Second payment", incomes.first().description)
        assertEquals(
            1,
            repo.dao
                .entries("a", "", query = "first", date = "2026-09-03", type = EntryType.INCOME)
                .size,
        )
        assertEquals(1, repo.dao.entries("a", "", type = EntryType.EXPENSE).size)
        assertTrue(repo.dao.currencyLocked("b"))
        // Changing type and month removes the old income and adds an October expense.
        repo.saveEntry(
            "a",
            incomes.first().id,
            1000,
            ym.plusMonths(1).atDay(1),
            "Corrected",
            EntryType.EXPENSE,
        )
        assertEquals(20000L, repo.summary("a", ym).income)
        assertEquals(-1000L, repo.summary("a", ym.plusMonths(1)).remaining)
        assertEquals(
            repo.summary("a", ym),
            repo.dao.history("a").first { it.period == ym.toString() }.summary(),
        )
        repo.dao.deleteEntry("b", incomes.last().id)
        assertEquals(20000L, repo.summary("a", ym).income)
        repo.dao.deleteEntry("a", incomes.last().id)
        assertEquals(0L, repo.summary("a", ym).income)
        try {
            repo.saveEntry("a", null, 1, ym.atDay(1), "Invalid", "savings")
            fail("Unsupported entry type accepted")
        } catch (_: IllegalArgumentException) {}
        repo.dao.deleteProfile("a")
        assertTrue(repo.dao.entries("a", "").isEmpty())
        assertEquals(9000L, repo.summary("b", ym).income)
    }
}
