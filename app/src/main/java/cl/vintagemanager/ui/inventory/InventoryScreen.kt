package cl.vintagemanager.ui.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cl.vintagemanager.core.Era
import cl.vintagemanager.core.GarmentStatus
import cl.vintagemanager.core.GarmentType
import cl.vintagemanager.core.GarmentView
import cl.vintagemanager.core.InventoryFilter
import cl.vintagemanager.core.Season
import cl.vintagemanager.core.Semaphore
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.ui.components.BaseViewModel
import cl.vintagemanager.ui.components.DropdownField
import cl.vintagemanager.ui.components.EmptyState
import cl.vintagemanager.ui.components.ScreenScaffold
import cl.vintagemanager.ui.components.SemaphoreBadge
import cl.vintagemanager.ui.components.StatusChip
import cl.vintagemanager.ui.navigation.Routes
import cl.vintagemanager.util.cl
import cl.vintagemanager.util.clp
import cl.vintagemanager.util.color
import cl.vintagemanager.util.emoji
import cl.vintagemanager.util.label
import cl.vintagemanager.util.today
import cl.vintagemanager.util.userMessage
import coil.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InventoryFilterUi(
    val semaphore: Semaphore? = null,
    val type: GarmentType? = null,
    val season: Season? = null,
    val era: Era? = null,
    val size: String = "",
    val query: String = "",
)

@HiltViewModel
class InventoryViewModel @Inject constructor(repo: VintageRepository) : BaseViewModel(repo) {
    val filter = MutableStateFlow(InventoryFilterUi())
    private val _items = MutableStateFlow<List<GarmentView>>(emptyList())
    val items = _items.asStateFlow()
    private val _all = MutableStateFlow<List<GarmentView>>(emptyList())
    val all = _all.asStateFlow()
    val scanned = MutableStateFlow<Long?>(null)

    init {
        reloadOnChanges { load() }
    }

    private suspend fun load() {
        val f = filter.value
        // El orden por Fecha Límite de Rotación lo garantiza el núcleo.
        val coreFilter = InventoryFilter(
            semaphore = f.semaphore, garmentType = f.type, size = f.size.ifBlank { null },
            season = f.season, era = f.era, query = f.query.ifBlank { null }, includeInactive = false,
        )
        repo.query { listInventory(coreFilter, today()) }
            .onSuccess { _items.value = it }
            .onFailure { notify(it.userMessage()) }
        repo.query { listInventory(InventoryFilter(null, null, null, null, null, null, false), today()) }
            .onSuccess { _all.value = it }
    }

    fun setFilter(f: InventoryFilterUi) {
        filter.value = f
        viewModelScope.launch { load() }
    }

    fun onScanned(code: String) = viewModelScope.launch {
        repo.query { findByCode(code, today()) }
            .onSuccess { v -> if (v == null) notify("No se encontró una prenda con el código $code.") else scanned.value = v.garment.id }
            .onFailure { notify(it.userMessage()) }
    }
}

@Composable
fun InventoryScreen(
    initialSemaphore: Semaphore?,
    onNavigate: (String) -> Unit,
    vm: InventoryViewModel = hiltViewModel(),
) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val all by vm.all.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val scanned by vm.scanned.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }

    LaunchedEffect(initialSemaphore) {
        if (initialSemaphore != null) vm.setFilter(InventoryFilterUi(semaphore = initialSemaphore))
    }
    LaunchedEffect(scanned) {
        scanned?.let {
            vm.scanned.value = null
            onNavigate(Routes.garment(it))
        }
    }
    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.onScanned(it) }
    }

    ScreenScaffold(
        title = "Inventario",
        message = message,
        onMessageShown = vm::consumeMessage,
        actions = {
            IconButton(onClick = {
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Apunta al código QR de la etiqueta")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            }) { Icon(Icons.Filled.QrCodeScanner, "Escanear QR") }
            IconButton(onClick = { onNavigate(Routes.LOTS) }) { Icon(Icons.Filled.Inventory2, "Lotes y fardos") }
            IconButton(onClick = { onNavigate(Routes.METRICS) }) { Icon(Icons.Filled.Analytics, "Métricas") }
        },
        fab = { FloatingActionButton(onClick = { onNavigate(Routes.garmentEdit(0)) }) { Icon(Icons.Filled.Add, "Agregar prenda") } },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = filter.query,
                        onValueChange = { vm.setFilter(filter.copy(query = it)) },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        placeholder = { Text("Buscar marca, color, SKU…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showFilters = !showFilters }) { Icon(Icons.Filled.Tune, "Más filtros") }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = filter.semaphore == null,
                            onClick = { vm.setFilter(filter.copy(semaphore = null)) },
                            label = { Text("Todas (${all.size})") },
                        )
                    }
                    items(Semaphore.entries.toList()) { s ->
                        val count = all.count { it.semaphore == s }
                        FilterChip(
                            selected = filter.semaphore == s,
                            onClick = { vm.setFilter(filter.copy(semaphore = if (filter.semaphore == s) null else s)) },
                            label = { Text("${s.emoji} ${s.label} ($count)") },
                        )
                    }
                }
            }
            if (showFilters) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DropdownField("Tipo", listOf<GarmentType?>(null) + GarmentType.entries, filter.type, { it?.label ?: "Todos" }, { vm.setFilter(filter.copy(type = it)) })
                        DropdownField("Temporada", listOf<Season?>(null) + Season.entries, filter.season, { it?.label ?: "Todas" }, { vm.setFilter(filter.copy(season = it)) })
                        DropdownField("Época", listOf<Era?>(null) + Era.entries, filter.era, { it?.label ?: "Todas" }, { vm.setFilter(filter.copy(era = it)) })
                        OutlinedTextField(
                            value = filter.size,
                            onValueChange = { vm.setFilter(filter.copy(size = it)) },
                            label = { Text("Talla") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                Text(
                    "Ordenadas por Fecha Límite de Rotación: primero las que pierden valor antes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Filled.Checkroom,
                        if (all.isEmpty()) "Tu inventario está vacío" else "Ninguna prenda coincide",
                        if (all.isEmpty()) "Agrega tu primera prenda o carga los datos de demostración en Ajustes." else "Prueba con otros filtros.",
                        if (all.isEmpty()) "Agregar prenda" else null,
                    ) { onNavigate(Routes.garmentEdit(0)) }
                }
            }
            items(items, key = { it.garment.id }) { v -> GarmentCard(v) { onNavigate(Routes.garment(v.garment.id)) } }
            item { Spacer(Modifier.size(72.dp)) }
        }
    }
}

@Composable
fun GarmentCard(v: GarmentView, onClick: () -> Unit) {
    val g = v.garment
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(v.semaphore.color))
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val photo = g.photoPaths.firstOrNull()
                if (photo != null) {
                    AsyncImage(
                        model = File(photo),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Box(
                        Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Checkroom, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${g.garmentType.label} · ${g.brand}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        listOf(g.era.label, "Talla ${g.size}", g.color).filter { it.isNotBlank() }.joinToString(" · ") + " · ${g.sku}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SemaphoreBadge(v.semaphore)
                        if (g.status != GarmentStatus.EN_STOCK) StatusChip(g.status.label, MaterialTheme.colorScheme.tertiary)
                    }
                    Text(
                        "FLR ${v.flr.cl()} · " + when {
                            v.daysRemaining < 0 -> "vencida hace ${-v.daysRemaining} días"
                            else -> "${v.daysRemaining} días restantes"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(clp(g.price), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}
