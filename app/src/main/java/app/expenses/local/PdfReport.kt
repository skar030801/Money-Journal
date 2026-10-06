package app.expenses.local

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.room.withTransaction
import java.io.OutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/** A paginated A4 statement. All measurements are PDF points, independent of phone size. */
object PdfReport {
    suspend fun write(repo: Repository, profile: Profile, month: YearMonth, out: OutputStream) {
        val document = PdfDocument()
        val ink = Color.rgb(23, 48, 52)
        val muted = Color.rgb(88, 108, 110)
        val green = Color.rgb(20, 107, 83)
        val red = Color.rgb(157, 57, 43)
        val pale = Color.rgb(237, 247, 243)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var page: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var pageNumber = 0
        var y = 0f

        fun text(
            value: String,
            x: Float,
            baseline: Float,
            size: Float = 11f,
            color: Int = ink,
            bold: Boolean = false,
            right: Boolean = false,
            width: Float = 1000f,
        ) {
            paint.color = color
            paint.typeface =
                if (bold) Typeface.create("sans-serif", Typeface.BOLD)
                else Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = size
            while (paint.measureText(value) > width && paint.textSize > 8f) paint.textSize -= .25f
            paint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.drawText(value, x, baseline, paint)
        }

        fun box(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            color: Int,
            radius: Float = 0f,
        ) {
            paint.color = color
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        }

        fun layout(value: String, width: Int, size: Float, color: Int = ink): StaticLayout {
            val p =
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size
                    this.color = color
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                }
            return StaticLayout.Builder.obtain(value, 0, value.length, p, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(3f, 1f)
                .build()
        }

        fun drawLayout(block: StaticLayout, left: Float, top: Float) {
            canvas.save()
            canvas.translate(left, top)
            block.draw(canvas)
            canvas.restore()
        }

        fun startPage(first: Boolean) {
            page?.let(document::finishPage)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            canvas = page!!.canvas
            box(0f, 0f, 595f, 7f, green)
            text("MONEY JOURNAL", 36f, 35f, 10f, green, true)
            text(
                if (first) "Monthly statement" else "Transactions continued",
                36f,
                65f,
                23f,
                ink,
                true,
            )
            text(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)),
                36f,
                89f,
                13f,
                muted,
            )
            text(profile.currency, 559f, 89f, 11f, muted, right = true)
            box(36f, 794f, 559f, 795f, Color.rgb(214, 226, 222))
            text("Money Journal | Private monthly report", 36f, 812f, 9f, muted)
            text("Page $pageNumber", 559f, 812f, 9f, muted, right = true)
            y = 110f
        }

        fun tableHeader() {
            box(36f, y, 559f, y + 27f, ink, 4f)
            text("Date", 46f, y + 18f, 9f, Color.WHITE, true)
            text("Type", 116f, y + 18f, 9f, Color.WHITE, true)
            text("Description", 180f, y + 18f, 9f, Color.WHITE, true)
            text("Amount (${profile.currency})", 549f, y + 18f, 9f, Color.WHITE, true, true)
            y += 27f
        }

        try {
            repo.db.withTransaction {
                val summary = repo.summary(profile.id, month)
                startPage(true)
                val name = layout(profile.name.replace(Regex("\\s+"), " "), 523, 13f)
                drawLayout(name, 36f, y)
                y += name.height + 20f
                listOf(
                        "Total income" to summary.income,
                        "Total expenses" to summary.total,
                        "Remaining" to summary.remaining,
                    )
                    .filter { profile.incomeEnabled || it.first == "Total expenses" }
                    .forEachIndexed { i, (label, amount) ->
                        val left = 36f + i * 178f
                        box(left, y, left + (if (profile.incomeEnabled) 167f else 523f), y + 75f, pale, 7f)
                        text(label, left + 12f, y + 23f, 10f, muted)
                        text(
                            Money.format(amount, profile.currency),
                            left + 12f,
                            y + 52f,
                            17f,
                            if (amount < 0) red else if (i == 0) green else ink,
                            true,
                            width = 143f,
                        )
                    }
                y += 95f
                text(
                    if (profile.incomeEnabled) "Remaining = income minus expenses for this month."
                    else "Expenses-only profile",
                    36f,
                    y,
                    10f,
                    muted,
                )
                y += 19f
                text(
                    (if (profile.incomeEnabled) "${summary.incomeCount} income payments  |  "
                    else "") + "${summary.count} expenses  |  Newest first",
                    36f,
                    y,
                    10f,
                    muted,
                )
                y += 17f
                tableHeader()
                var offset = 0
                while (true) {
                    val batch =
                        repo.dao.entries(profile.id, month.toString(), limit = 250, offset = offset)
                    if (batch.isEmpty()) break
                    for ((index, entry) in batch.withIndex()) {
                        val description =
                            entry.description.trim().replace(Regex("\\s+"), " ").ifBlank {
                                "No description"
                            }
                        val block = layout(description, 230, 10.5f)
                        val height = max(39f, block.height + 22f)
                        if (y + height > 778f) {
                            startPage(false)
                            tableHeader()
                        }
                        if ((offset + index) % 2 == 0)
                            box(36f, y, 559f, y + height, Color.rgb(247, 250, 249))
                        val typeColor = if (entry.type == EntryType.INCOME) green else red
                        text(
                            LocalDate.parse(entry.date)
                                .format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)),
                            46f,
                            y + 23f,
                            9f,
                            muted,
                            width = 65f,
                        )
                        text(EntryType.label(entry.type), 116f, y + 23f, 9.5f, typeColor, true)
                        drawLayout(block, 180f, y + 12f)
                        text(
                            Money.format(entry.amount, profile.currency),
                            549f,
                            y + 23f,
                            10.5f,
                            typeColor,
                            true,
                            true,
                            126f,
                        )
                        y += height
                        box(36f, y - .5f, 559f, y, Color.rgb(225, 233, 230))
                    }
                    offset += batch.size
                }
                if (offset == 0)
                    text(if (profile.incomeEnabled) "No income or expenses recorded for this month." else "No expenses recorded for this month.", 46f, y + 29f, 11f, muted)
                page?.let(document::finishPage)
                page = null
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }
}
