import SwiftUI

// Etiquetas en español de Chile y listas de opciones para los enums del núcleo.

extension MovementKind {
    static let all: [MovementKind] = [.income, .expense]
    var label: String { self == .income ? "Ingreso" : "Egreso" }
}

extension PaymentMethod {
    static let all: [PaymentMethod] = [.cash, .debit, .credit, .transfer]
    var label: String {
        switch self {
        case .cash: return "Efectivo"
        case .debit: return "Débito"
        case .credit: return "Crédito"
        case .transfer: return "Transferencia"
        }
    }
}

extension DocumentType {
    static let all: [DocumentType] = [.boleta, .factura, .facturaCompra, .sinDocumento]
    static func options(for kind: MovementKind) -> [DocumentType] {
        kind == .income ? [.boleta, .factura, .sinDocumento] : all
    }
    var label: String {
        switch self {
        case .boleta: return "Boleta"
        case .factura: return "Factura"
        case .facturaCompra: return "Factura de compra"
        case .sinDocumento: return "Sin documento / no afecto"
        }
    }
}

extension Category {
    static let income: [Category] = [.ventaTienda, .ventaOnline, .ventaFeria, .otroIngreso]
    static let expense: [Category] = [.compraMercaderia, .arriendo, .serviciosBasicos, .sueldosHonorarios,
                                      .lavanderiaReparacion, .marketing, .envios, .comisiones, .otroEgreso]
    static func options(for kind: MovementKind) -> [Category] { kind == .income ? income : expense }
    var label: String {
        switch self {
        case .ventaTienda: return "Venta en tienda"
        case .ventaOnline: return "Venta online / RR.SS."
        case .ventaFeria: return "Venta en feria"
        case .otroIngreso: return "Otros ingresos"
        case .compraMercaderia: return "Compra de mercadería"
        case .arriendo: return "Arriendo"
        case .serviciosBasicos: return "Servicios básicos"
        case .sueldosHonorarios: return "Sueldos / honorarios"
        case .lavanderiaReparacion: return "Lavandería y reparación"
        case .marketing: return "Marketing"
        case .envios: return "Envíos"
        case .comisiones: return "Comisiones (Transbank, Mercado Pago)"
        case .otroEgreso: return "Otros egresos"
        }
    }
}

extension ReceivableConcept {
    static let all: [ReceivableConcept] = [.ventaCredito, .consignacion, .apartado, .otro]
    var label: String {
        switch self {
        case .ventaCredito: return "Venta a crédito"
        case .consignacion: return "Consignación"
        case .apartado: return "Apartado / reserva"
        case .otro: return "Otro"
        }
    }
}

extension DebtStatus {
    var label: String {
        switch self {
        case .pending: return "Pendiente"
        case .partial: return "Parcial"
        case .paid: return "Pagado"
        case .overdue: return "Vencido"
        }
    }
    var color: Color {
        switch self {
        case .paid: return Palette.fresca
        case .overdue: return Palette.liquidar
        case .partial: return Palette.pronto
        case .pending: return Palette.terracotta
        }
    }
}

extension GarmentType {
    static let all: [GarmentType] = [.basicoPolera, .jeansPantalon, .vestidoFalda, .chaquetaAbrigo, .accesorio, .coleccion]
    var label: String {
        switch self {
        case .basicoPolera: return "Básicos y poleras"
        case .jeansPantalon: return "Jeans y pantalones"
        case .vestidoFalda: return "Vestidos y faldas"
        case .chaquetaAbrigo: return "Chaquetas y abrigos"
        case .accesorio: return "Accesorios"
        case .coleccion: return "Colección / alta gama"
        }
    }
}

extension Era {
    static let all: [Era] = [.sesentas, .setentas, .ochentas, .noventas, .y2k, .otra]
    var label: String {
        switch self {
        case .sesentas: return "60s"
        case .setentas: return "70s"
        case .ochentas: return "80s"
        case .noventas: return "90s"
        case .y2k: return "Y2K"
        case .otra: return "Otra"
        }
    }
}

extension Condition {
    static let all: [Condition] = [.excelente, .muyBueno, .bueno, .conDetalles]
    var label: String {
        switch self {
        case .excelente: return "Excelente"
        case .muyBueno: return "Muy bueno"
        case .bueno: return "Bueno"
        case .conDetalles: return "Con detalles"
        }
    }
}

extension Season {
    static let all: [Season] = [.primaveraVerano, .otonoInvierno, .todoElAno]
    var label: String {
        switch self {
        case .primaveraVerano: return "Primavera-Verano"
        case .otonoInvierno: return "Otoño-Invierno"
        case .todoElAno: return "Todo el año"
        }
    }
}

extension StoreLocation {
    static let all: [StoreLocation] = [.percha, .vitrina, .bodega]
    var label: String {
        switch self {
        case .percha: return "Percha"
        case .vitrina: return "Vitrina"
        case .bodega: return "Bodega"
        }
    }
}

extension GarmentStatus {
    static let editable: [GarmentStatus] = [.enStock, .reservada, .enReparacion, .donada]
    var label: String {
        switch self {
        case .enStock: return "En stock"
        case .reservada: return "Reservada"
        case .vendida: return "Vendida"
        case .enReparacion: return "En reparación / lavandería"
        case .donada: return "Donada"
        }
    }
}

extension Semaphore {
    static let all: [Semaphore] = [.fresca, .rotarPronto, .rotarYa, .liquidar]
    var label: String {
        switch self {
        case .fresca: return "Fresca"
        case .rotarPronto: return "Rotar pronto"
        case .rotarYa: return "Rotar ya"
        case .liquidar: return "Liquidar"
        }
    }
    var emoji: String {
        switch self {
        case .fresca: return "🟢"
        case .rotarPronto: return "🟡"
        case .rotarYa: return "🟠"
        case .liquidar: return "🔴"
        }
    }
    var color: Color {
        switch self {
        case .fresca: return Palette.fresca
        case .rotarPronto: return Palette.pronto
        case .rotarYa: return Palette.ya
        case .liquidar: return Palette.liquidar
        }
    }
}

extension TaxRegime {
    static let all: [TaxRegime] = [.proPymeGeneral, .proPymeTransparente, .regimenGeneral]
    var label: String {
        switch self {
        case .proPymeGeneral: return "Pro Pyme General (14 D N°3)"
        case .proPymeTransparente: return "Pro Pyme Transparente (14 D N°8)"
        case .regimenGeneral: return "Régimen General (14 A)"
        }
    }
}

extension AlertPriority {
    static let all: [AlertPriority] = [.critical, .high, .medium, .low]
    var label: String {
        switch self {
        case .critical: return "Crítica"
        case .high: return "Alta"
        case .medium: return "Media"
        case .low: return "Baja"
        }
    }
    var color: Color {
        switch self {
        case .critical: return Palette.liquidar
        case .high: return Palette.ya
        case .medium: return Palette.pronto
        case .low: return Palette.fresca
        }
    }
}

extension AlertKind {
    static let all: [AlertKind] = [.f29Due, .supplierPaymentOverdue, .supplierPaymentDueSoon, .receivableOverdue,
                                   .receivableDueToday, .receivableDueSoon, .cashShortfall, .inventoryLiquidate,
                                   .inventoryRotateNow, .inventoryRotateSoon, .priceSuggestion, .backupReminder]
    var label: String {
        switch self {
        case .f29Due: return "Vencimiento del F29"
        case .supplierPaymentOverdue: return "Pagos a proveedores vencidos"
        case .supplierPaymentDueSoon: return "Pagos a proveedores por vencer"
        case .receivableOverdue: return "Cobros vencidos"
        case .receivableDueToday: return "Cobros que vencen hoy"
        case .receivableDueSoon: return "Cobros por vencer (7 días)"
        case .cashShortfall: return "Caja insuficiente"
        case .inventoryLiquidate: return "Prendas para liquidar"
        case .inventoryRotateNow: return "Prendas en «Rotar ya»"
        case .inventoryRotateSoon: return "Prendas en «Rotar pronto»"
        case .priceSuggestion: return "Sugerencias de precio"
        case .backupReminder: return "Recordatorio de respaldo"
        }
    }
}
