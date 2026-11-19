package cl.vintagemanager.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Fotos de prendas y comprobantes: se guardan en filesDir/photos (ruta local). */
object Photos {
    private fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }

    /** Copia una imagen elegida de la galería a almacenamiento interno. */
    fun importFromUri(context: Context, uri: Uri): String? = runCatching {
        val file = File(dir(context), "img_${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: return null
        file.absolutePath
    }.getOrNull()

    /** Archivo + Uri para que la cámara escriba la foto. */
    fun newCaptureTarget(context: Context): Pair<File, Uri> {
        val file = File(dir(context), "cam_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }
}
