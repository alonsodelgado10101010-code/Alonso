package cl.vintagemanager.ui.inventory

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cl.vintagemanager.core.Condition
import cl.vintagemanager.core.Era
import cl.vintagemanager.core.GarmentInput
import cl.vintagemanager.core.GarmentType
import cl.vintagemanager.core.Lot
import cl.vintagemanager.core.Season
import cl.vintagemanager.core.StoreLocation
import cl.vintagemanager.core.Supplier
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.ui.components.BaseViewModel
import cl.vintagemanager.ui.components.DateField
import cl.vintagemanager.ui.components.DropdownField
import cl.vintagemanager.ui.components.MoneyField
import cl.vintagemanager.ui.components.PhotoPicker
import cl.vintagemanager.ui.components.ScreenScaffold
import cl.vintagemanager.ui.components.TextInput
import cl.vintagemanager.util.label
import cl.vintagemanager.util.today
import cl.vintagemanager.util.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GarmentForm(
    val type: GarmentType = GarmentType.BASICO_POLERA,
    val brand: String = "",
    val era: Era = Era.NOVENTAS,
    val size: String = "",
    val color: String = "",
    val material: String = "",
    val condition: Condition = Condition.MUY_BUENO,
    val season: Season = Season.TODO_EL_ANO,
    val cost: String = "",
    val price: String = "",
    val supplier: Supplier? = null,
    val lot: Lot? = null,
    val intakeDate: LocalDate = LocalDate.now(),
    val location: StoreLocation = StoreLocation.PERCHA,
    val photos: List<String> = emptyList(),
    val description: String = "",
)

@HiltViewModel
class GarmentFormViewModel @Inject constructor(repo: VintageRepository, handle: SavedStateHandle) : BaseViewModel(repo) {
    val id: Long = handle.get<Long>("id") ?: 0L
    val form = MutableStateFlow(GarmentForm())
    private val _suppliers = MutableStateFlow<List<Supplier>>(emptyList())
    val suppliers = _suppliers.asStateFlow()
    private val _lots = MutableStateFlow<List<Lot>>(emptyList())
    val lots = _lots.asStateFlow()
    val savedId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            val sups = repo.query { listSuppliers() }.getOrDefault(emptyList())
            val lots = repo.query { listLots() }.getOrDefault(emptyList())
            _suppliers.value = sups
            _lots.value = lots
            if (id != 0L) {
                repo.query { getGarment(id, today()) }.onSuccess { v ->
                    val g = v.garment
                    form.value = GarmentForm(
                        type = g.garmentType, brand = g.brand, era = g.era, size = g.size, color = g.color,
                        material = g.material, condition = g.condition, season = g.season,
                        cost = g.cost.toString(), price = g.price.toString(),
                        supplier = sups.firstOrNull { it.id == g.supplierId },
                        lot = lots.firstOrNull { it.id == g.lotId },
                        intakeDate = g.intakeDate, location = g.location, photos = g.photoPaths, description = g.description,
                    )
                }.onFailure { notify(it.userMessage()) }
            }
        }
    }

    fun edit(change: GarmentForm.() -> GarmentForm) {
        form.value = form.value.change()
    }

    fun save() {
        val f = form.value
        val price = f.price.toLongOrNull() ?: return notify("Ingresa el precio de venta.")
        val input = GarmentInput(
            garmentType = f.type, brand = f.brand.trim(), era = f.era, size = f.size.trim(), color = f.color.trim(),
            material = f.material.trim(), condition = f.condition, season = f.season,
            cost = f.cost.toLongOrNull() ?: 0L, price = price, supplierId = f.supplier?.id, lotId = f.lot?.id,
            intakeDate = f.intakeDate, location = f.location, photoPaths = f.photos, description = f.description.trim(),
        )
        if (id == 0L) mutate("Prenda agregada", { addGarment(input, today()) }) { savedId.value = it.garment.id }
        else mutate("Cambios guardados", { updateGarment(id, input, today()) }) { savedId.value = it.garment.id }
    }
}

@Composable
fun GarmentFormScreen(onBack: () -> Unit, vm: GarmentFormViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val lots by vm.lots.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val savedId by vm.savedId.collectAsStateWithLifecycle()
    LaunchedEffect(savedId) { if (savedId != null) onBack() }

    ScreenScaffold(
        title = if (vm.id == 0L) "Agregar prenda" else "Editar prenda",
        onBack = onBack,
        message = message,
        onMessageShown = vm::consumeMessage,
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhotoPicker(f.photos, { p -> vm.edit { copy(photos = photos + p) } }, { p -> vm.edit { copy(photos = photos - p) } })
            DropdownField("Tipo de prenda", GarmentType.entries.toList(), f.type, { it.label }, { v -> vm.edit { copy(type = v) } })
            TextInput(f.brand, { v -> vm.edit { copy(brand = v) } }, "Marca")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField("Época", Era.entries.toList(), f.era, { it.label }, { v -> vm.edit { copy(era = v) } }, Modifier.weight(1f))
                TextInput(f.size, { v -> vm.edit { copy(size = v) } }, "Talla", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(f.color, { v -> vm.edit { copy(color = v) } }, "Color", Modifier.weight(1f))
                TextInput(f.material, { v -> vm.edit { copy(material = v) } }, "Material", Modifier.weight(1f))
            }
            DropdownField("Estado de conservación", Condition.entries.toList(), f.condition, { it.label }, { v -> vm.edit { copy(condition = v) } })
            DropdownField("Temporada", Season.entries.toList(), f.season, { it.label }, { v -> vm.edit { copy(season = v) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(f.cost, { v -> vm.edit { copy(cost = v) } }, "Costo", Modifier.weight(1f))
                MoneyField(f.price, { v -> vm.edit { copy(price = v) } }, "Precio venta", Modifier.weight(1f))
            }
            if (f.lot != null) {
                Text(
                    "El costo se calcula automáticamente prorrateando el costo del lote «${f.lot?.name}».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownField("Proveedor", listOf<Supplier?>(null) + suppliers, f.supplier, { it?.name ?: "Sin proveedor" }, { v -> vm.edit { copy(supplier = v) } })
            DropdownField("Lote / fardo de origen", listOf<Lot?>(null) + lots, f.lot, { it?.name ?: "Sin lote" }, { v -> vm.edit { copy(lot = v) } })
            DateField(f.intakeDate, { d -> vm.edit { copy(intakeDate = d) } }, "Fecha de ingreso")
            DropdownField("Ubicación en tienda", StoreLocation.entries.toList(), f.location, { it.label }, { v -> vm.edit { copy(location = v) } })
            TextInput(f.description, { v -> vm.edit { copy(description = v) } }, "Descripción / detalles", singleLine = false)
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("Guardar prenda") }
            Spacer(Modifier.size(24.dp))
        }
    }
}
