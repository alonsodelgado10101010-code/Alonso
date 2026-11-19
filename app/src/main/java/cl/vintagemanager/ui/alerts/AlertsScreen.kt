package cl.vintagemanager.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.vintagemanager.core.Alert
import cl.vintagemanager.core.AlertPriority
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.ui.components.BaseViewModel
import cl.vintagemanager.ui.components.EmptyState
import cl.vintagemanager.ui.components.ScreenScaffold
import cl.vintagemanager.ui.components.SectionCard
import cl.vintagemanager.ui.dashboard.AlertRow
import cl.vintagemanager.ui.navigation.routeFor
import cl.vintagemanager.util.color
import cl.vintagemanager.util.label
import cl.vintagemanager.util.today
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class AlertsViewModel @Inject constructor(repo: VintageRepository) : BaseViewModel(repo) {
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts = _alerts.asStateFlow()

    init {
        reloadOnChanges { repo.query { currentAlerts(today()) }.onSuccess { _alerts.value = it } }
    }

    fun snooze(a: Alert) = mutate("Alerta pospuesta hasta mañana", { snoozeAlerts(listOf(a.key), today().plusDays(1)) })
}

/** Bandeja de alertas: todas las prioridades (las MEDIA y BAJA solo se ven aquí). */
@Composable
fun AlertsScreen(onNavigate: (String) -> Unit, onBack: () -> Unit, vm: AlertsViewModel = hiltViewModel()) {
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Bandeja de alertas", onBack = onBack, message = message, onMessageShown = vm::consumeMessage) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (alerts.isEmpty()) {
                item { EmptyState(Icons.Filled.TaskAlt, "Sin alertas", "No hay nada pendiente. Te avisaremos cuando algo requiera tu atención.") }
            }
            AlertPriority.entries.forEach { p ->
                val group = alerts.filter { it.priority == p }
                if (group.isNotEmpty()) {
                    item(key = p.name) {
                        SectionCard("Prioridad ${p.label.lowercase()}") {
                            Text(
                                if (p == AlertPriority.CRITICAL || p == AlertPriority.HIGH) "Pueden llegar como notificación (máx. 3 al día)."
                                else "Solo se muestran dentro de la app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = p.color,
                            )
                            group.forEach { a ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(Modifier.weight(1f)) { AlertRow(a) { onNavigate(routeFor(a.target)) } }
                                    IconButton(onClick = { vm.snooze(a) }) { Icon(Icons.Filled.NotificationsPaused, "Posponer 1 día") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
