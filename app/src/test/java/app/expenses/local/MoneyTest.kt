package app.expenses.local

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class MoneyTest {
    @Test
    fun incomeExpensesAndRemaining() {
        val s = Money.summary(5000000, 6000000, 2, 3)
        assertEquals(-1000000L, s.remaining)
        assertEquals(3, s.incomeCount)
        assertEquals(2, s.count)
        assertEquals(0L, Money.summary(0, 0, 0).remaining)
        assertEquals(-2000L, Money.summary(0, 2000, 1).remaining)
        assertEquals(3000L, Money.summary(5000, 2000, 3).remaining)
        assertEquals(5000L, Money.summary(5000, 0, 0, 1).remaining)
    }

    @Test
    fun exactMoney() {
        assertEquals(29L, Money.parse("0.29", "INR"))
        assertEquals("0.29", Money.decimal(29, "INR"))
        assertEquals(150L, Money.parse("150", "JPY"))
        assertEquals(1234L, Money.parse("1.234", "KWD"))
        assertEquals(Money.MAX, Money.parse("90000000000.00", "INR"))
    }

    @Test
    fun invalidInputs() {
        for (input in listOf("-1", "", "NaN", "1e3", "1.001", "1,000", "90000000000.01")) {
            assertThrows(IllegalArgumentException::class.java) { Money.parse(input, "INR") }
        }
        assertThrows(IllegalArgumentException::class.java) { Money.parse("0", "INR", false) }
    }

    @Test
    fun calendarBoundaries() {
        assertEquals(YearMonth.of(2027, 1), YearMonth.of(2026, 12).plusMonths(1))
        assertEquals(29, YearMonth.of(2024, 2).lengthOfMonth())
        assertEquals(28, YearMonth.of(2025, 2).lengthOfMonth())
        assertEquals(YearMonth.of(2024, 2), YearMonth.from(LocalDate.parse("2024-02-29")))
    }

    @Test
    fun csvNeutralizesFormulasAndEscapes() {
        assertEquals("\"'=SUM(A1)\"", Exports.csvCell("=SUM(A1)"))
        assertEquals("\"A, \"\"B\"\"\"", Exports.csvCell("A, \"B\""))
    }

    @Test
    fun authenticatedEncryption() {
        val key = Crypto.bytes(32)
        val text = "private expense".toByteArray()
        val encrypted = Crypto.encrypt(key, text)
        assertArrayEquals(text, Crypto.decrypt(key, encrypted))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { Crypto.decrypt(key, encrypted) }
    }

    @Test
    fun correctAndWrongPasswords() {
        val key = Crypto.bytes(32)
        val blob = Crypto.passwordWrap("correct-password", key)
        assertArrayEquals(key, Crypto.passwordUnwrap("correct-password", blob))
        assertThrows(Exception::class.java) { Crypto.passwordUnwrap("wrong-password", blob) }
    }
}
