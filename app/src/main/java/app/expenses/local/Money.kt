package app.expenses.local

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object Money {
    const val MAX = 9_000_000_000_000L

    fun scale(currency: String) =
        Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0)

    fun parse(text: String, currency: String, allowZero: Boolean = true): Long {
        require(text.matches(Regex("[0-9]+(\\.[0-9]+)?"))) {
            "Enter a valid amount without commas."
        }
        val value =
            try {
                BigDecimal(text)
                    .movePointRight(scale(currency))
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .longValueExact()
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException(
                    "Amount is too large or has too many decimal places."
                )
            }
        require(value in (if (allowZero) 0L else 1L)..MAX) {
            "Enter an amount ${if (allowZero) "at least zero" else "greater than zero"} within the supported limit."
        }
        return value
    }

    fun decimal(value: Long, currency: String) =
        BigDecimal.valueOf(value, scale(currency)).toPlainString()

    fun format(value: Long, currency: String): String =
        NumberFormat.getCurrencyInstance(
                if (currency == "INR") Locale("en", "IN") else Locale.getDefault()
            )
            .apply {
                this.currency = Currency.getInstance(currency)
                maximumFractionDigits = scale(currency)
                minimumFractionDigits = scale(currency)
            }
            .format(BigDecimal.valueOf(value, scale(currency)))

    fun summary(income: Long, total: Long, count: Int, incomeCount: Int = 0) =
        Summary(income, total, Math.subtractExact(income, total), count, incomeCount)
}

data class Summary(
    val income: Long,
    val total: Long,
    val remaining: Long,
    val count: Int,
    val incomeCount: Int,
)
