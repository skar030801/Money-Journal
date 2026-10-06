package app.expenses.local

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.crypto.Cipher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppState(
    val configured: Boolean = false,
    val locked: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val dark: Boolean = true,
    val profiles: List<Profile> = emptyList(),
    val profile: Profile? = null,
    val month: YearMonth = YearMonth.now(),
    val summary: Summary = Money.summary(0, 0, 0),
    val expenses: List<Entry> = emptyList(),
    val recent: List<Entry> = emptyList(),
    val history: List<HistoryRow> = emptyList(),
    val query: String = "",
    val date: String = "",
    val allMonths: Boolean = false,
    val entryType: String = "",
    val more: Boolean = false,
    val recovery: String? = null,
    val biometric: Boolean = false,
    val historyMore: Boolean = false,
)

class ExpensesViewModel(app: Application) : AndroidViewModel(app) {
    val security = Security(app)
    private val prefs = app.getSharedPreferences("appearance", 0)
    private val drafts = app.getSharedPreferences("drafts", 0)
    private val mutable =
        MutableStateFlow(
            AppState(
                configured = security.configured,
                dark = prefs.getBoolean("dark", true),
                biometric = security.biometricEnabled,
            )
        )
    val state = mutable.asStateFlow()
    private val stateGuard = Any()

    private fun publish(ticket: Int, change: (AppState) -> AppState) {
        synchronized(stateGuard) { if (ticket == generation) mutable.value = change(mutable.value) }
    }

    fun draft(name: String): List<String>? =
        master?.let { key ->
            drafts.getString(name, null)?.let { encoded ->
                try {
                    val plain =
                        Crypto.decrypt(
                            key,
                            android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP),
                        )
                    try {
                        val json = org.json.JSONArray(plain.toString(Charsets.UTF_8))
                        List(json.length()) { json.getString(it) }
                    } finally {
                        plain.fill(0)
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }

    fun saveDraft(name: String, values: List<String>) {
        master?.let { key ->
            val bytes = org.json.JSONArray(values).toString().toByteArray()
            try {
                drafts
                    .edit()
                    .putString(
                        name,
                        android.util.Base64.encodeToString(
                            Crypto.encrypt(key, bytes),
                            android.util.Base64.NO_WRAP,
                        ),
                    )
                    .apply()
            } finally {
                bytes.fill(0)
            }
        }
    }

    fun clearDraft(name: String) {
        drafts.edit().remove(name).apply()
    }

    private val mutex = Mutex()
    private var repo: Repository? = null
    private var master: ByteArray? = null
    private var generation = 0
    @Volatile private var selected: String? = null
    @Volatile private var period = YearMonth.now()
    @Volatile private var selectionVersion = 0
    private var wasCurrent = true
    private var searchJob: Job? = null
    private var activeTicket = 0

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                onResume()
            }
        }
    }

    fun clearMessage() {
        mutable.value = mutable.value.copy(error = null, notice = null)
    }

    fun error(message: String) {
        mutable.value = mutable.value.copy(error = message, busy = false)
    }

    private fun task(requireUnlock: Boolean = true, block: suspend () -> Unit) {
        if (requireUnlock && mutable.value.locked) return
        val ticket = generation
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null)
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (ticket != generation) return@withLock
                    activeTicket = ticket
                    if (requireUnlock) check(master != null)
                    block()
                } catch (e: Exception) {
                    if (ticket == generation)
                        mutable.value =
                            mutable.value.copy(
                                error =
                                    if (e is IllegalArgumentException)
                                        e.message ?: "Check your input."
                                    else
                                        "Operation failed. Your saved data was not discarded. Please try again."
                            )
                } finally {
                    if (ticket == generation) mutable.value = mutable.value.copy(busy = false)
                }
            }
        }
    }

    private suspend fun open(key: ByteArray) {
        val ticket = activeTicket
        val database = ExpensesDb.open(getApplication(), key)
        synchronized(stateGuard) {
            if (ticket != generation) {
                database.close()
                key.fill(0)
                return
            }
            master?.fill(0)
            master = key
            repo = Repository(database)
            mutable.value =
                mutable.value.copy(
                    configured = true,
                    locked = false,
                    biometric = security.biometricEnabled,
                    recovery = security.pendingRecovery(key),
                )
        }
        refresh(ticket)
    }

    fun setup(password: String, confirm: String) {
        task(false) {
            require(password == confirm) { "Passwords do not match." }
            val (key, _) = security.setup(password)
            open(key)
        }
    }

    fun acknowledgeRecovery() {
        security.acknowledgeRecovery()
        mutable.value = mutable.value.copy(recovery = null)
    }

    fun unlock(password: String) {
        task(false) { open(security.unlock(password)) }
    }

    fun recover(code: String, password: String, confirm: String) {
        task(false) {
            require(password == confirm) { "Passwords do not match." }
            open(security.recover(code, password))
        }
    }

    fun lock() {
        synchronized(stateGuard) {
            generation++
            searchJob?.cancel()
            val old = mutable.value
            mutable.value =
                AppState(
                    configured = security.configured,
                    dark = old.dark,
                    biometric = security.biometricEnabled,
                )
        }
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                repo?.db?.close()
                repo = null
                master?.fill(0)
                master = null
            }
        }
    }

    fun onResume() {
        if (wasCurrent && period != YearMonth.now()) {
            period = YearMonth.now()
            if (!state.value.locked) task { refresh() }
        }
    }

    private suspend fun refresh(
        ticket: Int = activeTicket,
        limit: Int = 100,
        historyLimit: Int = 120,
    ) {
        val r = repo ?: return
        val selection = selectionVersion
        val month = period
        val profiles = r.dao.profiles()
        val p = profiles.firstOrNull { it.id == selected } ?: profiles.firstOrNull()
        selected = p?.id
        if (p == null) {
            publish(ticket) {
                it.copy(
                    profiles = profiles,
                    profile = null,
                    expenses = emptyList(),
                    history = emptyList(),
                )
            }
            return
        }
        val snapshot = mutable.value
        val s = r.summary(p.id, month)
        val recent = r.dao.entries(p.id, month.toString(), limit = 5)
        val rows =
            r.dao.entries(
                p.id,
                if (snapshot.allMonths) "" else month.toString(),
                snapshot.query,
                snapshot.date,
                limit + 1,
                type = if (p.incomeEnabled) snapshot.entryType else EntryType.EXPENSE,
            )
        val history = r.dao.history(p.id, historyLimit + 1)
        publish(ticket) {
            if (selection != selectionVersion) it
            else
                it.copy(
                    profiles = profiles,
                    profile = p,
                    entryType = if (p.incomeEnabled) snapshot.entryType else "",
                    month = month,
                    summary = s,
                    expenses = rows.take(limit),
                    recent = recent,
                    more = rows.size > limit,
                    history = history.take(historyLimit),
                    historyMore = history.size > historyLimit,
                )
        }
    }

    fun selectProfile(id: String) {
        selectionVersion++
        selected = id
        mutable.value =
            mutable.value.copy(
                profile = null,
                expenses = emptyList(),
                history = emptyList(),
                query = "",
                date = "",
            )
        task { refresh() }
    }

    fun selectMonth(ym: YearMonth) {
        if (ym.year !in 1900..9999) return
        selectionVersion++
        period = ym
        wasCurrent = ym == YearMonth.now()
        mutable.value = mutable.value.copy(query = "", date = "", allMonths = false)
        task { refresh() }
    }

    fun filter(
        query: String = state.value.query,
        date: String = state.value.date,
        all: Boolean = state.value.allMonths,
        type: String = state.value.entryType,
    ) {
        selectionVersion++
        mutable.value =
            mutable.value.copy(query = query, date = date, allMonths = all, entryType = type)
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                delay(250)
                task {
                    if (date.isNotEmpty()) LocalDate.parse(date)
                    refresh()
                }
            }
    }

    fun loadMore() {
        task { refresh(limit = state.value.expenses.size + 100) }
    }

    fun moreHistory() {
        task { refresh(historyLimit = state.value.history.size + 120) }
    }

    fun theme() {
        val dark = !state.value.dark
        prefs.edit().putBoolean("dark", dark).apply()
        mutable.value = mutable.value.copy(dark = dark)
    }

    fun profile(
        name: String,
        currency: String,
        id: String?,
        incomeEnabled: Boolean,
        done: () -> Unit,
    ) {
        task {
            require(name.trim().length in 1..60) { "Profile name must contain 1–60 characters." }
            java.util.Currency.getInstance(currency)
            val r = repo!!
            if (id == null) {
                val p =
                    Profile(
                        UUID.randomUUID().toString(),
                        name.trim(),
                        currency,
                        incomeEnabled = incomeEnabled,
                    )
                r.dao.addProfile(p)
                selected = p.id
            } else
                r.updateProfile(
                    r.dao
                        .profiles()
                        .first { it.id == id }
                        .copy(name = name, currency = currency, incomeEnabled = incomeEnabled)
                )
            refresh()
            withContext(Dispatchers.Main) { done() }
        }
    }

    private fun authenticate(password: String) {
        val key = security.unlock(password)
        try {
            check(java.security.MessageDigest.isEqual(key, master))
        } finally {
            key.fill(0)
        }
    }

    fun deleteProfile(p: Profile, password: String, done: () -> Unit) {
        task {
            authenticate(password)
            repo!!.dao.deleteProfile(p.id)
            drafts
                .edit()
                .apply { drafts.all.keys.filter { it.contains(p.id) }.forEach { remove(it) } }
                .apply()
            refresh()
            withContext(Dispatchers.Main) { done() }
        }
    }

    fun deleteEntry(e: Entry, password: String, done: () -> Unit) {
        task {
            authenticate(password)
            require(e.profileId == selected)
            repo!!.dao.deleteEntry(e.profileId, e.id)
            clearDraft("entry-${e.profileId}-${e.id}")
            refresh()
            withContext(Dispatchers.Main) { done() }
        }
    }

    fun saveEntry(
        id: String?,
        amount: String,
        date: String,
        description: String,
        type: String,
        done: () -> Unit,
    ) {
        task {
            val p = requireNotNull(state.value.profile)
            val parsed =
                try {
                    LocalDate.parse(date)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Use a valid date in YYYY-MM-DD format.")
                }
            repo!!.saveEntry(
                p.id,
                id,
                Money.parse(amount, p.currency, false),
                parsed,
                description,
                type,
            )
            refresh()
            withContext(Dispatchers.Main) { done() }
        }
    }

    fun changePassword(old: String, new: String, confirm: String, done: () -> Unit) {
        task {
            require(new == confirm) { "Passwords do not match." }
            authenticate(old)
            security.changePassword(master!!, new)
            mutable.value = mutable.value.copy(biometric = false)
            withContext(Dispatchers.Main) { done() }
        }
    }

    fun disableBiometric() {
        task {
            security.disableBiometric()
            mutable.value = mutable.value.copy(biometric = false)
        }
    }

    fun biometricSuccess(cipher: Cipher, enroll: Boolean) {
        task(enroll) {
            if (enroll) {
                security.enrollBiometric(cipher, master!!)
                mutable.value = mutable.value.copy(biometric = true)
            } else open(security.unlockBiometric(cipher))
        }
    }

    fun export(uri: Uri, kind: String, password: String, authentication: String, done: () -> Unit) {
        task {
            val r = repo!!
            val resolver = getApplication<Application>().contentResolver
            if (kind == "restore") {
                authenticate(authentication)
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open backup." }
                    try {
                        Backup.restore(r, password, input)
                        drafts.edit().clear().apply()
                    } catch (e: Exception) {
                        throw IllegalArgumentException(
                            "Restore failed: wrong password, damaged backup, or unsupported data. Existing data has been preserved."
                        )
                    }
                }
                selected = null
                refresh()
            } else
                resolver.openOutputStream(uri, "wt").use { out ->
                    requireNotNull(out) { "Could not open the selected file." }
                    if (kind == "backup") Backup.write(r, password, out)
                    else {
                        val p = requireNotNull(state.value.profile)
                        when (kind) {
                            "csv" -> Exports.csv(r, p, period, out)
                            "pdf" -> Exports.pdf(r, p, period, out)
                            "xlsx" -> Exports.xlsx(r, p, period, out)
                        }
                    }
                }
            mutable.value =
                mutable.value.copy(
                    notice =
                        if (kind == "restore") "Backup restored successfully."
                        else "File saved successfully."
                )
            withContext(Dispatchers.Main) { done() }
        }
    }

    override fun onCleared() {
        repo?.db?.close()
        master?.fill(0)
    }
}
