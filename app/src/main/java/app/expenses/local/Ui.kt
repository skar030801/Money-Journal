@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.expenses.local

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF8BE5C6),
        onPrimary = Color(0xFF07382C),
        primaryContainer = Color(0xFF204C40),
        onPrimaryContainer = Color(0xFFCEF5E8),
        secondaryContainer = Color(0xFF204C40),
        onSecondaryContainer = Color(0xFFCEF5E8),
        outlineVariant = Color(0xFF425A61),
        secondary = Color(0xFFAEC9F5),
        background = Color(0xFF0C151B),
        surface = Color(0xFF111E26),
        surfaceVariant = Color(0xFF213039),
        onSurface = Color(0xFFE5EFF1),
        onSurfaceVariant = Color(0xFFB0C3CA),
        outline = Color(0xFF71868F),
        error = Color(0xFFFFB4AB),
    )
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF146B53),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCDF4E5),
        onPrimaryContainer = Color(0xFF07382C),
        secondaryContainer = Color(0xFFCDF4E5),
        onSecondaryContainer = Color(0xFF07382C),
        outlineVariant = Color(0xFFBDCEC8),
        secondary = Color(0xFF405F8B),
        background = Color(0xFFF4F8F7),
        surface = Color.White,
        surfaceVariant = Color(0xFFE1ECE8),
        onSurface = Color(0xFF172A30),
        onSurfaceVariant = Color(0xFF455F64),
        outline = Color(0xFF6A807D),
        error = Color(0xFFBA1A1A),
    )

@Composable
fun ExpensesApp(
    vm: ExpensesViewModel,
    biometric: (Boolean) -> Unit,
    pick: (String) -> Unit,
    pending: Uri?,
    kind: String,
    clearPending: () -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            val controller =
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !s.dark
            controller.isAppearanceLightNavigationBars = !s.dark
            window.navigationBarColor =
                (if (s.dark) DarkColors.background else LightColors.background).toArgb()
        }
    }
    MaterialTheme(colorScheme = if (s.dark) DarkColors else LightColors) {
        Surface(Modifier.fillMaxSize()) {
            if (s.locked) LockScreen(s, vm, biometric)
            else
                key(s.profile?.id) {
                    UnlockedApp(s, vm, biometric, pick, pending, kind, clearPending)
                }
        }
    }
}

@Composable
private fun ErrorNotice(s: AppState) {
    s.error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun SecretField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(
        value,
        change,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun LockScreen(s: AppState, vm: ExpensesViewModel, biometric: (Boolean) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.AccountBalanceWallet,
            null,
            Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text("Money Journal", fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text(
            if (!s.configured) "Your money. Your device."
            else if (recovery) "Recover access offline" else "Welcome back",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            if (!s.configured)
                "Create one PIN or password to protect all your profiles. Use 6 or more digits, or a password of at least 10 characters."
            else if (recovery)
                "Enter the recovery code saved during setup, then choose a new PIN/password."
            else "Unlock to view your profiles and monthly spending.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (recovery) SecretField(code, { code = it }, "Offline recovery code")
        SecretField(
            password,
            { password = it },
            if (recovery) "New PIN/password" else "PIN/password",
        )
        if (!s.configured || recovery)
            SecretField(confirm, { confirm = it }, "Confirm PIN/password")
        Spacer(Modifier.height(16.dp))
        ErrorNotice(s)
        Button(
            onClick = {
                if (!s.configured) vm.setup(password, confirm)
                else if (recovery) vm.recover(code, password, confirm) else vm.unlock(password)
            },
            enabled = !s.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                if (!s.configured) "Create secure app"
                else if (recovery) "Recover and unlock" else "Unlock"
            )
        }
        if (s.configured) {
            if (s.biometric && !recovery)
                OutlinedButton({ biometric(false) }, Modifier.fillMaxWidth(), enabled = !s.busy) {
                    Icon(Icons.Outlined.Fingerprint, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock with biometrics")
                }
            TextButton({
                recovery = !recovery
                vm.clearMessage()
            }) {
                Text(if (recovery) "Back to unlock" else "Forgot PIN/password?")
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "OFFLINE  /  ENCRYPTED  /  PRIVATE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnlockedApp(
    s: AppState,
    vm: ExpensesViewModel,
    biometric: (Boolean) -> Unit,
    pick: (String) -> Unit,
    pending: Uri?,
    kind: String,
    clearPending: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var newType by remember { mutableStateOf(EntryType.EXPENSE) }
    var profileEdit by remember { mutableStateOf<Profile?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Money Journal", fontWeight = FontWeight.Bold)
                        Text(
                            s.profile?.name ?: "Your private money journal",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton({ page = if (page == "profiles") "" else "profiles" }) {
                        Icon(Icons.Outlined.People, "Profiles")
                    }
                    IconButton({ vm.theme() }) {
                        Icon(
                            if (s.dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            "Toggle light/dark mode",
                        )
                    }
                    IconButton({ page = if (page == "settings") "" else "settings" }) {
                        Icon(Icons.Outlined.Settings, "Settings")
                    }
                },
            )
        },
        bottomBar = {
            if (page.isEmpty() && s.profile != null)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(
                            "Overview" to Icons.Outlined.Dashboard,
                            "Entries" to Icons.Outlined.ReceiptLong,
                            "History" to Icons.Outlined.History,
                            "Insights" to Icons.Outlined.BarChart,
                        )
                        .forEachIndexed { i, (name, icon) ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(icon, null) },
                                label = { Text(name) },
                            )
                        }
                }
        },
        floatingActionButton = {
            if (page.isEmpty() && s.profile != null)
                ExtendedFloatingActionButton(
                    modifier = Modifier.semantics { contentDescription = "Add entry" },
                    onClick = {
                        editing = null
                        dialog = if (s.profile?.incomeEnabled == false) "expense" else "chooseEntry"
                        newType = EntryType.EXPENSE
                    },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text("Add entry") },
                )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (dialog.isEmpty()) {
                s.error?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                s.notice?.let {
                    Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
            when {
                page == "settings" ->
                    Settings(s, { page = "" }, vm, biometric, pick) { dialog = "password" }
                page == "profiles" || s.profile == null ->
                    Profiles(
                        s,
                        { page = "" },
                        {
                            profileEdit = null
                            dialog = "profile"
                        },
                        {
                            profileEdit = it
                            dialog = "profile"
                        },
                        {
                            profileEdit = it
                            dialog = "deleteProfile"
                        },
                    ) {
                        vm.selectProfile(it.id)
                        page = ""
                    }
                else -> {
                    MonthStrip(s.month, vm::selectMonth) { dialog = "month" }
                    when (tab) {
                        0 ->
                            Dashboard(
                                s,
                                { tab = 1 },
                                {
                                    editing = it
                                    dialog = "expense"
                                },
                                { pick(it) },
                            )
                        1 ->
                            EntryList(
                                s,
                                vm,
                                {
                                    editing = it
                                    dialog = "expense"
                                },
                                {
                                    editing = it
                                    dialog = "deleteEntry"
                                },
                            )
                        2 ->
                            History(
                                s,
                                {
                                    vm.selectMonth(YearMonth.parse(it))
                                    tab = 0
                                },
                                vm::moreHistory,
                            )
                        3 -> Analytics(s)
                    }
                }
            }
        }
    }
    when (dialog) {
        "expense" -> EntryForm(s, editing, newType, vm) { dialog = "" }
        "chooseEntry" ->
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Add entry") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Are you recording money spent or money received?")
                        Setting("Expense", Icons.Outlined.ArrowUpward) {
                            newType = EntryType.EXPENSE
                            dialog = "expense"
                        }
                        Setting("Income", Icons.Outlined.ArrowDownward) {
                            newType = EntryType.INCOME
                            dialog = "expense"
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } },
            )
        "profile" -> ProfileForm(s, profileEdit, vm) { dialog = "" }
        "deleteEntry" ->
            DeleteDialog(
                s,
                "Delete ${EntryType.label(editing?.type ?: EntryType.EXPENSE).lowercase()} permanently?",
                "${editing?.description}\nThis entry cannot be recovered.",
                { dialog = "" },
            ) { password ->
                editing?.let { vm.deleteEntry(it, password) { dialog = "" } }
            }
        "deleteProfile" ->
            DeleteDialog(
                s,
                "Delete ${profileEdit?.name}?",
                "All income, expenses and history in this profile will be permanently deleted. There is no undo.",
                { dialog = "" },
            ) { password ->
                profileEdit?.let { vm.deleteProfile(it, password) { dialog = "" } }
            }
        "password" -> PasswordForm(s, vm) { dialog = "" }
        "month" ->
            MonthDialog(s.month, { dialog = "" }) {
                vm.selectMonth(it)
                dialog = ""
            }
    }
    s.recovery?.let { RecoveryDialog(it, vm::acknowledgeRecovery) }
    if (pending != null && s.recovery == null)
        FileDialog(s, kind, { clearPending() }) { password, auth ->
            vm.export(pending, kind, password, auth, clearPending)
        }
}

@Composable
private fun MonthStrip(month: YearMonth, select: (YearMonth) -> Unit, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton({ select(month.minusMonths(1)) }) {
            Icon(Icons.Outlined.ChevronLeft, "Previous month")
        }
        TextButton(choose) {
            Text(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                style = MaterialTheme.typography.titleMedium,
            )
            Icon(Icons.Outlined.ExpandMore, null)
        }
        IconButton({ select(month.plusMonths(1)) }) {
            Icon(Icons.Outlined.ChevronRight, "Next month")
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String? = null) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        subtitle?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    negative: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color =
                    if (negative) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Dashboard(
    s: AppState,
    all: () -> Unit,
    edit: (Entry) -> Unit,
    export: (String) -> Unit,
) {
    val p = s.profile ?: return
    val f = s.summary
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("TOTAL EXPENSES", style = MaterialTheme.typography.labelLarge)
                    Text(
                        Money.format(f.total, p.currency),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Text(
                        "${f.count} ${if(f.count==1)"expense" else "expenses"} this month",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (p.incomeEnabled)
            item {
                Metric(
                    "Total income",
                    Money.format(f.income, p.currency),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        if (p.incomeEnabled)
            item {
                Metric(
                    "Remaining",
                    Money.format(f.remaining, p.currency),
                    f.remaining < 0,
                    Modifier.fillMaxWidth(),
                )
                Text(
                    "Income minus expenses for this month. Previous months are not carried forward.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Recent entries", style = MaterialTheme.typography.titleLarge)
                TextButton(all) { Text("View all") }
            }
        }
        if (s.recent.isEmpty())
            item {
                Empty(
                    "A fresh start",
                    if (p.incomeEnabled) "Tap Add entry to record an expense or a payment received."
                    else "Tap Add entry to record an expense.",
                )
            }
        items(s.recent, key = { it.id }) { EntryRow(it, p.currency, { edit(it) }) }
        item {
            Text("Export this month", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("csv", "pdf", "xlsx").forEach { k ->
                    OutlinedButton({ export(k) }) { Text(k.uppercase()) }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: Entry, currency: String, edit: () -> Unit, delete: (() -> Unit)? = null) {
    OutlinedCard(onClick = edit, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    e.description.ifBlank { EntryType.label(e.type) },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${EntryType.label(e.type)} · ${e.date}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${if (e.type == EntryType.INCOME) "+" else "−"}${Money.format(e.amount, currency)}",
                color =
                    if (e.type == EntryType.INCOME) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
            delete?.let {
                IconButton(it) {
                    Icon(
                        Icons.Outlined.Delete,
                        "Delete ${e.description.ifBlank { EntryType.label(e.type) }}",
                    )
                }
            }
        }
    }
}

@Composable
private fun Empty(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.ReceiptLong,
            null,
            Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun EntryList(
    s: AppState,
    vm: ExpensesViewModel,
    edit: (Entry) -> Unit,
    delete: (Entry) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                s.query,
                { vm.filter(query = it) },
                label = { Text("Search descriptions") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    s.date,
                    { vm.filter(date = it) },
                    label = { Text("Date · YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton({ vm.filter(date = "", query = "", all = false, type = "") }) {
                    Text("Clear")
                }
            }
        }
        item {
            FilterChip(
                selected = s.allMonths,
                onClick = { vm.filter(all = !s.allMonths) },
                label = { Text("Search all months") },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (s.profile?.incomeEnabled != false)
                        listOf(
                            "" to "All",
                            EntryType.EXPENSE to "Expenses",
                            EntryType.INCOME to "Income",
                        )
                    else listOf("" to "All", EntryType.EXPENSE to "Expenses"))
                    .forEach { (type, label) ->
                        FilterChip(
                            selected = s.entryType == type,
                            onClick = { vm.filter(type = type) },
                            label = { Text(label) },
                        )
                    }
            }
        }
        if (s.expenses.isEmpty())
            item { Empty("No matching entries", "Try another search or add an entry.") }
        items(s.expenses, key = { it.id }) { e ->
            EntryRow(e, s.profile!!.currency, { edit(e) }, { delete(e) })
        }
        if (s.more)
            item {
                OutlinedButton(vm::loadMore, Modifier.fillMaxWidth(), enabled = !s.busy) {
                    Text("Load more entries")
                }
            }
    }
}

@Composable
private fun History(s: AppState, select: (String) -> Unit, more: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Heading("Monthly history", "Your past months stay editable.") }
        items(s.history, key = { it.period }) { m ->
            OutlinedCard(onClick = { select(m.period) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        YearMonth.parse(m.period).format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${Money.format(m.total,s.profile!!.currency)} spent · ${m.count} expenses",
                        Modifier.padding(vertical = 8.dp),
                    )
                    if (s.profile.incomeEnabled) {
                        val f = m.summary()
                        Text(
                            "Income: ${Money.format(m.income,s.profile.currency)} · ${m.incomeCount} payments"
                        )
                        Text(
                            "Remaining: ${Money.format(f.remaining,s.profile.currency)}",
                            color =
                                if (f.remaining < 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        if (s.historyMore)
            item { OutlinedButton(more, enabled = !s.busy) { Text("Load older months") } }
    }
}

@Composable
private fun Analytics(s: AppState) {
    val months = s.history.filter { it.count > 0 || it.incomeCount > 0 }.take(12).reversed()
    val max = months.maxOfOrNull { maxOf(it.total, it.income) } ?: 0
    val color = MaterialTheme.colorScheme.primary
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Heading(
                if (s.profile?.incomeEnabled == false) "Spending insights" else "Income & spending",
                "Up to 12 recent months with financial data.",
            )
        }
        if (months.isEmpty())
            item {
                Empty(
                    "Your story starts here",
                    "Record income and expenses to see monthly comparisons.",
                )
            }
        items(months, key = { it.period }) { m ->
            Column {
                Text(m.period, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                    drawRoundRect(color.copy(alpha = .15f))
                    if (max > 0)
                        drawRoundRect(
                            color,
                            size =
                                size.copy(width = size.width * (m.total.toDouble() / max).toFloat()),
                        )
                }
                Text(
                    "Spent ${Money.format(m.total,s.profile!!.currency)}",
                    Modifier.padding(top = 8.dp),
                )
                if (s.profile.incomeEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                        drawRoundRect(color.copy(alpha = .15f))
                        if (max > 0)
                            drawRoundRect(
                                color.copy(alpha = .55f),
                                size =
                                    size.copy(
                                        width = size.width * (m.income.toDouble() / max).toFloat()
                                    ),
                            )
                    }
                    Text(
                        "Income ${Money.format(m.income,s.profile.currency)}",
                        Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Remaining ${Money.format(m.summary().remaining,s.profile.currency)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Profiles(
    s: AppState,
    back: () -> Unit,
    create: () -> Unit,
    edit: (Profile) -> Unit,
    delete: (Profile) -> Unit,
    select: (Profile) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(back) { Text("Back") }
            Heading("Profiles", "Separate records. One secure app lock.")
            Button(create) {
                Icon(Icons.Outlined.Add, null)
                Text("Create profile")
            }
        }
        if (s.profiles.isEmpty())
            item {
                Empty("Make it yours", "Create your first profile to track income and expenses.")
            }
        items(s.profiles, key = { it.id }) { p ->
            OutlinedCard(onClick = { select(p) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("${p.currency}${if(p.id==s.profile?.id)" · Selected" else ""}")
                    }
                    IconButton({ edit(p) }) { Icon(Icons.Outlined.Edit, "Edit ${p.name}") }
                    IconButton({ delete(p) }) { Icon(Icons.Outlined.Delete, "Delete ${p.name}") }
                }
            }
        }
    }
}

@Composable
private fun Settings(
    s: AppState,
    back: () -> Unit,
    vm: ExpensesViewModel,
    biometric: (Boolean) -> Unit,
    pick: (String) -> Unit,
    password: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(back) { Text("Back") }
        Heading("Settings", "Private by design. Entirely offline.")
        Setting("Lock app", Icons.Outlined.Lock) { vm.lock() }
        Setting("Change PIN/password", Icons.Outlined.Password, password)
        Setting(
            if (s.biometric) "Disable biometric unlock" else "Enable biometric unlock",
            Icons.Outlined.Fingerprint,
        ) {
            if (s.biometric) vm.disableBiometric() else biometric(true)
        }
        Setting(
            if (s.dark) "Switch to light mode" else "Switch to dark mode",
            Icons.Outlined.Contrast,
        ) {
            vm.theme()
        }
        HorizontalDivider()
        Heading("Move to another phone")
        Text(
            "Encrypted backups include every profile and month. Choose a separate backup password of at least 12 characters. On your new phone, set up Money Journal, then restore the backup.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Setting("Save encrypted backup", Icons.Outlined.Save) { pick("backup") }
        Setting("Restore encrypted backup", Icons.Outlined.Restore) { pick("restore") }
        Text(
            "Restore replaces all current profiles and history after confirmation and app authentication. Your new phone keeps its own PIN and recovery code.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Heading("Money Journal 1.3.0")
        Text(
            "No accounts, ads, analytics, or internet permission. Data stays encrypted on this device. Reports become readable files only when you explicitly export them."
        )
        Text(
            "The app locks whenever it leaves the foreground. Screenshots and recent-app previews are protected. Biometrics never authorize permanent deletion.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Built with Kotlin, Jetpack Compose, Room and SQLCipher. SQLCipher Community Edition uses the BSD license.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Setting(text: String, icon: ImageVector, action: () -> Unit) {
    OutlinedButton(action, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Icon(icon, null)
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null)
    }
}

@Composable
private fun Form(
    title: String,
    s: AppState,
    close: () -> Unit,
    save: () -> Unit,
    button: String = "Save",
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!s.busy) close() },
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
                ErrorNotice(s)
            }
        },
        confirmButton = { TextButton(save, enabled = !s.busy) { Text(button) } },
        dismissButton = { TextButton(close, enabled = !s.busy) { Text("Cancel") } },
    )
}

@Composable
private fun EntryForm(
    s: AppState,
    e: Entry?,
    newType: String,
    vm: ExpensesViewModel,
    close: () -> Unit,
) {
    val p = s.profile ?: return
    var choosingDate by remember { mutableStateOf(false) }
    val draftName = "entry-${p.id}-${e?.id?:"new-$newType"}"
    val draft = remember { vm.draft(draftName) }
    var amount by remember {
        mutableStateOf(draft?.getOrNull(0) ?: e?.let { Money.decimal(it.amount, p.currency) } ?: "")
    }
    var date by remember {
        mutableStateOf(
            draft?.getOrNull(1)
                ?: e?.date
                ?: if (s.month == YearMonth.now()) LocalDate.now().toString()
                else s.month.atDay(1).toString()
        )
    }
    var description by remember { mutableStateOf(draft?.getOrNull(2) ?: e?.description ?: "") }
    var type by remember {
        mutableStateOf(if (p.incomeEnabled) draft?.getOrNull(3) ?: e?.type ?: newType else EntryType.EXPENSE)
    }
    LaunchedEffect(amount, date, description, type) {
        vm.saveDraft(draftName, listOf(amount, date, description, type))
    }
    Form(
        "${if (e == null) "Add" else "Edit"} ${EntryType.label(type).lowercase()}",
        s,
        close,
        {
            vm.saveEntry(e?.id, amount, date, description, type) {
                vm.clearDraft(draftName)
                close()
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (p.incomeEnabled) listOf(EntryType.EXPENSE, EntryType.INCOME)
                else listOf(EntryType.EXPENSE))
                .forEach { value ->
                    FilterChip(
                        selected = type == value,
                        onClick = { type = value },
                        label = { Text(EntryType.label(value)) },
                    )
                }
        }
        OutlinedTextField(
            description,
            { if (it.length <= 500) description = it },
            label = {
                Text(
                    if (type == EntryType.INCOME) "Where did this income come from?"
                    else "What was this expense for?"
                )
            },
            supportingText = { Text("Optional · ${description.length}/500") },
            minLines = 2,
        )
        Text("Date", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { choosingDate = true },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Choose date" },
        ) {
            Icon(Icons.Outlined.CalendarMonth, null)
            Spacer(Modifier.width(8.dp))
            Text(
                runCatching {
                        LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
                    }
                    .getOrDefault(date)
            )
        }
        OutlinedTextField(
            amount,
            { amount = it },
            label = { Text("Amount · ${p.currency}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        Text(
            "The date determines which month contains this entry.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (choosingDate) {
        val initial = runCatching { LocalDate.parse(date) }.getOrNull()
            ?.takeIf { it.year in 1900..9999 } ?: LocalDate.now()
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis = initial.toEpochDay() * 86_400_000L,
                yearRange = 1900..9999,
            )
        DatePickerDialog(
            onDismissRequest = { choosingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let {
                            date = LocalDate.ofEpochDay(Math.floorDiv(it, 86_400_000L)).toString()
                        }
                        choosingDate = false
                    },
                    enabled = picker.selectedDateMillis != null,
                ) {
                    Text("Use date")
                }
            },
            dismissButton = { TextButton({ choosingDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(picker)
        }
    }
}

@Composable
private fun ProfileForm(s: AppState, p: Profile?, vm: ExpensesViewModel, close: () -> Unit) {
    var name by remember { mutableStateOf(p?.name ?: "") }
    var currency by remember { mutableStateOf(p?.currency ?: "INR") }
    var incomeEnabled by remember { mutableStateOf(p?.incomeEnabled ?: true) }
    Form(
        if (p == null) "Create profile" else "Edit profile",
        s,
        close,
        { vm.profile(name, currency.uppercase(), p?.id, incomeEnabled, close) },
    ) {
        OutlinedTextField(
            name,
            { if (it.length <= 60) name = it },
            label = { Text("Profile name") },
        )
        OutlinedTextField(
            currency,
            { if (it.length <= 3) currency = it.uppercase() },
            label = { Text("Currency code") },
            supportingText = { Text("INR, USD, EUR, GBP, JPY and other ISO codes") },
        )
        Text("Track in this profile", style = MaterialTheme.typography.titleSmall)
        FilterChip(
            selected = incomeEnabled,
            onClick = { incomeEnabled = true },
            label = { Text("Income & expenses") },
        )
        FilterChip(
            selected = !incomeEnabled,
            onClick = { incomeEnabled = false },
            label = { Text("Expenses only") },
        )
        Text(
            "Expenses-only profiles hide income and remaining totals. You can enable income later.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (p != null)
            Text(
                "Currency is locked once this profile contains financial data.",
                style = MaterialTheme.typography.bodySmall,
            )
    }
}

@Composable
private fun DeleteDialog(
    s: AppState,
    title: String,
    body: String,
    close: () -> Unit,
    delete: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    Form(title, s, close, { if (confirmed) delete(password) }, "Delete permanently") {
        Text(body)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(confirmed, { confirmed = it })
            Text("I understand this is permanent.", Modifier.weight(1f))
        }
        SecretField(password, { password = it }, "App PIN/password")
        if (!confirmed)
            Text(
                "Confirm the checkbox to enable deletion.",
                style = MaterialTheme.typography.bodySmall,
            )
    }
}

@Composable
private fun PasswordForm(s: AppState, vm: ExpensesViewModel, close: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    Form("Change PIN/password", s, close, { vm.changePassword(old, new, confirm, close) }) {
        SecretField(old, { old = it }, "Current PIN/password")
        SecretField(new, { new = it }, "New PIN/password")
        SecretField(confirm, { confirm = it }, "Confirm new PIN/password")
        Text(
            "Your recovery code stays valid. Enable biometrics again after changing your password."
        )
    }
}

@Composable
private fun RecoveryDialog(code: String, done: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Save your recovery code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Write this code down and keep it somewhere private, separate from your phone. It can reset your app password without internet."
                )
                Text(
                    code,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "If you lose both your password and this code, your local data cannot be recovered. Keep encrypted backups for phone loss."
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { checked = it })
                    Text("I saved my recovery code.")
                }
            }
        },
        confirmButton = { Button(done, enabled = checked) { Text("Continue") } },
    )
}

@Composable
private fun MonthDialog(current: YearMonth, close: () -> Unit, select: (YearMonth) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Go to month") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text("YYYY-MM") })
                if (error)
                    Text(
                        "Enter a valid month, year 1900–9999.",
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        },
        confirmButton = {
            TextButton({
                try {
                    val ym = YearMonth.parse(text)
                    require(ym.year in 1900..9999)
                    select(ym)
                } catch (e: Exception) {
                    error = true
                }
            }) {
                Text("Open")
            }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
    )
}

@Composable
private fun FileDialog(
    s: AppState,
    kind: String,
    close: () -> Unit,
    save: (String, String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("") }
    var agree by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }
    Form(
        when (kind) {
            "backup" -> "Save encrypted backup"
            "restore" -> "Restore backup"
            else -> "Export ${kind.uppercase()}"
        },
        s,
        close,
        {
            if (kind == "backup" && password != confirm)
                localError = "Backup passwords do not match."
            else if (kind == "restore" && !agree)
                localError = "Confirm that current data will be replaced."
            else save(password, auth)
        },
        if (kind == "restore") "Replace and restore" else "Save",
    ) {
        if (kind == "backup" || kind == "restore") {
            Text(
                if (kind == "backup")
                    "Includes all profiles and history. Keep the backup password safe: it cannot be reset with your app recovery code."
                else
                    "This replaces all profiles and history on this phone. A failed restore preserves current data."
            )
            SecretField(password, { password = it }, "Backup password (12+ characters)")
            if (kind == "backup") SecretField(confirm, { confirm = it }, "Confirm backup password")
            else {
                SecretField(auth, { auth = it }, "Current app PIN/password")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(agree, { agree = it })
                    Text("Replace all current data.")
                }
            }
        } else
            Text(
                "Export ${s.profile?.name} · ${s.month}. This report is readable outside Money Journal. Save it in a location you trust."
            )
        if (localError.isNotEmpty()) Text(localError, color = MaterialTheme.colorScheme.error)
    }
}
