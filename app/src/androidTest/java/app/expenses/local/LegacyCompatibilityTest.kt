package app.expenses.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.YearMonth
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyCompatibilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private inline fun <T> ExpensesDb.useDb(block: (ExpensesDb) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    @Test
    fun encryptedVersionOneDatabaseMigratesWithoutLosingRecords() = runBlocking {
        System.loadLibrary("sqlcipher")
        val name = "migration-${UUID.randomUUID()}.db"
        val key = Crypto.bytes(32)
        val config =
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            db.setForeignKeyConstraintsEnabled(true)
                        }

                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE profiles (id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,currency TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,currencyFrozen INTEGER NOT NULL)"
                            )
                            db.execSQL(
                                "CREATE TABLE months (profileId TEXT NOT NULL,period TEXT NOT NULL,salary INTEGER,budget INTEGER,PRIMARY KEY(profileId,period),FOREIGN KEY(profileId) REFERENCES profiles(id) ON DELETE CASCADE)"
                            )
                            db.execSQL(
                                "CREATE TABLE expenses (id TEXT NOT NULL PRIMARY KEY,profileId TEXT NOT NULL,period TEXT NOT NULL,amount INTEGER NOT NULL,date TEXT NOT NULL,description TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,FOREIGN KEY(profileId,period) REFERENCES months(profileId,period) ON DELETE CASCADE)"
                            )
                            db.execSQL(
                                "CREATE INDEX index_expenses_profileId_period_date ON expenses(profileId,period,date)"
                            )
                            db.execSQL(
                                "CREATE INDEX index_expenses_profileId_date ON expenses(profileId,date)"
                            )
                            db.execSQL(
                                "CREATE TRIGGER freeze_currency_expense AFTER INSERT ON expenses BEGIN UPDATE profiles SET currencyFrozen=1 WHERE id=NEW.profileId; END"
                            )
                            db.execSQL(
                                "CREATE TRIGGER freeze_currency_month_insert AFTER INSERT ON months WHEN NEW.salary IS NOT NULL OR NEW.budget IS NOT NULL BEGIN UPDATE profiles SET currencyFrozen=1 WHERE id=NEW.profileId; END"
                            )
                            db.execSQL(
                                "CREATE TRIGGER freeze_currency_month_update AFTER UPDATE ON months WHEN NEW.salary IS NOT NULL OR NEW.budget IS NOT NULL BEGIN UPDATE profiles SET currencyFrozen=1 WHERE id=NEW.profileId; END"
                            )
                            db.execSQL(
                                "INSERT INTO profiles VALUES ('a','Personal','INR',123,456,0),('b','Other','USD',124,457,0)"
                            )
                            db.execSQL(
                                "INSERT INTO months VALUES ('a','2026-09',5000,1000),('a','2026-08',0,NULL),('b','2026-09',NULL,500)"
                            )
                            db.execSQL(
                                "INSERT INTO expenses VALUES ('original','a','2026-09',1234,'2026-09-12','Groceries',111,222)"
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) =
                            error("Unexpected upgrade")
                    }
                )
                .build()
        val legacy = SupportOpenHelperFactory(key.copyOf()).create(config)
        legacy.writableDatabase
        legacy.close()
        try {
            ExpensesDb.open(context, key, name).useDb { db ->
                val repo = Repository(db)
                val ym = YearMonth.of(2026, 9)
                val s = repo.summary("a", ym)
                assertEquals(5000L, s.income)
                assertEquals(1234L, s.total)
                assertEquals(3766L, s.remaining)
                val original = repo.dao.entry("a", "original")!!
                assertEquals(EntryType.EXPENSE, original.type)
                assertEquals(111L, original.createdAt)
                assertEquals(222L, original.updatedAt)
                assertEquals("2026-09-12", original.date)
                assertEquals(
                    "2026-09-01",
                    repo.dao.entries("a", "", type = EntryType.INCOME).single().date,
                )
                assertEquals(0L, repo.summary("b", ym).income)
                assertEquals(0, repo.summary("a", ym.minusMonths(1)).incomeCount)
                assertTrue(repo.dao.currencyLocked("b"))
                assertEquals(2, repo.dao.history("a").size)
                db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                    assertFalse(it.moveToFirst())
                }
                db.openHelper.writableDatabase.query("PRAGMA table_info(months)").use { c ->
                    val columns = mutableListOf<String>()
                    while (c.moveToNext()) columns.add(c.getString(c.getColumnIndexOrThrow("name")))
                    assertEquals(listOf("profileId", "period"), columns)
                }
            }
            // Reopening must not import the same salary a second time.
            ExpensesDb.open(context, key, name).useDb { db ->
                assertEquals(2, db.dao().entries("a", "").size)
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

    private fun archive(version: Int, invalidType: Boolean = false): ByteArray {
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { d ->
            d.writeInt(1)
            d.writeUTF("legacy-profile")
            d.writeUTF("Tailoring")
            d.writeUTF("INR")
            d.writeLong(1)
            d.writeLong(2)
            d.writeBoolean(true)
            d.writeBoolean(true)
            d.writeUTF("2026-09")
            if (version == 1) {
                d.writeBoolean(true)
                d.writeLong(5000)
                d.writeBoolean(true)
                d.writeLong(1000)
            }
            d.writeBoolean(false)
            d.writeBoolean(true)
            d.writeUTF("old-expense")
            d.writeLong(1234)
            d.writeUTF("2026-09-12")
            d.writeUTF("Fabric")
            d.writeLong(3)
            d.writeLong(4)
            if (version == 2) d.writeUTF(if (invalidType) "savings" else EntryType.INCOME)
            d.writeBoolean(false)
        }
        val magic = "EXPENSES-BACKUP-$version".toByteArray(Charsets.US_ASCII)
        val salt = Crypto.bytes(16)
        val iv = Crypto.bytes(12)
        val key = Crypto.derive("legacy-backup-password", salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(magic)
        key.fill(0)
        return magic + salt + iv + cipher.doFinal(plain.toByteArray())
    }

    @Test
    fun oldBackupConvertsSalaryAndMalformedNewBackupRollsBack() = runBlocking {
        val name = "legacy-backup-${UUID.randomUUID()}.db"
        val db = ExpensesDb.open(context, Crypto.bytes(32), name)
        try {
            val repo = Repository(db)
            Backup.restore(repo, "legacy-backup-password", ByteArrayInputStream(archive(1)))
            val ym = YearMonth.of(2026, 9)
            assertEquals(3766L, repo.summary("legacy-profile", ym).remaining)
            assertEquals(EntryType.EXPENSE, repo.dao.entry("legacy-profile", "old-expense")!!.type)
            val income = repo.dao.entries("legacy-profile", "", type = EntryType.INCOME).single()
            assertEquals("2026-09-01", income.date)
            assertEquals(5000L, income.amount)
            try {
                Backup.restore(
                    repo,
                    "legacy-backup-password",
                    ByteArrayInputStream(archive(2, true)),
                )
                fail("Authenticated but invalid data accepted")
            } catch (_: IllegalArgumentException) {}
            assertEquals(3766L, repo.summary("legacy-profile", ym).remaining)
            assertNotNull(repo.dao.entry("legacy-profile", income.id))
            Backup.restore(repo, "legacy-backup-password", ByteArrayInputStream(archive(2)))
            assertTrue(repo.dao.profiles().single().incomeEnabled)
            assertEquals(1234L, repo.summary("legacy-profile", ym).income)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
