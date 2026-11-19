package cl.vintagemanager.util

import androidx.compose.ui.graphics.Color
import cl.vintagemanager.core.AlertPriority
import cl.vintagemanager.core.Category
import cl.vintagemanager.core.Condition
import cl.vintagemanager.core.DebtStatus
import cl.vintagemanager.core.DocumentType
import cl.vintagemanager.core.Era
import cl.vintagemanager.core.GarmentStatus
import cl.vintagemanager.core.GarmentType
import cl.vintagemanager.core.MovementKind
import cl.vintagemanager.core.PaymentMethod
import cl.vintagemanager.core.ReceivableConcept
import cl.vintagemanager.core.Season
import cl.vintagemanager.core.Semaphore
import cl.vintagemanager.core.StoreLocation
import cl.vintagemanager.core.TaxRegime
import cl.vintagemanager.core.AlertKind
import cl.vintagemanager.ui.theme.SemFresca
import cl.vintagemanager.ui.theme.SemLiquidar
import cl.vintagemanager.ui.theme.SemPronto
import cl.vintagemanager.ui.theme.SemYa

// Etiquetas en español de Chile para los enums del núcleo.

val MovementKind.label get() = when (this) {
    MovementKind.INCOME -> "Ingreso"
    MovementKind.EXPENSE -> "Egreso"
}

val PaymentMethod.label get() = when (this) {
    PaymentMethod.CASH -> "Efectivo"
    PaymentMethod.DEBIT -> "Débito"
    PaymentMethod.CREDIT -> "Crédito"
    PaymentMethod.TRANSFER -> "Transferencia"
}

val DocumentType.label get() = when (this) {
    DocumentType.BOLETA -> "Boleta"
    DocumentType.FACTURA -> "Factura"
    DocumentType.FACTURA_COMPRA -> "Factura de compra"
    DocumentType.SIN_DOCUMENTO -> "Sin documento / no afecto"
}

val Category.label get() = when (this) {
    Category.VENTA_TIENDA -> "Venta en tienda"
    Category.VENTA_ONLINE -> "Venta online / RR.SS."
    Category.VENTA_FERIA -> "Venta en feria"
    Category.OTRO_INGRESO -> "Otros ingresos"
    Category.COMPRA_MERCADERIA -> "Compra de mercadería"
    Category.ARRIENDO -> "Arriendo"
    Category.SERVICIOS_BASICOS -> "Servicios básicos"
    Category.SUELDOS_HONORARIOS -> "Sueldos / honorarios"
    Category.LAVANDERIA_REPARACION -> "Lavandería y reparación"
    Category.MARKETING -> "Marketing"
    Category.ENVIOS -> "Envíos"
    Category.COMISIONES -> "Comisiones (Transbank, Mercado Pago)"
    Category.OTRO_EGRESO -> "Otros egresos"
}

val incomeCategories = listOf(Category.VENTA_TIENDA, Category.VENTA_ONLINE, Category.VENTA_FERIA, Category.OTRO_INGRESO)
val expenseCategories = Category.entries.filterNot { it in incomeCategories }

fun categoriesFor(kind: MovementKind) = if (kind == MovementKind.INCOME) incomeCategories else expenseCategories

fun documentsFor(kind: MovementKind) =
    if (kind == MovementKind.INCOME) listOf(DocumentType.BOLETA, DocumentType.FACTURA, DocumentType.SIN_DOCUMENTO)
    else DocumentType.entries.toList()

val ReceivableConcept.label get() = when (this) {
    ReceivableConcept.VENTA_CREDITO -> "Venta a crédito"
    ReceivableConcept.CONSIGNACION -> "Consignación"
    ReceivableConcept.APARTADO -> "Apartado / reserva"
    ReceivableConcept.OTRO -> "Otro"
}

val DebtStatus.label get() = when (this) {
    DebtStatus.PENDING -> "Pendiente"
    DebtStatus.PARTIAL -> "Parcial"
    DebtStatus.PAID -> "Pagado"
    DebtStatus.OVERDUE -> "Vencido"
}

val GarmentType.label get() = when (this) {
    GarmentType.BASICO_POLERA -> "Básicos y poleras"
    GarmentType.JEANS_PANTALON -> "Jeans y pantalones"
    GarmentType.VESTIDO_FALDA -> "Vestidos y faldas"
    GarmentType.CHAQUETA_ABRIGO -> "Chaquetas y abrigos"
    GarmentType.ACCESORIO -> "Accesorios"
    GarmentType.COLECCION -> "Colección / alta gama"
}

val Era.label get() = when (this) {
    Era.SESENTAS -> "60s"
    Era.SETENTAS -> "70s"
    Era.OCHENTAS -> "80s"
    Era.NOVENTAS -> "90s"
    Era.Y2K -> "Y2K"
    Era.OTRA -> "Otra"
}

val Condition.label get() = when (this) {
    Condition.EXCELENTE -> "Excelente"
    Condition.MUY_BUENO -> "Muy bueno"
    Condition.BUENO -> "Bueno"
    Condition.CON_DETALLES -> "Con detalles"
}

val Season.label get() = when (this) {
    Season.PRIMAVERA_VERANO -> "Primavera-Verano"
    Season.OTONO_INVIERNO -> "Otoño-Invierno"
    Season.TODO_EL_ANO -> "Todo el año"
}

val StoreLocation.label get() = when (this) {
    StoreLocation.PERCHA -> "Percha"
    StoreLocation.VITRINA -> "Vitrina"
    StoreLocation.BODEGA -> "Bodega"
}

val GarmentStatus.label get() = when (this) {
    GarmentStatus.EN_STOCK -> "En stock"
    GarmentStatus.RESERVADA -> "Reservada"
    GarmentStatus.VENDIDA -> "Vendida"
    GarmentStatus.EN_REPARACION -> "En reparación / lavandería"
    GarmentStatus.DONADA -> "Donada"
}

val Semaphore.label get() = when (this) {
    Semaphore.FRESCA -> "Fresca"
    Semaphore.ROTAR_PRONTO -> "Rotar pronto"
    Semaphore.ROTAR_YA -> "Rotar ya"
    Semaphore.LIQUIDAR -> "Liquidar"
}

val Semaphore.emoji get() = when (this) {
    Semaphore.FRESCA -> "🟢"
    Semaphore.ROTAR_PRONTO -> "🟡"
    Semaphore.ROTAR_YA -> "🟠"
    Semaphore.LIQUIDAR -> "🔴"
}

val Semaphore.color: Color get() = when (this) {
    Semaphore.FRESCA -> SemFresca
    Semaphore.ROTAR_PRONTO -> SemPronto
    Semaphore.ROTAR_YA -> SemYa
    Semaphore.LIQUIDAR -> SemLiquidar
}

val TaxRegime.label get() = when (this) {
    TaxRegime.PRO_PYME_GENERAL -> "Pro Pyme General (14 D N°3)"
    TaxRegime.PRO_PYME_TRANSPARENTE -> "Pro Pyme Transparente (14 D N°8)"
    TaxRegime.REGIMEN_GENERAL -> "Régimen General (14 A)"
}

val AlertPriority.label get() = when (this) {
    AlertPriority.CRITICAL -> "Crítica"
    AlertPriority.HIGH -> "Alta"
    AlertPriority.MEDIUM -> "Media"
    AlertPriority.LOW -> "Baja"
}

val AlertPriority.color: Color get() = when (this) {
    AlertPriority.CRITICAL -> SemLiquidar
    AlertPriority.HIGH -> SemYa
    AlertPriority.MEDIUM -> SemPronto
    AlertPriority.LOW -> SemFresca
}

val AlertKind.label get() = when (this) {
    AlertKind.F29_DUE -> "Vencimiento del F29"
    AlertKind.SUPPLIER_PAYMENT_OVERDUE -> "Pagos a proveedores vencidos"
    AlertKind.SUPPLIER_PAYMENT_DUE_SOON -> "Pagos a proveedores por vencer"
    AlertKind.RECEIVABLE_OVERDUE -> "Cobros vencidos"
    AlertKind.RECEIVABLE_DUE_TODAY -> "Cobros que vencen hoy"
    AlertKind.RECEIVABLE_DUE_SOON -> "Cobros por vencer (7 días)"
    AlertKind.CASH_SHORTFALL -> "Caja insuficiente"
    AlertKind.INVENTORY_LIQUIDATE -> "Prendas para liquidar"
    AlertKind.INVENTORY_ROTATE_NOW -> "Prendas en «Rotar ya»"
    AlertKind.INVENTORY_ROTATE_SOON -> "Prendas en «Rotar pronto»"
    AlertKind.PRICE_SUGGESTION -> "Sugerencias de precio"
    AlertKind.BACKUP_REMINDER -> "Recordatorio de respaldo"
}

val monthNames = listOf(
    "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
    "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre",
)

fun monthName(m: Int) = monthNames.getOrElse(m - 1) { "" }
