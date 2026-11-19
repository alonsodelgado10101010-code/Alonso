package cl.vintagemanager.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cl.vintagemanager.core.AlertKind
import cl.vintagemanager.core.AlertPriority
import cl.vintagemanager.core.Era
import cl.vintagemanager.core.GarmentLifetime
import cl.vintagemanager.core.GarmentType
import cl.vintagemanager.core.Settings
import cl.vintagemanager.core.TaxRegime
import cl.vintagemanager.core.coreVersion
import cl.vintagemanager.core.isValidRut
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.notifications.AlertScheduler
import cl.vintagemanager.ui.components.BaseViewModel
import cl.vintagemanager.ui.components.ConfirmDialog
import cl.vintagemanager.ui.components.DropdownField
import cl.vintagemanager.ui.components.LoadingBox
import cl.vintagemanager.ui.components.MoneyField
import cl.vintagemanager.ui.components.NumberField
import cl.vintagemanager.ui.components.ScreenScaffold
import cl.vintagemanager.ui.components.SectionCard
import cl.vintagemanager.ui.components.TextInput
import cl.vintagemanager.util.cl
import cl.vintagemanager.util.label
import cl.vintagemanager.util.minutesToText
import cl.vintagemanager.util.parsePct
import cl.vintagemanager.util.rateToPctText
import cl.vintagemanager.util.today
import cl.vintagemanager.util.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Campos de texto editables; el resto se edita directamente sobre [base]. */
data class SettingsForm(
    val base: Settings,
    val iva: String,
    val idpc: String,
    val ppmLow: String,
    val ppmHigh: String,
    val thresholdUf: String,
    val ufValue: String,
    val initialCash: String,
    val initialRemanente: String,
    val lifetimes: Map<GarmentType, String>,
    val delicate: String,
) {
    companion object {
        fun from(s: Settings) = SettingsForm(
            base = s,
            iva = rateToPctText(s.ivaRate),
            idpc = rateToPctText(s.idpcRate),
            ppmLow = rateToPctText(s.ppmRateLow),
            ppmHigh = rateToPctText(s.ppmRateHigh),
            thresholdUf = s.ppmThresholdUf.toString(),
            ufValue = s.ufValue.toString(),
            initialCash = s.initialCash.toString(),
            initialRemanente = s.initialIvaRemanente.toString(),
            lifetimes = GarmentType.entries.associateWith { t ->
                (s.lifetimes.firstOrNull { it.garmentType == t }?.days ?: 90u).toString()
            },
            delicate = s.delicateMaterials.joinToString(", "),
        )
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    repo: VintageRepository,
    private val scheduler: AlertScheduler,
) : BaseViewModel(repo) {
    private val _form = MutableStateFlow<SettingsForm?>(null)
    val form = _form.asStateFlow()

    init {
        reload()
    }

    private fun reload() = viewModelScope.launch {
        repo.query { getSettings() }.onSuccess { _form.value = SettingsForm.from(it) }.onFailure { notify(it.userMessage()) }
    }

    fun edit(change: SettingsForm.() -> SettingsForm) {
        _form.value = _form.value?.change()
    }

    fun editBase(change: Settings.() -> Settings) = edit { copy(base = base.change()) }

    fun setRegime(regime: TaxRegime) = mutate("Régimen actualizado con sus tasas por defecto", { setRegime(regime) }) {
        _form.value = SettingsForm.from(it)
    }

    fun save() {
        val f = _form.value ?: return
        val rates = listOf(f.iva, f.idpc, f.ppmLow, f.ppmHigh).map { parsePct(it) }
        if (rates.any { it == null }) return notify("Revisa las tasas: usa números como 19 o 0,125.")
        if (f.base.storeRut.isNotBlank() && !isValidRut(f.base.storeRut)) return notify("El RUT de la tienda no es válido.")
        val lifetimes = f.lifetimes.map { (t, d) -> GarmentLifetime(t, (d.toUIntOrNull() ?: 0u).coerceAtLeast(1u)) }
        val s = f.base.copy(
            ivaRate = rates[0]!!,
            idpcRate = rates[1]!!,
            ppmRateLow = rates[2]!!,
            ppmRateHigh = rates[3]!!,
            ppmThresholdUf = f.thresholdUf.toLongOrNull() ?: f.base.ppmThresholdUf,
            ufValue = f.ufValue.toLongOrNull() ?: f.base.ufValue,
            initialCash = f.initialCash.toLongOrNull() ?: 0L,
            initialIvaRemanente = f.initialRemanente.toLongOrNull() ?: 0L,
            lifetimes = lifetimes,
            delicateMaterials = f.delicate.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
        mutate("Ajustes guardados", { saveSettings(s) }) {
            scheduler.scheduleDaily(s.dailyCheckMinute.toInt(), replace = true)
            reload()
        }
    }

    fun setPin(pin: String?) = mutate(if (pin == null) "Bloqueo desactivado" else "PIN configurado", { setPin(pin) }) { reload() }

    fun setBiometric(enabled: Boolean) {
        val f = _form.value ?: return
        mutate(null, { saveSettings(f.base.copy(biometricEnabled = enabled)) }) { reload() }
    }

    fun loadDemo() = mutate("Datos de demostración cargados", { loadDemoData(today()) }) { reload() }

    fun clearAll() = mutate("Se borraron todos los datos del negocio", { clearAllData() })

    fun runAlertsNow() {
        scheduler.runNow()
        notify("Revisión de alertas en curso…")
    }

    fun exportBackup(context: Context, uri: Uri) = viewModelScope.launch {
        repo.mutate { exportBackup(today()) }
            .mapCatching { json ->
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        ?: error("No se pudo escribir el archivo")
                }
            }
            .onSuccess { notify("Respaldo exportado"); reload() }
            .onFailure { notify(it.userMessage()) }
    }

    fun importBackup(context: Context, uri: Uri) = viewModelScope.launch {
        val json = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        }
        if (json == null) return@launch notify("No se pudo leer el archivo")
        repo.mutate { importBackup(json) }
            .onSuccess { n -> notify("Respaldo restaurado ($n registros)"); reload() }
            .onFailure { notify(it.userMessage()) }
    }
}

private val timeOptions: List<UInt> = (0 until 48).map { (it * 30).toUInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onNavigate: (String) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDemo by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var pinDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.exportBackup(context, it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingImport = uri
    }

    ScreenScaffold(title = "Ajustes", message = message, onMessageShown = vm::consumeMessage) { padding ->
        val f = form
        if (f == null) {
            LoadingBox()
            return@ScreenScaffold
        }
        val s = f.base
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Datos de la tienda") {
                TextInput(s.storeName, { v -> vm.editBase { copy(storeName = v) } }, "Nombre")
                TextInput(s.storeRut, { v -> vm.editBase { copy(storeRut = v) } }, "RUT")
                TextInput(s.storeGiro, { v -> vm.editBase { copy(storeGiro = v) } }, "Giro")
            }

            SectionCard("Tributario") {
                DropdownField("Régimen tributario", TaxRegime.entries.toList(), s.regime, { it.label }, { vm.setRegime(it) })
                Text(
                    "Las tasas cambian por ley: revísalas cada año con tu contador.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(f.iva, { v -> vm.edit { copy(iva = v) } }, "IVA", Modifier.weight(1f), "%", decimal = true)
                    NumberField(f.idpc, { v -> vm.edit { copy(idpc = v) } }, "1ª Categoría", Modifier.weight(1f), "%", decimal = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(f.ppmLow, { v -> vm.edit { copy(ppmLow = v) } }, "PPM ≤ umbral", Modifier.weight(1f), "%", decimal = true)
                    NumberField(f.ppmHigh, { v -> vm.edit { copy(ppmHigh = v) } }, "PPM > umbral", Modifier.weight(1f), "%", decimal = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(f.thresholdUf, { v -> vm.edit { copy(thresholdUf = v) } }, "Umbral", Modifier.weight(1f), "UF")
                    MoneyField(f.ufValue, { v -> vm.edit { copy(ufValue = v) } }, "Valor UF", Modifier.weight(1f))
                }
                DropdownField(
                    "Vencimiento F29",
                    listOf(20u, 12u),
                    s.f29DueDay,
                    { if (it == 20u) "Día 20 (facturación electrónica)" else "Día 12 (papel)" },
                    { v -> vm.editBase { copy(f29DueDay = v) } },
                )
                MoneyField(f.initialCash, { v -> vm.edit { copy(initialCash = v) } }, "Caja inicial")
                MoneyField(f.initialRemanente, { v -> vm.edit { copy(initialRemanente = v) } }, "Remanente IVA inicial")
            }

            SectionCard("Inventario (FEFO)") {
                Text("Vida comercial base por tipo (días)", style = MaterialTheme.typography.titleSmall)
                GarmentType.entries.forEach { t ->
                    NumberField(f.lifetimes[t].orEmpty(), { v -> vm.edit { copy(lifetimes = lifetimes + (t to v)) } }, t.label, suffix = "días")
                }
                Text("Épocas en tendencia (+30% de días)", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Era.entries.forEach { e ->
                        val on = e in s.trendingEras
                        FilterChip(
                            selected = on,
                            onClick = { vm.editBase { copy(trendingEras = if (on) trendingEras - e else trendingEras + e) } },
                            label = { Text(e.label) },
                        )
                    }
                }
                TextInput(f.delicate, { v -> vm.edit { copy(delicate = v) } }, "Materiales delicados (−20%), separados por coma")
            }

            SectionCard("Alertas y notificaciones") {
                DropdownField("Revisión diaria", timeOptions, s.dailyCheckMinute, { minutesToText(it) }, { v -> vm.editBase { copy(dailyCheckMinute = v) } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField("Silencio desde", timeOptions, s.quietStartMinute, { minutesToText(it) }, { v -> vm.editBase { copy(quietStartMinute = v) } }, Modifier.weight(1f))
                    DropdownField("hasta", timeOptions, s.quietEndMinute, { minutesToText(it) }, { v -> vm.editBase { copy(quietEndMinute = v) } }, Modifier.weight(1f))
                }
                DropdownField(
                    "Notificar desde prioridad",
                    listOf(AlertPriority.HIGH, AlertPriority.CRITICAL),
                    if (s.pushThreshold == AlertPriority.CRITICAL) AlertPriority.CRITICAL else AlertPriority.HIGH,
                    { if (it == AlertPriority.CRITICAL) "Solo críticas" else "Alta y crítica (recomendado)" },
                    { v -> vm.editBase { copy(pushThreshold = v) } },
                )
                Text("Máximo ${s.maxPushPerDay} notificaciones al día; si hay más, llegan en un solo resumen.", style = MaterialTheme.typography.bodySmall)
                Text("Tipos de alerta", style = MaterialTheme.typography.titleSmall)
                AlertKind.entries.forEach { k ->
                    val enabled = k !in s.disabledAlertKinds
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on ->
                                vm.editBase { copy(disabledAlertKinds = if (on) disabledAlertKinds - k else disabledAlertKinds + k) }
                            },
                        )
                    }
                }
                OutlinedButton(onClick = vm::runAlertsNow) { Text("Revisar alertas ahora") }
            }

            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("Guardar ajustes") }

            SectionCard("Seguridad") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.lockEnabled) "Bloqueo con PIN activado" else "Sin bloqueo", modifier = Modifier.weight(1f))
                    if (s.lockEnabled) {
                        TextButton(onClick = { vm.setPin(null) }) { Text("Quitar") }
                    }
                    FilledTonalButton(onClick = { pinDialog = true }) { Text(if (s.lockEnabled) "Cambiar PIN" else "Definir PIN") }
                }
                if (s.lockEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Desbloquear con huella", modifier = Modifier.weight(1f))
                        Switch(checked = s.biometricEnabled, onCheckedChange = { vm.setBiometric(it) })
                    }
                }
            }

            SectionCard("Respaldo y restauración") {
                Text(
                    "Último respaldo: " + (s.lastBackupDate?.cl() ?: "nunca"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { exportLauncher.launch("vintage_manager_${today()}.json") }, modifier = Modifier.weight(1f)) {
                        Text("Exportar JSON")
                    }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.weight(1f)) {
                        Text("Importar")
                    }
                }
            }

            SectionCard("Datos de demostración") {
                Text(
                    "40 prendas, 3 meses de movimientos, cobros, pagos y lotes para probar la app.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { confirmDemo = true }, modifier = Modifier.weight(1f)) { Text("Cargar demo") }
                    OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.weight(1f)) { Text("Borrar datos") }
                }
            }

            Text(
                "Vintage Manager · núcleo Rust v${remember { runCatching { coreVersion() }.getOrDefault("?") }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(24.dp))
        }
    }

    if (confirmDemo) {
        ConfirmDialog(
            "¿Cargar datos de demostración?",
            "Se reemplazarán todos los datos actuales del negocio (los ajustes se mantienen). Te recomendamos exportar un respaldo antes.",
            "Cargar",
            { vm.loadDemo() },
            { confirmDemo = false },
        )
    }
    if (confirmClear) {
        ConfirmDialog("¿Borrar todos los datos?", "Se eliminarán movimientos, prendas, cobros y pagos. Esta acción no se puede deshacer.", "Borrar", { vm.clearAll() }, { confirmClear = false })
    }
    pendingImport?.let { uri ->
        ConfirmDialog(
            "¿Restaurar respaldo?",
            "Los datos actuales se reemplazarán por los del archivo.",
            "Restaurar",
            { vm.importBackup(context, uri) },
            { pendingImport = null },
        )
    }
    if (pinDialog) PinDialog(onSave = { vm.setPin(it) }, onDismiss = { pinDialog = false })
}

@Composable
private fun PinDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val ok = pin.length >= 4 && pin == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Definir PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PIN (mínimo 4 dígitos)" to pin, "Repite el PIN" to confirm).forEachIndexed { i, (label, value) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = { v -> val d = v.filter(Char::isDigit).take(8); if (i == 0) pin = d else confirm = d },
                        label = { Text(label) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                    )
                }
                if (confirm.isNotEmpty() && pin != confirm) Text("Los PIN no coinciden", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onSave(pin); onDismiss() }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
