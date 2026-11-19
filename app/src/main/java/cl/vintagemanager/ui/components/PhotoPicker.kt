package cl.vintagemanager.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cl.vintagemanager.util.Photos
import coil.compose.AsyncImage
import java.io.File

/** Selector de fotos: galería (Photo Picker) o cámara. Guarda rutas locales. */
@Composable
fun PhotoPicker(
    paths: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    max: Int = 5,
    label: String = "Fotos",
) {
    val context = LocalContext.current
    var pendingCapture by remember { mutableStateOf<Pair<File, Uri>?>(null) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { Photos.importFromUri(context, it) }?.let(onAdd)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = pendingCapture
        if (ok && target != null) onAdd(target.first.absolutePath) else target?.first?.delete()
        pendingCapture = null
    }
    val launchCamera = {
        val target = Photos.newCaptureTarget(context)
        pendingCapture = target
        camera.launch(target.second)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label)
        if (paths.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(paths) { path ->
                    Box {
                        AsyncImage(
                            model = File(path),
                            contentDescription = "Foto",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        FilledIconButton(
                            onClick = { onRemove(path) },
                            modifier = Modifier.size(28.dp).align(Alignment.TopEnd),
                            colors = IconButtonDefaults.filledIconButtonColors(),
                        ) { Icon(Icons.Filled.Close, contentDescription = "Quitar foto", modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
        if (paths.size < max) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Filled.PhotoLibrary, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Galería")
                }
                OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchCamera()
                    } else {
                        permission.launch(Manifest.permission.CAMERA)
                    }
                }) {
                    Icon(Icons.Filled.PhotoCamera, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Cámara")
                }
            }
        }
    }
}
