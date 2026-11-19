package cl.vintagemanager.util

import cl.vintagemanager.core.CoreException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val LocaleCL: Locale = Locale.forLanguageTag("es-CL")

/** `$ 1.250.000` — CLP sin decimales, punto como separador de miles. */
fun clp(amount: Long): String {
    val digits = kotlin.math.abs(amount).toString()
    val sb = StringBuilder()
    digits.forEachIndexed { i, c ->
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append('.')
        sb.append(c)
    }
    return if (amount < 0) "-$ $sb" else "$ $sb"
}

/** Convierte texto ingresado ("1.250.000", "$ 12.990") a pesos. */
fun parseClp(text: String): Long? = text.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toLongOrNull()

/** Tasa fraccional (0.19) → "19%"; (0.00125) → "0,125%". */
fun pct(rate: BigDecimal): String =
    rate.multiply(BigDecimal(100)).stripTrailingZeros().toPlainString().replace('.', ',') + "%"

/** "19" o "0,125" (porcentaje) → fracción BigDecimal. */
fun parsePct(text: String): BigDecimal? =
    text.trim().replace("%", "").replace(',', '.').toBigDecimalOrNull()
        ?.divide(BigDecimal(100), 8, RoundingMode.HALF_UP)?.stripTrailingZeros()

fun rateToPctText(rate: BigDecimal): String =
    rate.multiply(BigDecimal(100)).stripTrailingZeros().toPlainString().replace('.', ',')

private val dateFmt = DateTimeFormatter.ofPattern("dd-MM-yyyy", LocaleCL)
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", LocaleCL)

fun LocalDate.cl(): String = format(dateFmt)
fun LocalDate.monthTitle(): String = format(monthFmt).replaceFirstChar { it.titlecase(LocaleCL) }

fun today(): LocalDate = LocalDate.now()
fun minuteOfDay(): UInt = LocalTime.now().let { (it.hour * 60 + it.minute).toUInt() }

fun minutesToText(m: UInt): String = "%02d:%02d".format(m.toInt() / 60, m.toInt() % 60)

fun daysText(days: Long): String = when {
    days == 0L -> "hoy"
    days == 1L -> "mañana"
    days > 1 -> "en $days días"
    days == -1L -> "hace 1 día"
    else -> "hace ${-days} días"
}

/** Mensaje legible para la usuaria a partir de una excepción del núcleo. */
fun Throwable.userMessage(): String = when (this) {
    is CoreException.Validation -> msg.replaceFirstChar { it.uppercase() }
    is CoreException.NotFound -> "No se encontró $entity."
    is CoreException.Database -> "Error de base de datos: $msg"
    is CoreException.Backup -> msg.replaceFirstChar { it.uppercase() }
    else -> message ?: "Ocurrió un error inesperado."
}
