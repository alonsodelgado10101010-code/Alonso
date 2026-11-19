package cl.vintagemanager.ui.finance

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cl.vintagemanager.core.AnnualTax
import cl.vintagemanager.core.Category
import cl.vintagemanager.core.DebtStatus
import cl.vintagemanager.core.Movement
import cl.vintagemanager.core.MovementFilter
import cl.vintagemanager.core.MovementKind
import cl.vintagemanager.core.MonthlyTax
import cl.vintagemanager.core.Payable
import cl.vintagemanager.core.Receivable
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.export.Exporter
import cl.vintagemanager.ui.components.BaseViewModel
import cl.vintagemanager.ui.components.Disclaimer
import cl.vintagemanager.ui.components.DropdownField
import cl.vintagemanager.ui.components.EmptyState
import cl.vintagemanager.ui.components.LabeledAmount
import cl.vintagemanager.ui.components.ScreenScaffold
import cl.vintagemanager.ui.components.SectionCard
import cl.vintagemanager.ui.components.StatusChip
import cl.vintagemanager.ui.navigation.Routes
import cl.vintagemanager.ui.theme.Olive
import cl.vintagemanager.ui.theme.SemFresca
import cl.vintagemanager.ui.theme.SemLiquidar
import cl.vintagemanager.ui.theme.SemPronto
import cl.vintagemanager.ui.theme.Terracotta
import cl.vintagemanager.util.categoriesFor
import cl.vintagemanager.util.cl
import cl.vintagemanager.util.clp
import cl.vintagemanager.util.daysText
import cl.vintagemanager.util.label
import cl.vintagemanager.util.monthName
import cl.vintagemanager.util.pct
import cl.vintagemanager.util.today
import cl.vintagemanager.util.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MovementsFilterUi(
    val month: YearMonth = YearMonth.now(),
    val kind: MovementKind? = null,
    val category: Category? = null,
)

@HiltViewModel
class FinanceViewModel @Inject constructor(repo: VintageRepository) : BaseViewModel(repo) {
    val filter = MutableStateFlow(MovementsFilterUi())
    private val _movements = MutableStateFlow<List<Movement>>(emptyList())
    val movements = _movements.asStateFlow()
    private val _receivables = MutableStateFlow<List<Receivable>>(emptyList())
    val receivables = _receivables.asStateFlow()
    private val _payables = MutableStateFlow<List<Payable>>(emptyList())
    val payables = _payables.asStateFlow()
    val showPaid = MutableStateFlow(false)

    val taxYear = MutableStateFlow(YearMonth.now().year)
    private val _months = MutableStateFlow<List<MonthlyTax>>(emptyList())
    val months = _months.asStateFlow()
    private val _annual = MutableStateFlow<AnnualTax?>(null)
    val annual = _annual.asStateFlow()

    init {
        reloadOnChanges { load() }
    }

    private suspend fun load() {
        val f = filter.value
        val coreFilter = MovementFilter(f.month.year, f.month.monthValue.toUInt(), f.kind, f.category)
        repo.query { listMovements(coreFilter) }.onSuccess { _movements.value = it }.onFailure { notify(it.userMessage()) }
        val open = !showPaid.value
        repo.query { listReceivables(today(), open) }.onSuccess { _receivables.value = it }
        repo.query { listPayables(today(), open) }.onSuccess { _payables.value = it }
        val y = taxYear.value
        repo.query { taxYear(y, today()) }.onSuccess { _months.value = it }
        repo.query { annualTax(y, today()) }.onSuccess { _annual.value = it }
    }

    private fun reload() {
        viewModelScope.launch { load() }
    }

    fun setFilter(f: MovementsFilterUi) {
        filter.value = f
        reload()
    }

    fun toggleShowPaid() {
        showPaid.value = !showPaid.value
        reload()
    }

    fun shiftTaxYear(delta: Int) {
        taxYear.value += delta
        reload()
    }

    fun setFiled(m: MonthlyTax, filed: Boolean) {
        if (filed) mutate("F29 de ${monthName(m.month.toInt())} marcado como declarado", { markF29Filed(m.year, m.month, today()) })
        else mutate(null, { unmarkF29Filed(m.year, m.month) })
    }

    fun exportMovementsCsv(context: Context) = viewModelScope.launch {
        val f = filter.value
        repo.query { movementsCsv(MovementFilter(f.month.year, f.month.monthValue.toUInt(), f.kind, f.category)) }
            .onSuccess { Exporter.shareText(context, "movimientos_${f.month}.csv", it, "text/csv") }
            .onFailure { notify(it.userMessage()) }
    }

    fun exportTaxCsv(context: Context) = viewModelScope.launch {
        val y = taxYear.value
        repo.query { taxCsv(y, today()) }
            .onSuccess { Exporter.shareText(context, "impuestos_$y.csv", it, "text/csv") }
            .onFailure { notify(it.userMessage()) }
    }

    fun exportTaxPdf(context: Context) = viewModelScope.launch {
        val y = taxYear.value
        val annual = _annual.value ?: return@launch
        repo.query { getSettings() }.onSuccess { s ->
            runCatching {
                Exporter.taxPdf(
                    context, s.storeName, s.storeRut, y, _months.value, annual,
                    "Cálculo referencial. Confirma con tu contador antes de declarar en el SII.",
                )
            }.onSuccess { Exporter.share(context, it, "application/pdf") }
                .onFailure { notify(it.userMessage()) }
        }
    }
}

private val tabTitles = listOf("Movimientos", "Cobros", "Pagos", "Impuestos")

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FinanceScreen(initialTab: Int, onNavigate: (String) -> Unit, vm: FinanceViewModel = hiltViewModel()) {
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    ScreenScaffold(
        title = "Finanzas",
        message = message,
        onMessageShown = vm::consumeMessage,
        actions = {
            when (tab) {
                0 -> IconButton(onClick = { vm.exportMovementsCsv(context) }) { Icon(Icons.Filled.IosShare, "Exportar CSV") }
                2 -> IconButton(onClick = { onNavigate(Routes.SUPPLIERS) }) { Icon(Icons.Filled.Groups, "Proveedores") }
                else -> {}
            }
        },
        fab = {
            when (tab) {
                0 -> FloatingActionButton(onClick = { onNavigate(Routes.newMovement("INCOME")) }) { Icon(Icons.Filled.Add, "Nuevo movimiento") }
                1 -> FloatingActionButton(onClick = { onNavigate(Routes.receivable(0)) }) { Icon(Icons.Filled.Add, "Nuevo cobro") }
                2 -> FloatingActionButton(onClick = { onNavigate(Routes.payable(0)) }) { Icon(Icons.Filled.Add, "Nuevo pago") }
                else -> {}
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabTitles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> MovementsTab(vm, onNavigate)
                1 -> ReceivablesTab(vm, onNavigate)
                2 -> PayablesTab(vm, onNavigate)
                else -> TaxesTab(vm)
            }
        }
    }
}

@Composable
private fun MonthBar(text: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Filled.ChevronLeft, "Anterior") }
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        IconButton(onClick = onNext) { Icon(Icons.Filled.ChevronRight, "Siguiente") }
    }
}

@Composable
private fun MovementsTab(vm: FinanceViewModel, onNavigate: (String) -> Unit) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val movements by vm.movements.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            MonthBar(
                "${monthName(filter.month.monthValue)} ${filter.month.year}",
                onPrev = { vm.setFilter(filter.copy(month = filter.month.minusMonths(1))) },
                onNext = { vm.setFilter(filter.copy(month = filter.month.plusMonths(1))) },
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = filter.kind == null, onClick = { vm.setFilter(filter.copy(kind = null, category = null)) }, label = { Text("Todos") }) }
                items(MovementKind.entries.toList()) { k ->
                    FilterChip(
                        selected = filter.kind == k,
                        onClick = { vm.setFilter(filter.copy(kind = k, category = null)) },
                        label = { Text(if (k == MovementKind.INCOME) "Ingresos" else "Egresos") },
                    )
                }
            }
        }
        val kind = filter.kind
        if (kind != null) {
            item {
                val options: List<Category?> = listOf<Category?>(null) + categoriesFor(kind)
                DropdownField(
                    label = "Categoría",
                    options = options,
                    selected = filter.category,
                    optionLabel = { it?.label ?: "Todas" },
                    onSelect = { vm.setFilter(filter.copy(category = it)) },
                )
            }
        }
        item {
            val income = movements.filter { it.kind == MovementKind.INCOME }.sumOf { it.gross }
            val expense = movements.filter { it.kind == MovementKind.EXPENSE }.sumOf { it.gross }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Ingresos: ${clp(income)}", color = Olive, fontWeight = FontWeight.SemiBold)
                Text("Egresos: ${clp(expense)}", color = Terracotta, fontWeight = FontWeight.SemiBold)
            }
        }
        if (movements.isEmpty()) {
            item {
                EmptyState(
                    Icons.Filled.ReceiptLong,
                    "Sin movimientos este mes",
                    "Registra tus ventas y gastos para ver tus impuestos y tu caja al día.",
                    "Registrar venta",
                ) { onNavigate(Routes.newMovement("INCOME")) }
            }
        }
        items(movements, key = { it.id }) { m -> MovementCard(m) { onNavigate(Routes.editMovement(m.id)) } }
        item { Spacer(Modifier.size(72.dp)) }
    }
}

@Composable
private fun MovementCard(m: Movement, onClick: () -> Unit) {
    SectionCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.category.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOf(m.date.cl(), m.documentType.label, m.paymentMethod.label).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                val sign = if (m.kind == MovementKind.INCOME) "+" else "−"
                Text(
                    "$sign${clp(m.gross)}",
                    color = if (m.kind == MovementKind.INCOME) Olive else Terracotta,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (m.iva > 0) {
                    Text(
                        "IVA ${clp(m.iva)}" + if (m.kind == MovementKind.EXPENSE) (if (m.ivaCreditable) " · crédito" else " · sin crédito") else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

fun statusColor(s: DebtStatus) = when (s) {
    DebtStatus.PAID -> SemFresca
    DebtStatus.OVERDUE -> SemLiquidar
    DebtStatus.PARTIAL -> SemPronto
    DebtStatus.PENDING -> Terracotta
}

@Composable
private fun ShowPaidToggle(vm: FinanceViewModel) {
    val showPaid by vm.showPaid.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = showPaid, onCheckedChange = { vm.toggleShowPaid() })
        Text("Mostrar también los pagados")
    }
}

@Composable
private fun ReceivablesTab(vm: FinanceViewModel, onNavigate: (String) -> Unit) {
    val list by vm.receivables.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ShowPaidToggle(vm) }
        item {
            val open = list.filter { it.status != DebtStatus.PAID }
            LabeledAmount("Total por cobrar", open.sumOf { it.remaining }, emphasize = true)
        }
        if (list.isEmpty()) {
            item {
                EmptyState(
                    Icons.Outlined.RequestQuote, "No tienes cobros pendientes",
                    "Registra ventas a crédito, consignaciones o apartados para no olvidarlos.",
                    "Agregar cobro",
                ) { onNavigate(Routes.receivable(0)) }
            }
        }
        items(list, key = { it.id }) { r ->
            SectionCard(onClick = { onNavigate(Routes.receivable(r.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.customer, style = MaterialTheme.typography.titleSmall)
                        Text("${r.concept.label} · vence ${r.dueDate.cl()} (${daysText(r.daysToDue)})", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(clp(r.remaining), style = MaterialTheme.typography.titleMedium)
                        StatusChip(r.status.label, statusColor(r.status))
                    }
                }
            }
        }
        item { Spacer(Modifier.size(72.dp)) }
    }
}

@Composable
private fun PayablesTab(vm: FinanceViewModel, onNavigate: (String) -> Unit) {
    val list by vm.payables.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ShowPaidToggle(vm) }
        item {
            val open = list.filter { it.status != DebtStatus.PAID }
            LabeledAmount("Total por pagar", open.sumOf { it.remaining }, emphasize = true)
        }
        if (list.isEmpty()) {
            item {
                EmptyState(
                    Icons.Outlined.AccountBalance, "Sin pagos pendientes a proveedores",
                    "Registra facturas por pagar y te avisaremos antes de que venzan.",
                    "Agregar pago",
                ) { onNavigate(Routes.payable(0)) }
            }
        }
        items(list, key = { it.id }) { p ->
            SectionCard(onClick = { onNavigate(Routes.payable(p.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.supplierName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${p.documentType.label}${p.documentNumber?.let { " N° $it" } ?: ""} · vence ${p.dueDate.cl()} (${daysText(p.daysToDue)})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(clp(p.remaining), style = MaterialTheme.typography.titleMedium)
                        StatusChip(p.status.label, statusColor(p.status))
                    }
                }
            }
        }
        item { Spacer(Modifier.size(72.dp)) }
    }
}

@Composable
private fun TaxesTab(vm: FinanceViewModel) {
    val year by vm.taxYear.collectAsStateWithLifecycle()
    val months by vm.months.collectAsStateWithLifecycle()
    val annual by vm.annual.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { MonthBar("Año comercial $year", onPrev = { vm.shiftTaxYear(-1) }, onNext = { vm.shiftTaxYear(1) }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.exportTaxPdf(context) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PictureAsPdf, null)
                    Spacer(Modifier.size(6.dp))
                    Text("PDF")
                }
                OutlinedButton(onClick = { vm.exportTaxCsv(context) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.TableChart, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Excel / CSV")
                }
            }
        }
        item { Disclaimer() }
        annual?.let { a -> item { AnnualCard(a) } }
        item { Text("Desglose mensual (F29)", style = MaterialTheme.typography.titleMedium) }
        items(months, key = { "${it.year}-${it.month}" }) { m -> MonthTaxCard(m) { vm.setFiled(m, it) } }
    }
}

@Composable
private fun AnnualCard(a: AnnualTax) {
    SectionCard("Impuesto a la Renta estimado") {
        LabeledAmount("Ingresos netos acumulados", a.incomeNet)
        LabeledAmount("Costos y gastos netos", a.costsNet)
        LabeledAmount("Base imponible", a.base)
        LabeledAmount("Impuesto Primera Categoría (${pct(a.idpcRate)})", a.tax)
        LabeledAmount("PPM pagados", a.ppmPaid)
        HorizontalDivider()
        if (a.possibleRefund) {
            LabeledAmount("Posible devolución en Operación Renta", -a.balance, emphasize = true, color = Olive)
        } else {
            LabeledAmount("Saldo estimado a pagar", a.balance, emphasize = true)
        }
        if (a.monthsElapsed > 0u) {
            Text("Proyección a diciembre (promedio de ${a.monthsElapsed} meses)", style = MaterialTheme.typography.titleSmall)
            LabeledAmount("Base proyectada", a.projectedBase)
            LabeledAmount("Impuesto proyectado", a.projectedTax)
            LabeledAmount("PPM proyectados", a.projectedPpm)
            LabeledAmount("Saldo proyectado", a.projectedBalance)
        }
        Text(a.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthTaxCard(m: MonthlyTax, onFiled: (Boolean) -> Unit) {
    SectionCard(
        "${monthName(m.month.toInt())} ${m.year}",
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Declarado", style = MaterialTheme.typography.bodySmall)
                Checkbox(checked = m.filed, onCheckedChange = onFiled)
            }
        },
    ) {
        LabeledAmount("IVA débito (ventas)", m.ivaDebit)
        LabeledAmount("IVA crédito (compras con factura)", m.ivaCredit)
        if (m.remanentePrev > 0) LabeledAmount("Remanente mes anterior", m.remanentePrev)
        if (m.remanenteNext > 0) {
            LabeledAmount("Remanente a favor (pasa al mes siguiente)", m.remanenteNext, color = Olive)
        } else {
            LabeledAmount("IVA a pagar", m.ivaToPay)
        }
        LabeledAmount("PPM (${pct(m.ppmRate)} de ${clp(m.ppmBase)})", m.ppm)
        HorizontalDivider()
        LabeledAmount("Total F29", m.totalF29, emphasize = true)
        Text(
            "Vence el ${m.dueDate.cl()}" + if (!m.filed && m.daysToDue >= 0) " (${daysText(m.daysToDue)})" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (!m.filed && m.daysToDue in 0L..3L) SemLiquidar else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
