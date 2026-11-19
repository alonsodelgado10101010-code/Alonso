package cl.vintagemanager.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import cl.vintagemanager.core.Supplier
import cl.vintagemanager.core.SupplierInput
import cl.vintagemanager.core.isValidRut
import cl.vintagemanager.data.VintageRepository
import cl.vintagemanager.ui.components.BaseViewModel
import cl.vintagemanager.ui.components.ConfirmDialog
import cl.vintagemanager.ui.components.EmptyState
import cl.vintagemanager.ui.components.ScreenScaffold
import cl.vintagemanager.ui.components.SectionCard
import cl.vintagemanager.ui.components.TextInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class SuppliersViewModel @Inject constructor(repo: VintageRepository) : BaseViewModel(repo) {
    private val _list = MutableStateFlow<List<Supplier>>(emptyList())
    val list = _list.asStateFlow()

    init {
        reloadOnChanges { repo.query { listSuppliers() }.onSuccess { _list.value = it } }
    }

    fun save(id: Long?, input: SupplierInput) {
        if (id == null) mutate("Proveedor agregado", { addSupplier(input) })
        else mutate("Proveedor actualizado", { updateSupplier(id, input) })
    }

    fun delete(id: Long) = mutate("Proveedor eliminado", { deleteSupplier(id) })
}

@Composable
fun SuppliersScreen(onBack: () -> Unit, vm: SuppliersViewModel = hiltViewModel()) {
    val list by vm.list.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Supplier?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Supplier?>(null) }

    ScreenScaffold(
        title = "Proveedores",
        onBack = onBack,
        message = message,
        onMessageShown = vm::consumeMessage,
        fab = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Agregar proveedor") } },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (list.isEmpty()) {
                item {
                    EmptyState(Icons.Filled.Groups, "Aún no tienes proveedores", "Guarda a quienes te venden fardos, lotes o servicios.", "Agregar proveedor") { creating = true }
                }
            }
            items(list, key = { it.id }) { s ->
                SectionCard(onClick = { editing = s }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall)
                            val details = listOf(s.rut, s.contact, s.phone, s.email).filter { it.isNotBlank() }
                            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { deleting = s }) { Icon(Icons.Filled.Delete, "Eliminar") }
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        SupplierDialog(
            initial = editing,
            onSave = { input -> vm.save(editing?.id, input) },
            onDismiss = { creating = false; editing = null },
        )
    }
    deleting?.let { s ->
        ConfirmDialog("¿Eliminar a ${s.name}?", "Los pagos registrados conservarán el nombre del proveedor.", "Eliminar", { vm.delete(s.id) }, { deleting = null })
    }
}

@Composable
private fun SupplierDialog(initial: Supplier?, onSave: (SupplierInput) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var rut by remember { mutableStateOf(initial?.rut.orEmpty()) }
    var contact by remember { mutableStateOf(initial?.contact.orEmpty()) }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var email by remember { mutableStateOf(initial?.email.orEmpty()) }
    val rutOk = rut.isBlank() || isValidRut(rut)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nuevo proveedor" else "Editar proveedor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextInput(name, { name = it }, "Nombre")
                TextInput(rut, { rut = it }, "RUT (ej. 76.086.428-5)")
                if (!rutOk) Text("RUT no válido", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextInput(contact, { contact = it }, "Contacto")
                TextInput(phone, { phone = it }, "Teléfono")
                TextInput(email, { email = it }, "Correo")
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && rutOk, onClick = {
                onSave(SupplierInput(name, rut, contact, phone, email))
                onDismiss()
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
