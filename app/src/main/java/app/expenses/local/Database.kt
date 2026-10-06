package app.expenses.local

import android.content.Context
import androidx.room.*
import java.time.LocalDate
import java.time.YearMonth
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey val id: String,
    val name: String,
    val currency: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val currencyFrozen: Boolean = false,
    @ColumnInfo(defaultValue = "1") val incomeEnabled: Boolean = true,
)

@Entity(
    tableName = "months",
    primaryKeys = ["profileId", "period"],
    foreignKeys =
        [
            ForeignKey(
                entity = Profile::class,
                parentColumns = ["id"],
                childColumns = ["profileId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class Month(val profileId: String, val period: String)

@Entity(
    tableName = "entries",
    foreignKeys =
        [
            ForeignKey(
                entity = Month::class,
                parentColumns = ["profileId", "period"],
                childColumns = ["profileId", "period"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["profileId", "period", "date"]), Index(value = ["profileId", "date"])],
)
data class Entry(
    @PrimaryKey val id: String,
    val profileId: String,
    val period: String,
    val amount: Long,
    val date: String,
    val description: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val type: String = EntryType.EXPENSE,
)

object EntryType {
    const val EXPENSE = "expense"
    const val INCOME = "income"

    fun valid(type: String) = type == EXPENSE || type == INCOME

    fun label(type: String) = if (type == INCOME) "Income" else "Expense"
}

data class Aggregate(val total: Long, val count: Int, val income: Long, val incomeCount: Int)

data class HistoryRow(
    val period: String,
    val total: Long,
    val count: Int,
    val income: Long,
    val incomeCount: Int,
) {
    fun summary() = Money.summary(income, total, count, incomeCount)
}

@Dao
interface EntryDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt") suspend fun profiles(): List<Profile>

    @Insert suspend fun addProfile(p: Profile)

    @Update suspend fun updateProfile(p: Profile)

    @Query("DELETE FROM profiles WHERE id=:id") suspend fun deleteProfile(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensureMonth(m: Month)

    @Query("SELECT * FROM months WHERE profileId=:p AND period=:m")
    suspend fun month(p: String, m: String): Month?

    @Update suspend fun updateMonth(m: Month)

    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type='expense' THEN amount ELSE 0 END),0) AS total, COUNT(CASE WHEN type='expense' THEN 1 END) AS count, COALESCE(SUM(CASE WHEN type='income' THEN amount ELSE 0 END),0) AS income, COUNT(CASE WHEN type='income' THEN 1 END) AS incomeCount FROM entries WHERE profileId=:p AND period=:m"
    )
    suspend fun aggregate(p: String, m: String): Aggregate

    @Query(
        "SELECT * FROM entries WHERE profileId=:p AND (:m='' OR period=:m) AND (:date='' OR date=:date) AND (:type='' OR type=:type) AND instr(lower(description), lower(:query))>0 ORDER BY date DESC, createdAt DESC, id LIMIT :limit OFFSET :offset"
    )
    suspend fun entries(
        p: String,
        m: String,
        query: String = "",
        date: String = "",
        limit: Int = 100,
        offset: Int = 0,
        type: String = "",
    ): List<Entry>

    @Query("SELECT * FROM entries WHERE id=:id AND profileId=:p")
    suspend fun entry(p: String, id: String): Entry?

    @Insert suspend fun addEntry(e: Entry)

    @Update suspend fun updateEntry(e: Entry)

    @Query("DELETE FROM entries WHERE id=:id AND profileId=:p")
    suspend fun deleteEntry(p: String, id: String)

    @Query(
        "SELECT m.period,COALESCE(SUM(CASE WHEN e.type='expense' THEN e.amount ELSE 0 END),0) AS total,COUNT(CASE WHEN e.type='expense' THEN 1 END) AS count,COALESCE(SUM(CASE WHEN e.type='income' THEN e.amount ELSE 0 END),0) AS income,COUNT(CASE WHEN e.type='income' THEN 1 END) AS incomeCount FROM months m LEFT JOIN entries e ON m.profileId=e.profileId AND m.period=e.period WHERE m.profileId=:p GROUP BY m.profileId,m.period ORDER BY m.period DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun history(p: String, limit: Int = 120, offset: Int = 0): List<HistoryRow>

    @Query("SELECT currencyFrozen FROM profiles WHERE id=:p")
    suspend fun currencyLocked(p: String): Boolean
}

@Database(
    entities = [Profile::class, Month::class, Entry::class],
    version = 3,
    exportSchema = false,
)
abstract class ExpensesDb : RoomDatabase() {
    abstract fun dao(): EntryDao

    companion object {
        private fun triggers(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TRIGGER freeze_currency_entry AFTER INSERT ON entries BEGIN UPDATE profiles SET currencyFrozen=1 WHERE id=NEW.profileId; END"
            )
            for (operation in listOf("INSERT", "UPDATE")) {
                db.execSQL(
                    "CREATE TRIGGER valid_entry_${operation.lowercase()} BEFORE $operation ON entries WHEN NEW.type NOT IN ('expense','income') OR NEW.amount <= 0 OR NEW.amount > ${Money.MAX} OR length(NEW.description)>500 OR substr(NEW.date,1,7) <> NEW.period BEGIN SELECT RAISE(ABORT,'Invalid entry'); END"
                )
            }
        }

        val MIGRATION_1_2 =
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Keep copies without foreign keys while rebuilding the parent/child tables.
                    db.execSQL("CREATE TABLE migration_expenses AS SELECT * FROM expenses")
                    db.execSQL("CREATE TABLE migration_months AS SELECT * FROM months")
                    db.execSQL("DROP TABLE expenses")
                    db.execSQL("DROP TABLE months")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS months (profileId TEXT NOT NULL, period TEXT NOT NULL, PRIMARY KEY(profileId,period), FOREIGN KEY(profileId) REFERENCES profiles(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                    )
                    db.execSQL(
                        "INSERT INTO months (profileId,period) SELECT profileId,period FROM migration_months"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS entries (id TEXT NOT NULL PRIMARY KEY, profileId TEXT NOT NULL, period TEXT NOT NULL, amount INTEGER NOT NULL, date TEXT NOT NULL, description TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, type TEXT NOT NULL, FOREIGN KEY(profileId,period) REFERENCES months(profileId,period) ON UPDATE NO ACTION ON DELETE CASCADE)"
                    )
                    db.execSQL(
                        "CREATE INDEX index_entries_profileId_period_date ON entries(profileId,period,date)"
                    )
                    db.execSQL(
                        "CREATE INDEX index_entries_profileId_date ON entries(profileId,date)"
                    )
                    triggers(db)
                    db.execSQL(
                        "INSERT INTO entries SELECT id,profileId,period,amount,date,description,createdAt,updatedAt,'expense' FROM migration_expenses"
                    )
                    // Legacy salary has no payment date; use the first day and identify its origin.
                    db.execSQL(
                        "INSERT INTO entries SELECT lower(hex(randomblob(16))),profileId,period,salary,period || '-01','Salary (from previous version)',0,0,'income' FROM migration_months WHERE salary > 0"
                    )
                    db.execSQL("DROP TABLE migration_expenses")
                    db.execSQL("DROP TABLE migration_months")
                }
            }

        private fun profileModeTriggers(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            for (operation in listOf("INSERT", "UPDATE")) {
                db.execSQL(
                    "CREATE TRIGGER income_mode_${operation.lowercase()} BEFORE $operation ON entries WHEN NEW.type='income' AND (SELECT incomeEnabled FROM profiles WHERE id=NEW.profileId)=0 BEGIN SELECT RAISE(ABORT,'Income is disabled for this profile'); END"
                )
            }
            db.execSQL(
                "CREATE TRIGGER profile_mode_update BEFORE UPDATE OF incomeEnabled ON profiles WHEN NEW.incomeEnabled=0 AND EXISTS(SELECT 1 FROM entries WHERE profileId=NEW.id AND type='income') BEGIN SELECT RAISE(ABORT,'This profile has income entries'); END"
            )
        }

        val MIGRATION_2_3 =
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE profiles ADD COLUMN incomeEnabled INTEGER NOT NULL DEFAULT 1"
                    )
                    profileModeTriggers(db)
                }
            }

        fun open(context: Context, key: ByteArray, name: String = "expenses.db"): ExpensesDb {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, ExpensesDb::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(key.copyOf()))
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(
                    object : Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            triggers(db)
                            profileModeTriggers(db)
                        }
                    }
                )
                .build()
                .also { it.openHelper.writableDatabase }
        }
    }
}

class Repository(val db: ExpensesDb) {
    val dao = db.dao()

    suspend fun month(p: String, ym: YearMonth): Month {
        dao.ensureMonth(Month(p, ym.toString()))
        return requireNotNull(dao.month(p, ym.toString()))
    }

    suspend fun summary(p: String, ym: YearMonth): Summary {
        month(p, ym)
        val a = dao.aggregate(p, ym.toString())
        return Money.summary(a.income, a.total, a.count, a.incomeCount)
    }

    suspend fun saveEntry(
        p: String,
        id: String?,
        amount: Long,
        date: LocalDate,
        description: String,
        type: String = EntryType.EXPENSE,
    ) =
        db.withTransaction {
            require(amount in 1..Money.MAX) { "Enter a valid positive amount." }
            require(description.trim().length <= 500) {
                "Description must contain at most 500 characters."
            }
            require(EntryType.valid(type)) { "Choose Expense or Income." }
            val profile = dao.profiles().first { it.id == p }
            require(profile.incomeEnabled || type == EntryType.EXPENSE) {
                "This profile tracks expenses only."
            }
            require(date.year in 1900..9999) { "Choose a date between 1900 and 9999." }
            val period = YearMonth.from(date).toString()
            dao.ensureMonth(Month(p, period))
            val old = id?.let { requireNotNull(dao.entry(p, it)) { "Entry no longer exists." } }
            val e =
                Entry(
                    old?.id ?: java.util.UUID.randomUUID().toString(),
                    p,
                    period,
                    amount,
                    date.toString(),
                    description.trim(),
                    old?.createdAt ?: System.currentTimeMillis(),
                    type = type,
                )
            if (old == null) dao.addEntry(e) else dao.updateEntry(e)
        }

    suspend fun updateProfile(p: Profile) =
        db.withTransaction {
            require(p.name.trim().length in 1..60) { "Profile name must contain 1–60 characters." }
            java.util.Currency.getInstance(p.currency)
            val old = dao.profiles().first { it.id == p.id }
            require(old.currency == p.currency || !dao.currencyLocked(p.id)) {
                "Currency cannot change after financial data has been entered."
            }
            require(
                p.incomeEnabled ||
                    dao.entries(p.id, "", limit = 1, type = EntryType.INCOME).isEmpty()
            ) {
                "This profile already has income entries. Keep Income & expenses enabled."
            }
            dao.updateProfile(
                p.copy(
                    name = p.name.trim(),
                    updatedAt = System.currentTimeMillis(),
                    currencyFrozen = old.currencyFrozen,
                )
            )
        }
}
