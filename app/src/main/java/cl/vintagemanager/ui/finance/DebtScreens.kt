package cl.vintagemanager.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cl.vintagemanager.core.Category
import cl.vintagemanager.core.DebtPayment
import cl.vintagemanager.core.DebtStatus
import cl.vintagemanager.core.DocumentType
import cl.vintagemanager.core.Payable
import cl.vintagemanager.core.PayableInput
import cl.vintagemanager.core.PaymentMethod
import cl.vintagemanager.core.Receivable
import cl.vintagemanager.core.ReceivableConcept
import cl.vintagemanager.core.ReceivableInput
import cl.vintagemanager.core.Supplier
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.ui.components.BaseViewModel
import cl.vintagemanager.ui.components.ConfirmDialog
import cl.vintagemanager.ui.components.DateField
import cl.vintagemanager.ui.components.DropdownField
import cl.vintagemanager.ui.components.LabeledAmount
import cl.vintagemanager.ui.components.MoneyField
import cl.vintagemanager.ui.components.ScreenScaffold
import cl.vintagemanager.ui.components.SectionCard
import cl.vintagemanager.ui.components.StatusChip
import cl.vintagemanager.ui.components.TextInput
import cl.vintagemanager.ui.navigation.Routes
import cl.vintagemanager.util.cl
import cl.vintagemanager.util.clp
import cl.vintagemanager.util.daysText
import cl.vintagemanager.util.expenseCategories
import cl.vintagemanager.util.label
import cl.vintagemanager.util.today
import cl.vintagemanager.util.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ───────────────────────────── Abonos (compartido) ─────────────────────────────

@Composable
fun PaymentDialog(remaining: Long, onConfirm: (Long, LocalDate, PaymentMethod) -> Unit, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf(remaining.toString()) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var method by remember { mutableStateOf(PaymentMethod.TRANSFER) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar abono") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saldo pendiente: ${clp(remaining)}", style = MaterialTheme.typography.bodyMedium)
                MoneyField(amount, { amount = it }, "Monto del abono")
                DateField(date, { date = it }, "Fecha")
                DropdownField("Medio de pago", PaymentMethod.entries.toList(), method, { it.label }, { method = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                amount.toLongOrNull()?.let { onConfirm(it, date, method) }
                onDismiss()
            }) { Text("Registrar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun PaymentsList(payments: List<DebtPayment>) {
    if (payments.isEmpty()) return
    SectionCard("Abonos registrados") {
        payments.forEach { p -> LabeledAmount(p.date.cl(), p.amount) }
        Text(
            "Cada abono generó automáticamente su movimiento en Finanzas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DebtSummaryCard(amount: Long, paid: Long, remaining: Long, status: DebtStatus, due: LocalDate, days: Long) {
    SectionCard("Estado", trailing = { StatusChip(status.label, statusColor(status)) }) {
        LabeledAmount("Monto total", amount)
        LabeledAmount("Abonado", paid)
        LabeledAmount("Saldo pendiente", remaining, emphasize = true)
        if (status != DebtStatus.PAID) {
            Text("Vence el ${due.cl()} (${daysText(days)})", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ───────────────────────────── Cobros ─────────────────────────────

data class ReceivableForm(
    val customer: String = "",
    val amount: String = "",
    val issueDate: LocalDate = LocalDate.now(),
    val dueDate: LocalDate = LocalDate.now().plusDays(30),
    val concept: ReceivableConcept = ReceivableConcept.VENTA_CREDITO,
    val notes: String = "",
)

@HiltViewModel
class ReceivableViewModel @Inject constructor(repo: VintageRepository, handle: SavedStateHandle) : BaseViewModel(repo) {
    val id: Long = handle.get<Long>("id") ?: 0L
    val form = MutableStateFlow(ReceivableForm())
    private val _current = MutableStateFlow<Receivable?>(null)
    val current = _current.asStateFlow()
    val done = MutableStateFlow(false)

    init {
        if (id != 0L) {
            viewModelScope.launch {
                repo.query { getReceivable(id, today()) }.onSuccess { r ->
                    form.value = ReceivableForm(r.customer, r.amount.toString(), r.issueDate, r.dueDate, r.concept, r.notes)
                }.onFailure { notify(it.userMessage()) }
            }
            reloadOnChanges { repo.query { getReceivable(id, today()) }.onSuccess { _current.value = it } }
        }
    }

    fun save() {
        val f = form.value
        val amount = f.amount.toLongOrNull() ?: return notify("Ingresa el monto.")
        val input = ReceivableInput(f.customer, amount, f.issueDate, f.dueDate, f.concept, f.notes)
        if (id == 0L) mutate("Cobro guardado", { addReceivable(input, today()) }) { done.value = true }
        else mutate("Cambios guardados", { updateReceivable(id, input, today()) })
    }

    fun pay(amount: Long, date: LocalDate, method: PaymentMethod) =
        mutate("Abono registrado e ingreso creado", { payReceivable(id, amount, date, method) })

    fun markPaid() = mutate("Cobro pagado. Se creó el ingreso.", { markReceivablePaid(id, today(), PaymentMethod.TRANSFER) })

    fun delete() = mutate(null, { deleteReceivable(id) }) { done.value = true }
}

@Composable
fun ReceivableScreen(onBack: () -> Unit, vm: ReceivableViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    var payOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) onBack() }

    ScreenScaffold(
        title = if (vm.id == 0L) "Nuevo cobro" else "Cobro",
        onBack = onBack,
        message = message,
        onMessageShown = vm::consumeMessage,
        actions = { if (vm.id != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar") } },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            current?.let { r ->
                DebtSummaryCard(r.amount, r.paidAmount, r.remaining, r.status, r.dueDate, r.daysToDue)
                if (r.status != DebtStatus.PAID) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { payOpen = true }, modifier = Modifier.weight(1f)) { Text("Registrar abono") }
                        Button(onClick = vm::markPaid, modifier = Modifier.weight(1f)) { Text("Marcar pagado") }
                    }
                }
                PaymentsList(r.payments)
            }
            TextInput(form.customer, { v -> vm.form.value = form.copy(customer = v) }, "Cliente")
            MoneyField(form.amount, { v -> vm.form.value = form.copy(amount = v) }, "Monto (con IVA)")
            DropdownField("Concepto", ReceivableConcept.entries.toList(), form.concept, { it.label }, { c -> vm.form.value = form.copy(concept = c) })
            DateField(form.issueDate, { d -> vm.form.value = form.copy(issueDate = d) }, "Fecha de emisión")
            DateField(form.dueDate, { d -> vm.form.value = form.copy(dueDate = d) }, "Fecha de vencimiento")
            TextInput(form.notes, { v -> vm.form.value = form.copy(notes = v) }, "Notas", singleLine = false)
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("Guardar") }
            Spacer(Modifier.size(24.dp))
        }
    }
    current?.let { r ->
        if (payOpen) PaymentDialog(r.remaining, { a, d, m -> vm.pay(a, d, m) }, { payOpen = false })
    }
    if (confirmDelete) {
        ConfirmDialog("¿Eliminar cobro?", "Los movimientos ya generados por abonos se mantienen.", "Eliminar", { vm.delete() }, { confirmDelete = false })
    }
}

// ───────────────────────────── Pagos a proveedores ─────────────────────────────

data class PayableForm(
    val supplier: Supplier? = null,
    val supplierName: String = "",
    val amount: String = "",
    val issueDate: LocalDate = LocalDate.now(),
    val dueDate: LocalDate = LocalDate.now().plusDays(30),
    val documentType: DocumentType = DocumentType.FACTURA,
    val documentNumber: String = "",
    val category: Category = Category.COMPRA_MERCADERIA,
    val notes: String = "",
)

@HiltViewModel
class PayableViewModel @Inject constructor(repo: VintageRepository, handle: SavedStateHandle) : BaseViewModel(repo) {
    val id: Long = handle.get<Long>("id") ?: 0L
    val form = MutableStateFlow(PayableForm())
    private val _current = MutableStateFlow<Payable?>(null)
    val current = _current.asStateFlow()
    private val _suppliers = MutableStateFlow<List<Supplier>>(emptyList())
    val suppliers = _suppliers.asStateFlow()
    val done = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val list = repo.query { listSuppliers() }.getOrDefault(emptyList())
            _suppliers.value = list
            if (id != 0L) {
                repo.query { getPayable(id, today()) }.onSuccess { p ->
                    form.value = PayableForm(
                        supplier = list.firstOrNull { it.id == p.supplierId },
                        supplierName = p.supplierName, amount = p.amount.toString(), issueDate = p.issueDate,
                        dueDate = p.dueDate, documentType = p.documentType, documentNumber = p.documentNumber.orEmpty(),
                        category = p.category, notes = p.notes,
                    )
                }.onFailure { notify(it.userMessage()) }
            }
        }
        if (id != 0L) reloadOnChanges { repo.query { getPayable(id, today()) }.onSuccess { _current.value = it } }
    }

    fun save() {
        val f = form.value
        val amount = f.amount.toLongOrNull() ?: return notify("Ingresa el monto.")
        val input = PayableInput(
            supplierId = f.supplier?.id,
            supplierName = f.supplier?.name ?: f.supplierName,
            amount = amount, issueDate = f.issueDate, dueDate = f.dueDate, documentType = f.documentType,
            documentNumber = f.documentNumber.ifBlank { null }, category = f.category, notes = f.notes,
        )
        if (id == 0L) mutate("Pago guardado", { addPayable(input, today()) }) { done.value = true }
        else mutate("Cambios guardados", { updatePayable(id, input, today()) })
    }

    fun pay(amount: Long, date: LocalDate, method: PaymentMethod) =
        mutate("Abono registrado y egreso creado", { payPayable(id, amount, date, method) })

    fun markPaid() = mutate("Pago registrado. Se creó el egreso.", { markPayablePaid(id, today(), PaymentMethod.TRANSFER) })

    fun delete() = mutate(null, { deletePayable(id) }) { done.value = true }
}

@Composable
fun PayableScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, vm: PayableViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    var payOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) onBack() }

    ScreenScaffold(
        title = if (vm.id == 0L) "Nuevo pago a proveedor" else "Pago a proveedor",
        onBack = onBack,
        message = message,
        onMessageShown = vm::consumeMessage,
        actions = { if (vm.id != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar") } },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            current?.let { p ->
                DebtSummaryCard(p.amount, p.paidAmount, p.remaining, p.status, p.dueDate, p.daysToDue)
                if (p.status != DebtStatus.PAID) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { payOpen = true }, modifier = Modifier.weight(1f)) { Text("Registrar abono") }
                        Button(onClick = vm::markPaid, modifier = Modifier.weight(1f)) { Text("Marcar pagado") }
                    }
                }
                PaymentsList(p.payments)
            }
            val options: List<Supplier?> = listOf<Supplier?>(null) + suppliers
            Row(verticalAlignment = Alignment.CenterVertically) {
                DropdownField(
                    "Proveedor registrado", options, form.supplier, { it?.name ?: "Otro (escribir nombre)" },
                    { s -> vm.form.value = form.copy(supplier = s) }, Modifier.weight(1f),
                )
                TextButton(onClick = { onNavigate(Routes.SUPPLIERS) }) { Text("Fichas") }
            }
            if (form.supplier == null) {
                TextInput(form.supplierName, { v -> vm.form.value = form.copy(supplierName = v) }, "Nombre del proveedor")
            }
            MoneyField(form.amount, { v -> vm.form.value = form.copy(amount = v) }, "Monto total (con IVA)")
            DropdownField("Categoría", expenseCategories, form.category, { it.label }, { c -> vm.form.value = form.copy(category = c) })
            DropdownField("Documento", DocumentType.entries.toList(), form.documentType, { it.label }, { d -> vm.form.value = form.copy(documentType = d) })
            TextInput(form.documentNumber, { v -> vm.form.value = form.copy(documentNumber = v) }, "N° de documento")
            DateField(form.issueDate, { d -> vm.form.value = form.copy(issueDate = d) }, "Fecha de emisión")
            DateField(form.dueDate, { d -> vm.form.value = form.copy(dueDate = d) }, "Fecha de vencimiento")
            TextInput(form.notes, { v -> vm.form.value = form.copy(notes = v) }, "Notas", singleLine = false)
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("Guardar") }
            Spacer(Modifier.size(24.dp))
        }
    }
    current?.let { p ->
        if (payOpen) PaymentDialog(p.remaining, { a, d, m -> vm.pay(a, d, m) }, { payOpen = false })
    }
    if (confirmDelete) {
        ConfirmDialog("¿Eliminar pago pendiente?", "Los egresos ya generados se mantienen.", "Eliminar", { vm.delete() }, { confirmDelete = false })
    }
}
