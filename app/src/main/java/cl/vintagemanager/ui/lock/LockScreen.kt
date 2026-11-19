package cl.vintagemanager.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Bloqueo con PIN (verificado por el núcleo) o huella. */
@Composable
fun LockScreen(
    biometricAvailable: Boolean,
    onBiometric: () -> Unit,
    verifyPin: suspend (String) -> Boolean,
    onUnlocked: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (biometricAvailable) onBiometric() }
    LaunchedEffect(attempt) {
        attempt?.let {
            if (verifyPin(it)) onUnlocked() else error = true
            attempt = null
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text("Vintage Manager", style = MaterialTheme.typography.headlineMedium)
            Text("Ingresa tu PIN para continuar", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8); error = false },
                label = { Text("PIN") },
                isError = error,
                supportingText = { if (error) Text("PIN incorrecto") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
            )
            Button(onClick = { attempt = pin }, enabled = pin.length >= 4) { Text("Desbloquear") }
            if (biometricAvailable) {
                OutlinedButton(onClick = onBiometric) {
                    Icon(Icons.Filled.Fingerprint, null)
                    Text("  Usar huella")
                }
            }
        }
    }
}
