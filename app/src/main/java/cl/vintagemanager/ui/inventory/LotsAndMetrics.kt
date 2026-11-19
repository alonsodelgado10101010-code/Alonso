package cl.vintagemanager.ui.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.vintagemanager.core.DocumentType
import cl.vintagemanager.core.InventoryMetrics
import cl.vintagemanager.core.Lot
import cl.vintagemanager.core.LotInput
import cl.vintagemanager.core.PaymentMethod
import cl.vintagemanager.core.Supplier
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.ui.components.BaseViewModel
import cl.vintagemanager.ui.components.ConfirmDialog
import cl.vintagemanager.ui.components.DateField
import cl.vintagemanager.ui.components.DropdownField
import cl.vintagemanager.ui.components.EmptyState
import cl.vintagemanager.ui.components.LabeledAmount
import cl.vintagemanager.ui.components.LabeledText
import cl.vintagemanager.ui.components.LoadingBox
import cl.vintagemanager.ui.components.MoneyField
import cl.vintagemanager.ui.components.ScreenScaffold
import cl.vintagemanager.ui.components.SectionCard
import cl.vintagemanager.ui.components.TextInput
import cl.vintagemanager.ui.navigation.Routes
import cl.vintagemanager.util.cl
import cl.vintagemanager.util.clp
import cl.vintagemanager.util.label
import cl.vintagemanager.util.pct
import cl.vintagemanager.util.today
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// ───────────────────────────── Lotes / fardos ─────────────────────────────

@HiltViewModel
class LotsViewModel @Inject constructor(repo: VintageRepository) : BaseViewModel(repo) {
    private val _lots = MutableStateFlow<List<Lot>>(emptyList())
    val lots = _lots.asStateFlow()
    private val _suppliers = MutableStateFlow<List<Supplier>>(emptyList())
    val suppliers = _suppliers.asStateFlow()

    init {
        reloadOnChanges {
            repo.query { listLots() }.onSuccess { _lots.value = it }
            repo.query { listSuppliers() }.onSuccess { _suppliers.value = it }
        }
    }

    fun add(input: LotInput) = mutate("Lote registrado. Asigna prendas al lote para prorratear su costo.", { addLot(input) })

    fun delete(id: Long) = mutate("Lote eliminado", { deleteLot(id) })
}

@Composable
fun LotsScreen(onBack: () -> Unit, vm: LotsViewModel = hiltViewModel()) {
    val lots by vm.lots.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Lot?>(null) }

    ScreenScaffold(
        title = "Lotes y fardos",
        onBack = onBack,
        message = message,
        onMessageShown = vm::consumeMessage,
        fab = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Nuevo lote") } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "El costo total de cada lote se reparte automáticamente entre sus prendas, en proporción a su precio de venta.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (lots.isEmpty()) {
                item {
                    EmptyState(Icons.Filled.Inventory2, "Sin lotes registrados", "Registra tus fardos o compras a particulares para conocer el costo real de cada prenda.", "Registrar lote") { adding = true }
                }
            }
            items(lots, key = { it.id }) { l ->
                SectionCard(l.name, trailing = { IconButton(onClick = { deleting = l }) { Icon(Icons.Filled.Delete, "Eliminar") } }) {
                    LabeledAmount("Costo total", l.totalCost)
                    LabeledText("Prendas", "${l.garmentCount}")
                    if (l.garmentCount > 0u) LabeledAmount("Costo promedio por prenda", l.totalCost / l.garmentCount.toLong())
                    LabeledText("Compra", "${l.purchaseDate.cl()} · ${l.documentType.label}")
                    suppliers.firstOrNull { it.id == l.supplierId }?.let { LabeledText("Proveedor", it.name) }
                }
            }
        }
    }
    if (adding) LotDialog(suppliers, onSave = { vm.add(it) }, onDismiss = { adding = false })
    deleting?.let { l ->
        ConfirmDialog("¿Eliminar «${l.name}»?", "Las prendas quedarán sin lote y conservarán su costo actual.", "Eliminar", { vm.delete(l.id) }, { deleting = null })
    }
}

@Composable
private fun LotDialog(suppliers: List<Supplier>, onSave: (LotInput) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var supplier by remember { mutableStateOf<Supplier?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var total by remember { mutableStateOf("") }
    var doc by remember { mutableStateOf(DocumentType.SIN_DOCUMENTO) }
    var registerExpense by remember { mutableStateOf(true) }
    var method by remember { mutableStateOf(PaymentMethod.TRANSFER) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo lote / fardo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextInput(name, { name = it }, "Nombre (ej. Fardo invierno USA)")
                DropdownField("Proveedor", listOf<Supplier?>(null) + suppliers, supplier, { it?.name ?: "Particular / sin ficha" }, { supplier = it })
                DateField(date, { date = it }, "Fecha de compra")
                MoneyField(total, { total = it }, "Costo total")
                DropdownField("Documento", DocumentType.entries.toList(), doc, { it.label }, { doc = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Registrar el egreso en Finanzas", modifier = Modifier.weight(1f))
                    Switch(checked = registerExpense, onCheckedChange = { registerExpense = it })
                }
                if (registerExpense) {
                    DropdownField("Medio de pago", PaymentMethod.entries.toList(), method, { it.label }, { method = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && total.toLongOrNull() != null, onClick = {
                onSave(LotInput(name, supplier?.id, date, total.toLong(), doc, "", registerExpense, method))
                onDismiss()
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ───────────────────────────── Métricas ─────────────────────────────

@HiltViewModel
class MetricsViewModel @Inject constructor(repo: VintageRepository) : BaseViewModel(repo) {
    private val _metrics = MutableStateFlow<InventoryMetrics?>(null)
    val metrics = _metrics.asStateFlow()

    init {
        reloadOnChanges { repo.query { inventoryMetrics(today()) }.onSuccess { _metrics.value = it } }
    }
}

@Composable
fun MetricsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, vm: MetricsViewModel = hiltViewModel()) {
    val m by vm.metrics.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Métricas de inventario", onBack = onBack) { padding ->
        val metrics = m
        if (metrics == null) {
            LoadingBox()
            return@ScreenScaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard("Margen promedio de ventas") {
                    LabeledAmount("Margen promedio por prenda", metrics.avgMargin)
                    LabeledText("Margen sobre precio neto", pct(metrics.avgMarginPct))
                }
            }
            item {
                SectionCard("Días promedio de venta por tipo") {
                    metrics.byType.filter { it.sold > 0u }.forEach {
                        LabeledText(it.garmentType.label, "${it.avgDaysToSell} días · ${it.sold} vendidas")
                    }
                    if (metrics.byType.none { it.sold > 0u }) Text("Aún no hay ventas de prendas registradas.")
                }
            }
            item {
                SectionCard("Rotación por época") {
                    metrics.byEra.filter { it.sold + it.inStock > 0u }.forEach { e ->
                        Column {
                            LabeledText(e.era.label, "${pct(e.rotationPct)} · ${e.sold} vendidas / ${e.inStock} en tienda")
                            LinearProgressIndicator(progress = { e.rotationPct.toFloat() }, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            item {
                SectionCard("Prendas con más tiempo en tienda") {
                    metrics.oldest.forEach { v ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${v.garment.garmentType.label} · ${v.garment.brand}", style = MaterialTheme.typography.bodyMedium)
                                Text("Ingresó el ${v.garment.intakeDate.cl()} · ${clp(v.garment.price)}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onNavigate(Routes.garment(v.garment.id)) }) { Text("Ver") }
                        }
                    }
                }
            }
        }
    }
}
