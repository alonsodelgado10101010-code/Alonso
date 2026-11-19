package cl.vintagemanager.ui.inventory

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.print.PrintHelper
import cl.vintagemanager.core.Category
import cl.vintagemanager.core.DiscountPreview
import cl.vintagemanager.core.DocumentType
import cl.vintagemanager.core.GarmentStatus
import cl.vintagemanager.core.GarmentView
import cl.vintagemanager.core.PaymentMethod
import cl.vintagemanager.core.PriceChange
import cl.vintagemanager.core.QrMatrix
import cl.vintagemanager.core.SaleInput
import cl.vintagemanager.core.StoreLocation
import cl.vintagemanager.core.qrMatrix
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.ui.components.BaseViewModel
import cl.vintagemanager.ui.components.ConfirmDialog
import cl.vintagemanager.ui.components.DateField
import cl.vintagemanager.ui.components.DropdownField
import cl.vintagemanager.ui.components.LabeledAmount
import cl.vintagemanager.ui.components.LabeledText
import cl.vintagemanager.ui.components.LoadingBox
import cl.vintagemanager.ui.components.MoneyField
import cl.vintagemanager.ui.components.ScreenScaffold
import cl.vintagemanager.ui.components.SectionCard
import cl.vintagemanager.ui.components.SemaphoreBadge
import cl.vintagemanager.ui.navigation.Routes
import cl.vintagemanager.ui.theme.Olive
import cl.vintagemanager.ui.theme.SemLiquidar
import cl.vintagemanager.util.cl
import cl.vintagemanager.util.clp
import cl.vintagemanager.util.incomeCategories
import cl.vintagemanager.util.label
import cl.vintagemanager.util.today
import cl.vintagemanager.util.userMessage
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class GarmentDetailViewModel @Inject constructor(repo: VintageRepository, handle: SavedStateHandle) : BaseViewModel(repo) {
    val id: Long = handle.get<Long>("id") ?: 0L
    private val _view = MutableStateFlow<GarmentView?>(null)
    val view = _view.asStateFlow()
    private val _history = MutableStateFlow<List<PriceChange>>(emptyList())
    val history = _history.asStateFlow()
    private val _preview = MutableStateFlow<DiscountPreview?>(null)
    val preview = _preview.asStateFlow()
    val deleted = MutableStateFlow(false)

    init {
        reloadOnChanges {
            repo.query { getGarment(id, today()) }.onSuccess { _view.value = it }.onFailure { notify(it.userMessage()) }
            repo.query { priceHistory(id) }.onSuccess { _history.value = it }
        }
    }

    /** El núcleo calcula precio rebajado, margen y si queda bajo costo. */
    fun previewDiscount(pct: Int) = viewModelScope.launch {
        repo.query { previewDiscount(id, pct.toUInt()) }.onSuccess { _preview.value = it }
    }

    fun clearPreview() {
        _preview.value = null
    }

    fun applyDiscount(pct: Int) = mutate("Rebaja del $pct% aplicada", { applyDiscount(id, pct.toUInt(), today()) }) { clearPreview() }

    fun sell(price: Long, date: LocalDate, method: PaymentMethod, doc: DocumentType, category: Category) =
        mutate("¡Venta registrada! Se creó el ingreso con su IVA.", {
            sellGarment(SaleInput(id, price, date, method, doc, category))
        })

    fun setStatus(status: GarmentStatus) = mutate("Estado actualizado", { setGarmentStatus(id, status, today()) })

    fun setLocation(location: StoreLocation) = mutate("Ubicación actualizada", { setGarmentLocation(id, location) })

    fun delete() = mutate("Prenda eliminada", { deleteGarment(id) }) { deleted.value = true }
}

/** Dibuja la etiqueta (QR + SKU + precio) como Bitmap para mostrar e imprimir. */
fun labelBitmap(matrix: QrMatrix, sku: String, price: String, scale: Int = 10): Bitmap {
    val size = matrix.size.toInt()
    val quiet = 4
    val qrPx = (size + quiet * 2) * scale
    val textHeight = 4 * scale + 40
    val bmp = Bitmap.createBitmap(qrPx, qrPx + textHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(AColor.WHITE)
    val black = Paint().apply { color = AColor.BLACK }
    for (y in 0 until size) for (x in 0 until size) {
        if (matrix.modules[y * size + x]) {
            val left = ((x + quiet) * scale).toFloat()
            val top = ((y + quiet) * scale).toFloat()
            canvas.drawRect(left, top, left + scale, top + scale, black)
        }
    }
    val text = Paint().apply {
        color = AColor.BLACK
        textSize = 3f * scale
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }
    canvas.drawText(sku, qrPx / 2f, qrPx + 2f * scale, text)
    canvas.drawText(price, qrPx / 2f, qrPx + 5.5f * scale, text)
    return bmp
}

@Composable
fun GarmentDetailScreen(onBack: () -> Unit, onNavigate: (String) -> Unit, vm: GarmentDetailViewModel = hiltViewModel()) {
    val view by vm.view.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    var discountOpen by remember { mutableStateOf(false) }
    var sellOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(deleted) { if (deleted) onBack() }

    ScreenScaffold(
        title = view?.garment?.sku ?: "Prenda",
        onBack = onBack,
        message = message,
        onMessageShown = vm::consumeMessage,
        actions = {
            IconButton(onClick = { onNavigate(Routes.garmentEdit(vm.id)) }) { Icon(Icons.Filled.Edit, "Editar") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar") }
        },
    ) { padding ->
        val v = view
        if (v == null) {
            LoadingBox()
            return@ScreenScaffold
        }
        val g = v.garment
        val active = g.status != GarmentStatus.VENDIDA && g.status != GarmentStatus.DONADA
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (g.photoPaths.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(g.photoPaths) { p ->
                        AsyncImage(
                            model = File(p),
                            contentDescription = "Foto de la prenda",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(180.dp).clip(RoundedCornerShape(16.dp)),
                        )
                    }
                }
            }
            Text("${g.garmentType.label} · ${g.brand}", style = MaterialTheme.typography.headlineSmall)
            Text(clp(g.price), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)

            // FEFO
            SectionCard("Rotación (FEFO)", trailing = { SemaphoreBadge(v.semaphore) }) {
                LabeledText("Fecha Límite de Rotación", v.flr.cl())
                LabeledText(
                    "Días restantes",
                    if (v.daysRemaining < 0) "Vencida hace ${-v.daysRemaining} días" else "${v.daysRemaining}",
                )
                if (g.status == GarmentStatus.EN_REPARACION) {
                    Text("En reparación/lavandería: la FLR está pausada.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Sugerencia: ${v.suggestedAction}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (active) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            discountOpen = true
                            vm.previewDiscount(v.suggestedDiscountPct.toInt().takeIf { it > 0 } ?: 10)
                        }, modifier = Modifier.weight(1f)) { Text("Aplicar rebaja") }
                        Button(onClick = { sellOpen = true }, modifier = Modifier.weight(1f)) { Text("Vender") }
                    }
                }
            }

            SectionCard("Ficha") {
                LabeledText("Época", g.era.label)
                LabeledText("Talla", g.size.ifBlank { "—" })
                LabeledText("Color", g.color.ifBlank { "—" })
                LabeledText("Material", g.material.ifBlank { "—" })
                LabeledText("Conservación", g.condition.label)
                LabeledText("Temporada", g.season.label)
                LabeledText("Ingreso", g.intakeDate.cl())
                LabeledAmount("Costo", g.cost)
                if (g.soldPrice != null) LabeledAmount("Vendida en (${g.soldDate?.cl()})", g.soldPrice ?: 0L)
                if (g.description.isNotBlank()) Text(g.description, style = MaterialTheme.typography.bodyMedium)
            }

            if (active) {
                SectionCard("Estado y ubicación") {
                    DropdownField(
                        "Estado",
                        listOf(GarmentStatus.EN_STOCK, GarmentStatus.RESERVADA, GarmentStatus.EN_REPARACION, GarmentStatus.DONADA),
                        g.status, { it.label }, { vm.setStatus(it) },
                    )
                    DropdownField("Ubicación", StoreLocation.entries.toList(), g.location, { it.label }, { vm.setLocation(it) })
                }
            }

            // Etiqueta QR imprimible
            val matrix = remember(v.qrPayload) { runCatching { qrMatrix(v.qrPayload) }.getOrNull() }
            if (matrix != null) {
                val bitmap = remember(matrix, g.price) { labelBitmap(matrix, g.sku, clp(g.price)) }
                SectionCard("Etiqueta QR") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(bitmap.asImageBitmap(), contentDescription = "Código QR ${g.sku}", modifier = Modifier.size(120.dp))
                        Spacer(Modifier.size(12.dp))
                        OutlinedButton(onClick = {
                            PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }
                                .printBitmap("Etiqueta ${g.sku}", bitmap)
                        }) {
                            Icon(Icons.Filled.Print, null)
                            Spacer(Modifier.size(6.dp))
                            Text("Imprimir")
                        }
                    }
                }
            }

            if (history.isNotEmpty()) {
                SectionCard("Historial de precios") {
                    history.forEach { h ->
                        LabeledText("${h.date.cl()} · ${h.reason}", "${clp(h.oldPrice)} → ${clp(h.newPrice)}")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    val v = view
    if (discountOpen && v != null) {
        DiscountDialog(
            initialPct = v.suggestedDiscountPct.toInt().takeIf { it > 0 } ?: 10,
            preview = preview,
            onPctChange = { vm.previewDiscount(it) },
            onConfirm = { vm.applyDiscount(it) },
            onDismiss = { discountOpen = false; vm.clearPreview() },
        )
    }
    if (sellOpen && v != null) {
        SellDialog(v.garment.price, onConfirm = { p, d, m, doc, c -> vm.sell(p, d, m, doc, c) }, onDismiss = { sellOpen = false })
    }
    if (confirmDelete) {
        ConfirmDialog("¿Eliminar prenda?", "Se borrará la ficha y su historial de precios.", "Eliminar", { vm.delete() }, { confirmDelete = false })
    }
}

@Composable
private fun DiscountDialog(
    initialPct: Int,
    preview: DiscountPreview?,
    onPctChange: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var pct by remember { mutableFloatStateOf(initialPct.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rebaja de ${pct.roundToInt()}%") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = pct,
                    onValueChange = { pct = it },
                    onValueChangeFinished = { onPctChange(pct.roundToInt()) },
                    valueRange = 5f..70f,
                    steps = 12,
                )
                preview?.let { p ->
                    LabeledAmount("Precio actual", p.oldPrice)
                    LabeledAmount("Nuevo precio", p.newPrice, emphasize = true)
                    LabeledAmount("Costo", p.cost)
                    LabeledAmount(
                        "Margen resultante (${p.marginPct.multiply(BigDecimal(100)).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',')}%)",
                        p.margin,
                        color = if (p.belowCost) SemLiquidar else Olive,
                    )
                    if (p.belowCost) {
                        Text(
                            "⚠️ Con esta rebaja la prenda queda bajo su costo.",
                            color = SemLiquidar,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pct.roundToInt()); onDismiss() }) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun SellDialog(
    price: Long,
    onConfirm: (Long, LocalDate, PaymentMethod, DocumentType, Category) -> Unit,
    onDismiss: () -> Unit,
) {
    var amount by remember { mutableStateOf(price.toString()) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var method by remember { mutableStateOf(PaymentMethod.DEBIT) }
    var doc by remember { mutableStateOf(DocumentType.BOLETA) }
    var category by remember { mutableStateOf(Category.VENTA_TIENDA) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar venta") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MoneyField(amount, { amount = it }, "Precio de venta (con IVA)")
                DateField(date, { date = it }, "Fecha")
                DropdownField("Medio de pago", PaymentMethod.entries.toList(), method, { it.label }, { method = it })
                DropdownField("Documento", listOf(DocumentType.BOLETA, DocumentType.FACTURA, DocumentType.SIN_DOCUMENTO), doc, { it.label }, { doc = it })
                DropdownField("Canal", incomeCategories, category, { it.label }, { category = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                amount.toLongOrNull()?.let { onConfirm(it, date, method, doc, category) }
                onDismiss()
            }) { Text("Vender") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
