package app.expenses.local

import androidx.room.withTransaction
import java.io.*
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Exports {
    fun csvCell(value: String): String {
        val safe =
            if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value"
            else value
        return "\"${safe.replace("\"","\"\"")}\""
    }

    private suspend fun rows(
        repo: Repository,
        p: Profile,
        ym: YearMonth,
        emit: (List<String>) -> Unit,
    ) {
        val s = repo.summary(p.id, ym)
        emit(listOf("Money Journal — monthly report"))
        emit(listOf("Profile", p.name))
        emit(listOf("Currency", p.currency))
        emit(listOf("Month", ym.toString()))
        for ((label, value) in
            listOf(
                    "Total income" to s.income,
                    "Total expenses" to s.total,
                    "Remaining" to s.remaining,
                )
                .filter { p.incomeEnabled || it.first == "Total expenses" }) emit(
            listOf(label, Money.decimal(value, p.currency))
        )
        emit(listOf("Entry count", (s.count + s.incomeCount).toString()))
        emit(emptyList())
        emit(listOf("Date", "Type", "Amount (${p.currency})", "Description"))
        var offset = 0
        while (true) {
            val batch = repo.dao.entries(p.id, ym.toString(), limit = 250, offset = offset)
            if (batch.isEmpty()) break
            batch.forEach {
                emit(
                    listOf(
                        it.date,
                        EntryType.label(it.type),
                        Money.decimal(it.amount, p.currency),
                        it.description,
                    )
                )
            }
            offset += batch.size
        }
    }

    suspend fun csv(repo: Repository, p: Profile, ym: YearMonth, out: OutputStream) {
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("\uFEFF")
            repo.db.withTransaction {
                rows(repo, p, ym) {
                    w.write(it.joinToString(",", transform = ::csvCell))
                    w.write("\r\n")
                }
            }
        }
    }

    private fun xml(s: String) =
        s.filter { it == '\n' || it == '\r' || it == '\t' || it >= ' ' }
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    suspend fun xlsx(repo: Repository, p: Profile, ym: YearMonth, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun entry(name: String, text: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            entry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            )
            entry(
                "_rels/.rels",
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            )
            entry(
                "xl/workbook.xml",
                """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Money Journal" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            )
            entry(
                "xl/_rels/workbook.xml.rels",
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            )
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val w = OutputStreamWriter(z, Charsets.UTF_8)
            w.write(
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="1" width="24" customWidth="1"/><col min="2" max="2" width="24" customWidth="1"/><col min="3" max="3" width="24" customWidth="1"/><col min="4" max="4" width="72" customWidth="1"/></cols><sheetData>"""
            )
            var index = 0
            repo.db.withTransaction {
                rows(repo, p, ym) { row ->
                    index++
                    w.write("<row r=\"$index\">")
                    row.forEachIndexed { i, v ->
                        w.write(
                            "<c r=\"${('A'.code+i).toChar()}$index\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(v)}</t></is></c>"
                        )
                    }
                    w.write("</row>")
                }
            }
            w.write("</sheetData></worksheet>")
            w.flush()
            z.closeEntry()
        }
    }

    suspend fun pdf(repo: Repository, p: Profile, ym: YearMonth, out: OutputStream) =
        PdfReport.write(repo, p, ym, out)
}

/**
 * Versioned, authenticated portable archive. Plaintext exists only in memory, never in a temporary
 * file.
 */
object Backup {
    private val legacyMagic = "EXPENSES-BACKUP-1".toByteArray(Charsets.US_ASCII)
    private val previousMagic = "EXPENSES-BACKUP-2".toByteArray(Charsets.US_ASCII)
    private val magic = "EXPENSES-BACKUP-3".toByteArray(Charsets.US_ASCII)

    suspend fun write(repo: Repository, password: String, out: OutputStream) {
        require(password.length in 12..128) { "Backup password must contain 12–128 characters." }
        val salt = Crypto.bytes(16)
        val iv = Crypto.bytes(12)
        val key = Crypto.derive(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(magic)
        key.fill(0)
        out.write(magic)
        out.write(salt)
        out.write(iv)
        DataOutputStream(CipherOutputStream(out, cipher)).use { d ->
            repo.db.withTransaction {
                val profiles = repo.dao.profiles()
                d.writeInt(profiles.size)
                for (p in profiles) {
                    d.writeUTF(p.id)
                    d.writeUTF(p.name)
                    d.writeUTF(p.currency)
                    d.writeLong(p.createdAt)
                    d.writeLong(p.updatedAt)
                    d.writeBoolean(p.currencyFrozen)
                    d.writeBoolean(p.incomeEnabled)
                    var offset = 0
                    while (true) {
                        val batch = repo.dao.history(p.id, 250, offset)
                        for (m in batch) {
                            d.writeBoolean(true)
                            d.writeUTF(m.period)
                        }
                        offset += batch.size
                        if (batch.size < 250) break
                    }
                    d.writeBoolean(false)
                    offset = 0
                    while (true) {
                        val batch = repo.dao.entries(p.id, "", limit = 250, offset = offset)
                        for (e in batch) {
                            d.writeBoolean(true)
                            d.writeUTF(e.id)
                            d.writeLong(e.amount)
                            d.writeUTF(e.date)
                            d.writeUTF(e.description)
                            d.writeLong(e.createdAt)
                            d.writeLong(e.updatedAt)
                            d.writeUTF(e.type)
                        }
                        offset += batch.size
                        if (batch.size < 250) break
                    }
                    d.writeBoolean(false)
                }
            }
        }
    }

    suspend fun restore(repo: Repository, password: String, input: InputStream) {
        require(password.length in 12..128) { "Enter your backup password." }
        val header = DataInputStream(input)
        val prefix = ByteArray(magic.size)
        header.readFully(prefix)
        val legacy = prefix.contentEquals(legacyMagic)
        val current = prefix.contentEquals(magic)
        require(legacy || prefix.contentEquals(previousMagic) || current) {
            "This is not a supported Money Journal backup."
        }
        val salt = ByteArray(16)
        header.readFully(salt)
        val iv = ByteArray(12)
        header.readFully(iv)
        val key = Crypto.derive(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(prefix)
        key.fill(0)
        val encrypted = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(encrypted.size() + n <= 64 * 1024 * 1024) {
                "Backup exceeds the 64 MB restore limit."
            }
            encrypted.write(buffer, 0, n)
        }
        val plain = cipher.doFinal(encrypted.toByteArray())
        try {
            DataInputStream(ByteArrayInputStream(plain)).use { d ->
                repo.db.withTransaction {
                    // Any parse, validation or authentication failure rolls back the entire import.
                    repo.dao.profiles().forEach { repo.dao.deleteProfile(it.id) }
                    val count = d.readInt()
                    require(count in 0..1000) { "Invalid profile count." }
                    repeat(count) {
                        val id = d.readUTF()
                        val name = d.readUTF()
                        val currency = d.readUTF()
                        require(id.length in 1..80 && name.trim().length in 1..60)
                        java.util.Currency.getInstance(currency)
                        repo.dao.addProfile(
                            Profile(
                                id,
                                name,
                                currency,
                                d.readLong(),
                                d.readLong(),
                                d.readBoolean(),
                                if (current) d.readBoolean() else true,
                            )
                        )
                        var months = 0
                        while (d.readBoolean()) {
                            require(++months <= 100_000)
                            val ym = YearMonth.parse(d.readUTF())
                            require(ym.year in 1900..9999)
                            val salary = if (legacy && d.readBoolean()) d.readLong() else null
                            val budget = if (legacy && d.readBoolean()) d.readLong() else null
                            require(salary == null || salary in 0..Money.MAX)
                            require(budget == null || budget in 0..Money.MAX)
                            require(repo.dao.month(id, ym.toString()) == null)
                            repo.dao.ensureMonth(Month(id, ym.toString()))
                            if (salary != null && salary > 0) {
                                repo.dao.addEntry(
                                    Entry(
                                        java.util.UUID.randomUUID().toString(),
                                        id,
                                        ym.toString(),
                                        salary,
                                        ym.atDay(1).toString(),
                                        "Salary (from previous version)",
                                        type = EntryType.INCOME,
                                    )
                                )
                            }
                        }
                        var expenses = 0
                        while (d.readBoolean()) {
                            require(++expenses <= 1_000_000)
                            val eid = d.readUTF()
                            val amount = d.readLong()
                            val date = LocalDate.parse(d.readUTF())
                            val description = d.readUTF()
                            require(
                                eid.length in 1..80 &&
                                    amount in 1..Money.MAX &&
                                    date.year in 1900..9999 &&
                                    description.trim().length <= 500
                            )
                            repo.dao.addEntry(
                                Entry(
                                    eid,
                                    id,
                                    YearMonth.from(date).toString(),
                                    amount,
                                    date.toString(),
                                    description,
                                    d.readLong(),
                                    d.readLong(),
                                    if (legacy) EntryType.EXPENSE
                                    else d.readUTF().also { require(EntryType.valid(it)) },
                                )
                            )
                        }
                    }
                    require(d.read() == -1) { "Unexpected backup data." }
                }
            }
        } finally {
            plain.fill(0)
        }
    }
}
