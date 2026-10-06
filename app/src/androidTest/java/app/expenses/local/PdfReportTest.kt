package app.expenses.local

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfReportTest {
    @Test
    fun readableStatementsWithPaginationLongNotesAndEmptyMonths() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pdf-${UUID.randomUUID()}.db"
        val db = ExpensesDb.open(context, Crypto.bytes(32), name)
        try {
            val repo = Repository(db)
            val p = Profile("pdf", "Family income and expenses", "INR")
            repo.dao.addProfile(p)
            val month = YearMonth.of(2026, 9)
            repo.saveEntry(
                p.id,
                null,
                1500000,
                month.atDay(28),
                "Tuition fees - September classes",
                EntryType.INCOME,
            )
            repo.saveEntry(
                p.id,
                null,
                350000,
                month.atDay(25),
                "Tailoring - alterations and blouse stitching",
                EntryType.INCOME,
            )
            repo.saveEntry(p.id, null, 248050, month.atDay(24), "Groceries and household supplies")
            repo.saveEntry(
                p.id,
                null,
                90000,
                month.atDay(23),
                "Fabric and thread for customer orders",
            )
            val sample = File(context.getExternalFilesDir(null), "Money-Journal-sample.pdf")
            sample.outputStream().use { Exports.pdf(repo, p, month, it) }
            repeat(38) { i ->
                repo.saveEntry(
                    p.id,
                    null,
                    12500L + i,
                    month.atDay(i % 22 + 1),
                    if (i == 20)
                        "Long note: " +
                            "Customer order details with measurements and delivery instructions. "
                                .repeat(7)
                    else
                        "Entry ${i + 1} - ${if (i % 3 == 0) "Tuition payment" else "Everyday purchase"}",
                    if (i % 3 == 0) EntryType.INCOME else EntryType.EXPENSE,
                )
            }
            repo.saveEntry(
                p.id,
                null,
                Money.MAX,
                month.atDay(22),
                "Large amount formatting check",
                EntryType.INCOME,
            )
            val long = File(context.getExternalFilesDir(null), "Money-Journal-multipage.pdf")
            long.outputStream().use { Exports.pdf(repo, p, month, it) }
            PdfRenderer(ParcelFileDescriptor.open(long, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                assertTrue(it.pageCount >= 3)
                for (index in 0 until it.pageCount) it.openPage(index).use { page ->
                    assertEquals(595, page.width)
                    assertEquals(842, page.height)
                }
            }
            val empty = File(context.getExternalFilesDir(null), "Money-Journal-empty.pdf")
            empty.outputStream().use { Exports.pdf(repo, p, month.plusMonths(1), it) }
            PdfRenderer(ParcelFileDescriptor.open(empty, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                assertEquals(1, it.pageCount)
            }
            val only = Profile("only-pdf", "Personal spending", "INR", incomeEnabled = false)
            repo.dao.addProfile(only)
            repo.saveEntry(only.id, null, 55000, month.atDay(1), "Groceries")
            File(context.getExternalFilesDir(null), "Money-Journal-expenses-only.pdf").outputStream().use {
                Exports.pdf(repo, only, month, it)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
