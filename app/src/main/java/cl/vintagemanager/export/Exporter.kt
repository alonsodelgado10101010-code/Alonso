package cl.vintagemanager.export

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import cl.vintagemanager.core.AnnualTax
import cl.vintagemanager.core.MonthlyTax
import cl.vintagemanager.util.cl
import cl.vintagemanager.util.clp
import cl.vintagemanager.util.monthName
import cl.vintagemanager.util.pct
import java.io.File

/**
 * Escribe archivos de exportación en cache/exports y abre el selector para
 * compartirlos (correo, WhatsApp, Drive…). Solo dibuja lo que calculó el núcleo.
 */
object Exporter {

    private fun exportsDir(context: Context) = File(context.cacheDir, "exports").apply { mkdirs() }

    fun shareText(context: Context, fileName: String, content: String, mime: String) {
        val file = File(exportsDir(context), fileName)
        file.writeText(content, Charsets.UTF_8)
        share(context, file, mime)
    }

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, "Compartir ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** PDF A4 con el desglose mensual del F29 y el resumen de renta. */
    fun taxPdf(
        context: Context,
        storeName: String,
        storeRut: String,
        year: Int,
        months: List<MonthlyTax>,
        annual: AnnualTax,
        disclaimer: String,
    ): File {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(842, 595, 1).create() // A4 apaisado (pt)
        val page = doc.startPage(pageInfo)
        val c = page.canvas
        val title = Paint().apply { textSize = 18f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        val bold = Paint().apply { textSize = 9f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val normal = Paint().apply { textSize = 9f; typeface = Typeface.SANS_SERIF }
        val right = Paint(normal).apply { textAlign = Paint.Align.RIGHT }
        val rightBold = Paint(bold).apply { textAlign = Paint.Align.RIGHT }

        var y = 40f
        c.drawText("Resumen tributario $year — $storeName", 36f, y, title)
        y += 16f
        c.drawText(if (storeRut.isBlank()) "RUT: —" else "RUT: $storeRut", 36f, y, normal)
        y += 22f

        val headers = listOf("Mes", "Ventas", "IVA débito", "IVA crédito", "Rem. ant.", "IVA a pagar", "Rem. sig.", "Base PPM", "Tasa", "PPM", "Total F29", "Vence")
        val xs = listOf(36f, 150f, 215f, 280f, 345f, 410f, 475f, 545f, 590f, 640f, 710f, 800f)
        headers.forEachIndexed { i, h -> c.drawText(h, xs[i], y, if (i == 0) bold else rightBold) }
        y += 4f
        c.drawLine(36f, y, 806f, y, normal)
        y += 13f
        for (m in months) {
            val values = listOf(
                monthName(m.month.toInt()), clp(m.salesGross), clp(m.ivaDebit), clp(m.ivaCredit),
                clp(m.remanentePrev), clp(m.ivaToPay), clp(m.remanenteNext), clp(m.ppmBase),
                pct(m.ppmRate), clp(m.ppm), clp(m.totalF29), m.dueDate.cl(),
            )
            values.forEachIndexed { i, v -> c.drawText(v, xs[i], y, if (i == 0) normal else right) }
            y += 14f
        }
        y += 6f
        c.drawLine(36f, y, 806f, y, normal)
        y += 22f
        c.drawText("Impuesto a la Renta estimado (${pct(annual.idpcRate)})", 36f, y, bold)
        y += 15f
        listOf(
            "Ingresos netos" to annual.incomeNet,
            "Costos y gastos netos" to annual.costsNet,
            "Base imponible" to annual.base,
            "Impuesto Primera Categoría" to annual.tax,
            "PPM pagados" to annual.ppmPaid,
            "Saldo estimado" to annual.balance,
            "Proyección impuesto a diciembre" to annual.projectedTax,
        ).forEach { (k, v) ->
            c.drawText(k, 36f, y, normal)
            c.drawText(clp(v), 260f, y, right)
            y += 13f
        }
        y += 8f
        c.drawText(annual.note, 36f, y, normal)
        y += 14f
        c.drawText(disclaimer, 36f, y, bold)
        doc.finishPage(page)

        val file = File(exportsDir(context), "impuestos_$year.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
