package cl.vintagemanager.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cl.vintagemanager.core.Alert
import cl.vintagemanager.core.Dashboard
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.ui.components.BaseViewModel
import cl.vintagemanager.ui.components.Disclaimer
import cl.vintagemanager.ui.components.KpiCard
import cl.vintagemanager.ui.components.LabeledAmount
import cl.vintagemanager.ui.components.LabeledText
import cl.vintagemanager.ui.components.LoadingBox
import cl.vintagemanager.ui.components.ScreenScaffold
import cl.vintagemanager.ui.components.SectionCard
import cl.vintagemanager.ui.navigation.Routes
import cl.vintagemanager.ui.navigation.routeFor
import cl.vintagemanager.ui.theme.Olive
import cl.vintagemanager.ui.theme.SemLiquidar
import cl.vintagemanager.ui.theme.SemYa
import cl.vintagemanager.ui.theme.Terracotta
import cl.vintagemanager.util.cl
import cl.vintagemanager.util.clp
import cl.vintagemanager.util.color
import cl.vintagemanager.util.daysText
import cl.vintagemanager.util.monthName
import cl.vintagemanager.util.today
import cl.vintagemanager.util.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DashboardViewModel @Inject constructor(repo: VintageRepository) : BaseViewModel(repo) {
    private val _month = MutableStateFlow(YearMonth.now())
    val month = _month.asStateFlow()
    private val _data = MutableStateFlow<Dashboard?>(null)
    val data = _data.asStateFlow()
    private val _storeName = MutableStateFlow("")
    val storeName = _storeName.asStateFlow()

    init {
        reloadOnChanges { load() }
    }

    private suspend fun load() {
        val ym = _month.value
        repo.query { dashboard(ym.year, ym.monthValue.toUInt(), today()) }
            .onSuccess { _data.value = it }
            .onFailure { notify(it.userMessage()) }
        repo.query { getSettings().storeName }.onSuccess { _storeName.value = it }
    }

    fun shiftMonth(delta: Long) {
        _month.value = _month.value.plusMonths(delta)
        viewModelScope.launch { load() }
    }
}

@Composable
fun DashboardScreen(onNavigate: (String) -> Unit, vm: DashboardViewModel = hiltViewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val storeName by vm.storeName.collectAsStateWithLifecycle()
    var fabOpen by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = storeName.ifBlank { "Resumen" },
        message = message,
        onMessageShown = vm::consumeMessage,
        actions = {
            val count = data?.attention?.size ?: 0
            IconButton(onClick = { onNavigate(Routes.ALERTS) }) {
                BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
                    Icon(Icons.Filled.Notifications, contentDescription = "Bandeja de alertas")
                }
            }
        },
        fab = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (fabOpen) {
                    ExtendedFloatingActionButton(
                        onClick = { fabOpen = false; onNavigate(Routes.newMovement("INCOME")) },
                        icon = { Icon(Icons.Filled.PointOfSale, null) },
                        text = { Text("Registrar venta") },
                    )
                    ExtendedFloatingActionButton(
                        onClick = { fabOpen = false; onNavigate(Routes.newMovement("EXPENSE")) },
                        icon = { Icon(Icons.Filled.ReceiptLong, null) },
                        text = { Text("Registrar gasto") },
                    )
                    ExtendedFloatingActionButton(
                        onClick = { fabOpen = false; onNavigate(Routes.garmentEdit(0)) },
                        icon = { Icon(Icons.Filled.Checkroom, null) },
                        text = { Text("Agregar prenda") },
                    )
                }
                FloatingActionButton(onClick = { fabOpen = !fabOpen }) {
                    Icon(if (fabOpen) Icons.Filled.Close else Icons.Filled.Add, contentDescription = "Acciones rápidas")
                }
            }
        },
    ) { padding ->
        val d = data
        if (d == null) {
            LoadingBox()
            return@ScreenScaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MonthSelector(month, onPrev = { vm.shiftMonth(-1) }, onNext = { vm.shiftMonth(1) })
            DashboardContent(d, onNavigate)
            Spacer(Modifier.size(80.dp))
        }
    }
}

@Composable
private fun MonthSelector(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Mes anterior") }
        Text(
            "${monthName(month.monthValue)} ${month.year}",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onNext) { Icon(Icons.Filled.ChevronRight, contentDescription = "Mes siguiente") }
    }
}

@Composable
private fun DashboardContent(d: Dashboard, onNavigate: (String) -> Unit) {
    // 1. KPIs
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KpiCard("Ventas del mes", d.salesGross, Icons.AutoMirrored.Filled.TrendingUp, Modifier.weight(1f), Olive, "con IVA")
        KpiCard("Gastos del mes", d.expensesGross, Icons.AutoMirrored.Filled.TrendingDown, Modifier.weight(1f), Terracotta)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KpiCard("Utilidad estimada", d.profitEstimate, Icons.Filled.Savings, Modifier.weight(1f), MaterialTheme.colorScheme.tertiary, "sin IVA")
        KpiCard("Caja disponible", d.cashAvailable, Icons.Filled.AccountBalanceWallet, Modifier.weight(1f), MaterialTheme.colorScheme.secondary, "estimada")
    }

    // 2. Impuestos
    val t = d.tax
    SectionCard("Impuestos del mes (F29)", onClick = { onNavigate(Routes.finance(Routes.TAB_TAXES)) }) {
        if (t.remanenteNext > 0) {
            LabeledAmount("Remanente IVA a favor", t.remanenteNext, color = Olive)
        } else {
            LabeledAmount("IVA a pagar", t.ivaToPay)
        }
        LabeledAmount("PPM estimado", t.ppm)
        HorizontalDivider()
        LabeledAmount("Total a pagar en el F29", t.totalF29, emphasize = true)
        val daysColor = if (t.daysToDue <= 3) SemLiquidar else MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            if (t.filed) "Declarado ✓ · vencía el ${t.dueDate.cl()}"
            else "Vence el ${t.dueDate.cl()} (${daysText(t.daysToDue)})",
            color = if (t.filed) Olive else daysColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Disclaimer(d.disclaimer)
    }

    // 3. Por cobrar / por pagar
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Por cobrar", Modifier.weight(1f), onClick = { onNavigate(Routes.finance(1)) }) {
            Text(clp(d.debts.receivablesTotal), style = MaterialTheme.typography.titleMedium)
            Text("${d.debts.receivablesCount} pendientes", style = MaterialTheme.typography.bodySmall)
            if (d.debts.receivablesOverdue > 0) {
                Text("Vencido: ${clp(d.debts.receivablesOverdue)}", color = SemLiquidar, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
        }
        SectionCard("Por pagar", Modifier.weight(1f), onClick = { onNavigate(Routes.finance(2)) }) {
            Text(clp(d.debts.payablesTotal), style = MaterialTheme.typography.titleMedium)
            Text("${d.debts.payablesCount} pendientes", style = MaterialTheme.typography.bodySmall)
            if (d.debts.payablesOverdue > 0) {
                Text("Vencido: ${clp(d.debts.payablesOverdue)}", color = SemLiquidar, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
        }
    }

    // 4. Inventario
    val inv = d.inventory
    SectionCard("Inventario", onClick = { onNavigate(Routes.inventory()) }) {
        LabeledText("Prendas en tienda", "${inv.inStock}")
        LabeledAmount("Valor a costo", inv.valueCost)
        LabeledAmount("Valor a precio de venta", inv.valuePrice)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountPill("🟠 Rotar ya", inv.rotarYa.toInt(), SemYa) { onNavigate(Routes.inventory(cl.vintagemanager.core.Semaphore.ROTAR_YA)) }
            CountPill("🔴 Liquidar", inv.liquidar.toInt(), SemLiquidar) { onNavigate(Routes.inventory(cl.vintagemanager.core.Semaphore.LIQUIDAR)) }
        }
    }

    // 5. Requiere tu atención
    SectionCard("Requiere tu atención") {
        if (d.attention.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.TaskAlt, null, tint = Olive)
                Spacer(Modifier.width(8.dp))
                Text("Todo al día. ¡Buen trabajo!", style = MaterialTheme.typography.bodyMedium)
            }
        }
        d.attention.forEach { a -> AlertRow(a) { onNavigate(routeFor(a.target)) } }
    }

    // 6. Gráfico
    SectionCard("Ingresos vs. egresos (6 meses)") {
        IncomeExpenseChart(d.chart)
    }
}

@Composable
private fun CountPill(text: String, count: Int, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    androidx.compose.material3.AssistChip(
        onClick = onClick,
        label = { Text("$text: $count", color = color, fontWeight = FontWeight.SemiBold) },
    )
}

@Composable
fun AlertRow(alert: Alert, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(alert.priority.color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(alert.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (alert.body.isNotBlank()) {
                Text(alert.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
