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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import cl.vintagemanager.core.AmountBreakdown
import cl.vintagemanager.core.Category
import cl.vintagemanager.core.DocumentType
import cl.vintagemanager.core.MovementInput
import cl.vintagemanager.core.MovementKind
import cl.vintagemanager.core.PaymentMethod
import cl.vintagemanager.core.SaleInput
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.ui.components.BaseViewModel
import cl.vintagemanager.ui.components.ConfirmDialog
import cl.vintagemanager.ui.components.DateField
import cl.vintagemanager.ui.components.DropdownField
import cl.vintagemanager.ui.components.LabeledAmount
import cl.vintagemanager.ui.components.MoneyField
import cl.vintagemanager.ui.components.PhotoPicker
import cl.vintagemanager.ui.components.ScreenScaffold
import cl.vintagemanager.ui.components.SectionCard
import cl.vintagemanager.ui.components.TextInput
import cl.vintagemanager.util.categoriesFor
import cl.vintagemanager.util.documentsFor
import cl.vintagemanager.util.label
import cl.vintagemanager.util.today
import cl.vintagemanager.util.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MovementForm(
    val kind: MovementKind = MovementKind.INCOME,
    val date: LocalDate = LocalDate.now(),
    val amount: String = "",
    val includesIva: Boolean = true,
    val category: Category = Category.VENTA_TIENDA,
    val paymentMethod: PaymentMethod = PaymentMethod.DEBIT,
    val documentType: DocumentType = DocumentType.BOLETA,
    val documentNumber: String = "",
    val description: String = "",
    val photoPath: String? = null,
    val garmentSku: String = "",
    val garmentId: Long? = null,
)

@HiltViewModel
class MovementFormViewModel @Inject constructor(
    repo: VintageRepository,
    handle: SavedStateHandle,
) : BaseViewModel(repo) {
    val id: Long = handle.get<Long>("id") ?: 0L
    private val _form = MutableStateFlow(MovementForm())
    val form = _form.asStateFlow()
    private val _preview = MutableStateFlow<AmountBreakdown?>(null)
    val preview = _preview.asStateFlow()
    val saved = MutableStateFlow(false)

    init {
        val kindArg = handle.get<String>("kind").orEmpty()
        val garmentArg = handle.get<Long>("garment") ?: 0L
        if (id == 0L) {
            val kind = MovementKind.entries.firstOrNull { it.name == kindArg } ?: MovementKind.INCOME
            _form.value = defaultsFor(kind)
            if (garmentArg > 0) {
                viewModelScope.launch {
                    repo.query { getGarment(garmentArg, today()) }.onSuccess { g ->
                        update {
                            copy(
                                garmentId = g.garment.id,
                                garmentSku = g.garment.sku,
                                amount = g.garment.price.toString(),
                                description = "Venta ${g.garment.sku}",
                            )
                        }
                    }
                }
            }
        } else {
            viewModelScope.launch {
                repo.query { getMovement(id) }.onSuccess { m ->
                    val sku = m.garmentId?.let { gid -> repo.query { getGarment(gid, today()).garment.sku }.getOrNull() }.orEmpty()
                    _form.value = MovementForm(
                        kind = m.kind, date = m.date, amount = m.gross.toString().takeIf { m.includesIva } ?: m.net.toString(),
                        includesIva = m.includesIva, category = m.category, paymentMethod = m.paymentMethod,
                        documentType = m.documentType, documentNumber = m.documentNumber.orEmpty(),
                        description = m.description, photoPath = m.photoPath, garmentSku = sku, garmentId = m.garmentId,
                    )
                    refreshPreview()
                }.onFailure { notify(it.userMessage()) }
            }
        }
    }

    private fun defaultsFor(kind: MovementKind) = MovementForm(
        kind = kind,
        category = categoriesFor(kind).first(),
        documentType = if (kind == MovementKind.INCOME) DocumentType.BOLETA else DocumentType.FACTURA,
    )

    fun update(change: MovementForm.() -> MovementForm) {
        _form.value = _form.value.change()
        refreshPreview()
    }

    fun setKind(kind: MovementKind) {
        if (kind == _form.value.kind) return
        update { defaultsFor(kind).copy(date = date, amount = amount, description = description, photoPath = photoPath) }
    }

    private fun refreshPreview() {
        val f = _form.value
        val amount = f.amount.toLongOrNull()
        if (amount == null || amount <= 0) {
            _preview.value = null
            return
        }
        viewModelScope.launch {
            repo.query { previewAmounts(amount, f.includesIva, f.kind, f.documentType) }.onSuccess { _preview.value = it }
        }
    }

    fun save() {
        val f = _form.value
        val amount = f.amount.toLongOrNull()
        if (amount == null || amount <= 0) {
            notify("Ingresa un monto mayor a cero.")
            return
        }
        viewModelScope.launch {
            // Resolver prenda asociada por SKU (opcional, solo ventas).
            val garmentId: Long? = if (f.kind == MovementKind.INCOME && f.garmentSku.isNotBlank()) {
                val found = repo.query { findByCode(f.garmentSku, today()) }.getOrNull()
                if (found == null) {
                    notify("No existe una prenda con SKU ${f.garmentSku}.")
                    return@launch
                }
                found.garment.id
            } else {
                null
            }
            val input = MovementInput(
                kind = f.kind, date = f.date, amount = amount, includesIva = f.includesIva, category = f.category,
                paymentMethod = f.paymentMethod, documentType = f.documentType,
                documentNumber = f.documentNumber.ifBlank { null }, description = f.description.trim(),
                photoPath = f.photoPath, garmentId = garmentId,
            )
            val result = when {
                id != 0L -> repo.mutate { updateMovement(id, input) }
                // Nueva venta de una prenda: se descuenta del stock y se crea el ingreso con su IVA.
                garmentId != null && f.includesIva -> {
                    val sale = SaleInput(garmentId, amount, f.date, f.paymentMethod, f.documentType, f.category)
                    repo.mutate { sellGarment(sale) }
                }
                else -> repo.mutate { addMovement(input) }
            }
            result.onSuccess { saved.value = true }.onFailure { notify(it.userMessage()) }
        }
    }

    fun delete() {
        mutate(block = { deleteMovement(id) }, onSuccess = { saved.value = true })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MovementFormScreen(onBack: () -> Unit, vm: MovementFormViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) onBack() }

    ScreenScaffold(
        title = if (vm.id == 0L) (if (form.kind == MovementKind.INCOME) "Registrar ingreso" else "Registrar egreso") else "Editar movimiento",
        onBack = onBack,
        message = message,
        onMessageShown = vm::consumeMessage,
        actions = {
            if (vm.id != 0L) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar") }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MovementKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = form.kind == k,
                        onClick = { vm.setKind(k) },
                        shape = SegmentedButtonDefaults.itemShape(i, MovementKind.entries.size),
                    ) { Text(k.label) }
                }
            }
            DateField(form.date, { d -> vm.update { copy(date = d) } }, "Fecha")
            MoneyField(form.amount, { v -> vm.update { copy(amount = v) } }, "Monto")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("¿El monto incluye IVA?", modifier = Modifier.weight(1f))
                Switch(checked = form.includesIva, onCheckedChange = { v -> vm.update { copy(includesIva = v) } })
            }
            DropdownField("Categoría", categoriesFor(form.kind), form.category, { it.label }, { c -> vm.update { copy(category = c) } })
            DropdownField("Medio de pago", PaymentMethod.entries.toList(), form.paymentMethod, { it.label }, { p -> vm.update { copy(paymentMethod = p) } })
            DropdownField("Tipo de documento", documentsFor(form.kind), form.documentType, { it.label }, { d -> vm.update { copy(documentType = d) } })
            if (form.documentType != DocumentType.SIN_DOCUMENTO) {
                TextInput(form.documentNumber, { v -> vm.update { copy(documentNumber = v) } }, "N° de documento")
            }
            TextInput(form.description, { v -> vm.update { copy(description = v) } }, "Descripción", singleLine = false)
            if (form.kind == MovementKind.INCOME) {
                TextInput(form.garmentSku, { v -> vm.update { copy(garmentSku = v.uppercase()) } }, "Prenda asociada (SKU, opcional)")
            }
            PhotoPicker(
                paths = listOfNotNull(form.photoPath),
                onAdd = { p -> vm.update { copy(photoPath = p) } },
                onRemove = { vm.update { copy(photoPath = null) } },
                max = 1,
                label = "Foto del comprobante (opcional)",
            )

            preview?.let { p ->
                SectionCard("Desglose") {
                    LabeledAmount("Neto", p.net)
                    LabeledAmount("IVA", p.iva)
                    LabeledAmount("Total", p.gross, emphasize = true)
                    if (form.kind == MovementKind.EXPENSE && p.iva > 0) {
                        Text(
                            if (p.ivaCreditable) "Este IVA se usa como crédito fiscal en tu F29."
                            else "Las boletas de compra no dan crédito fiscal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("Guardar") }
            Spacer(Modifier.size(24.dp))
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "¿Eliminar movimiento?",
            text = "Si era la venta de una prenda, la prenda vuelve a stock. Esta acción no se puede deshacer.",
            confirmLabel = "Eliminar",
            onConfirm = vm::delete,
            onDismiss = { confirmDelete = false },
        )
    }
}
