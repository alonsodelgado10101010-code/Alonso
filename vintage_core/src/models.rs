//! Modelo de dominio de Vintage Manager.
//!
//! Convenciones:
//! * Dinero: `i64` en pesos chilenos (CLP no usa decimales).
//! * Tasas: `rust_decimal::Decimal` expresadas como fracción (`0.19` = 19%).
//! * Fechas: `chrono::NaiveDate` (en SQLite se guardan como `YYYY-MM-DD`).

use chrono::NaiveDate;
use rusqlite::types::{FromSql, FromSqlError, FromSqlResult, ToSql, ToSqlOutput, ValueRef};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, CoreResult};

/// Declara un enum exportado por UniFFI que se guarda en SQLite como texto.
macro_rules! db_enum {
    ($(#[$meta:meta])* $name:ident { $($variant:ident => $s:literal),* $(,)? }) => {
        $(#[$meta])*
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum, Serialize, Deserialize)]
        pub enum $name { $($variant),* }

        impl $name {
            pub fn as_str(&self) -> &'static str {
                match self { $(Self::$variant => $s),* }
            }
            pub fn parse(s: &str) -> CoreResult<Self> {
                match s {
                    $($s => Ok(Self::$variant),)*
                    other => Err(CoreError::validation(format!(
                        "valor '{}' no válido para {}", other, stringify!($name)
                    ))),
                }
            }
            pub fn all() -> Vec<Self> { vec![$(Self::$variant),*] }
        }

        impl ToSql for $name {
            fn to_sql(&self) -> rusqlite::Result<ToSqlOutput<'_>> {
                Ok(ToSqlOutput::from(self.as_str()))
            }
        }

        impl FromSql for $name {
            fn column_result(value: ValueRef<'_>) -> FromSqlResult<Self> {
                let s = value.as_str()?;
                Self::parse(s).map_err(|e| FromSqlError::Other(Box::new(e)))
            }
        }
    };
}

// ───────────────────────────── Finanzas ─────────────────────────────

db_enum!(
    /// Tipo de movimiento de caja.
    MovementKind { Income => "income", Expense => "expense" }
);

db_enum!(PaymentMethod {
    Cash => "cash",
    Debit => "debit",
    Credit => "credit",
    Transfer => "transfer",
});

db_enum!(
    /// Documento tributario que respalda el movimiento.
    DocumentType {
        Boleta => "boleta",
        Factura => "factura",
        FacturaCompra => "factura_compra",
        SinDocumento => "sin_documento",
    }
);

impl DocumentType {
    /// ¿El documento lleva IVA? (sin documento / gasto no afecto no lo lleva)
    pub fn has_iva(&self) -> bool {
        !matches!(self, DocumentType::SinDocumento)
    }
    /// ¿El IVA de una compra con este documento da derecho a crédito fiscal?
    /// Solo facturas; las boletas de compra NO dan crédito.
    pub fn gives_iva_credit(&self) -> bool {
        matches!(self, DocumentType::Factura | DocumentType::FacturaCompra)
    }
    /// ¿El IVA de una venta con este documento genera débito fiscal?
    pub fn generates_iva_debit(&self) -> bool {
        matches!(self, DocumentType::Boleta | DocumentType::Factura)
    }
}

db_enum!(Category {
    VentaTienda => "venta_tienda",
    VentaOnline => "venta_online",
    VentaFeria => "venta_feria",
    OtroIngreso => "otro_ingreso",
    CompraMercaderia => "compra_mercaderia",
    Arriendo => "arriendo",
    ServiciosBasicos => "servicios_basicos",
    SueldosHonorarios => "sueldos_honorarios",
    LavanderiaReparacion => "lavanderia_reparacion",
    Marketing => "marketing",
    Envios => "envios",
    Comisiones => "comisiones",
    OtroEgreso => "otro_egreso",
});

impl Category {
    pub fn kind(&self) -> MovementKind {
        match self {
            Category::VentaTienda
            | Category::VentaOnline
            | Category::VentaFeria
            | Category::OtroIngreso => MovementKind::Income,
            _ => MovementKind::Expense,
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Movement {
    pub id: i64,
    pub kind: MovementKind,
    pub date: NaiveDate,
    /// Monto total pagado/cobrado (con IVA si corresponde).
    pub gross: i64,
    /// Monto sin IVA.
    pub net: i64,
    pub iva: i64,
    pub includes_iva: bool,
    pub category: Category,
    pub payment_method: PaymentMethod,
    pub document_type: DocumentType,
    pub document_number: Option<String>,
    pub description: String,
    pub photo_path: Option<String>,
    pub garment_id: Option<i64>,
    pub receivable_id: Option<i64>,
    pub payable_id: Option<i64>,
    pub lot_id: Option<i64>,
    /// true si es un egreso cuyo IVA es crédito fiscal (respaldado con factura).
    pub iva_creditable: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MovementInput {
    pub kind: MovementKind,
    pub date: NaiveDate,
    pub amount: i64,
    pub includes_iva: bool,
    pub category: Category,
    pub payment_method: PaymentMethod,
    pub document_type: DocumentType,
    pub document_number: Option<String>,
    pub description: String,
    pub photo_path: Option<String>,
    pub garment_id: Option<i64>,
}

#[derive(Debug, Clone, Default, PartialEq, uniffi::Record)]
pub struct MovementFilter {
    pub year: Option<i32>,
    pub month: Option<u32>,
    pub kind: Option<MovementKind>,
    pub category: Option<Category>,
}

db_enum!(
    /// Estado calculado de un cobro o pago pendiente.
    DebtStatus {
        Pending => "pending",
        Partial => "partial",
        Paid => "paid",
        Overdue => "overdue",
    }
);

db_enum!(ReceivableConcept {
    VentaCredito => "venta_credito",
    Consignacion => "consignacion",
    Apartado => "apartado",
    Otro => "otro",
});

/// Abono parcial o total a un cobro / pago.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DebtPayment {
    pub id: i64,
    pub date: NaiveDate,
    pub amount: i64,
    pub movement_id: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Receivable {
    pub id: i64,
    pub customer: String,
    pub amount: i64,
    pub issue_date: NaiveDate,
    pub due_date: NaiveDate,
    pub concept: ReceivableConcept,
    pub notes: String,
    pub paid_amount: i64,
    pub remaining: i64,
    pub status: DebtStatus,
    /// Días hasta el vencimiento (negativo = vencido hace N días).
    pub days_to_due: i64,
    pub payments: Vec<DebtPayment>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ReceivableInput {
    pub customer: String,
    pub amount: i64,
    pub issue_date: NaiveDate,
    pub due_date: NaiveDate,
    pub concept: ReceivableConcept,
    pub notes: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Payable {
    pub id: i64,
    pub supplier_id: Option<i64>,
    pub supplier_name: String,
    pub amount: i64,
    pub issue_date: NaiveDate,
    pub due_date: NaiveDate,
    pub document_type: DocumentType,
    pub document_number: Option<String>,
    pub category: Category,
    pub notes: String,
    pub paid_amount: i64,
    pub remaining: i64,
    pub status: DebtStatus,
    pub days_to_due: i64,
    pub payments: Vec<DebtPayment>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PayableInput {
    pub supplier_id: Option<i64>,
    pub supplier_name: String,
    pub amount: i64,
    pub issue_date: NaiveDate,
    pub due_date: NaiveDate,
    pub document_type: DocumentType,
    pub document_number: Option<String>,
    pub category: Category,
    pub notes: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Supplier {
    pub id: i64,
    pub name: String,
    pub rut: String,
    pub contact: String,
    pub phone: String,
    pub email: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SupplierInput {
    pub name: String,
    pub rut: String,
    pub contact: String,
    pub phone: String,
    pub email: String,
}

// ───────────────────────────── Inventario ─────────────────────────────

db_enum!(GarmentType {
    BasicoPolera => "basico_polera",
    JeansPantalon => "jeans_pantalon",
    VestidoFalda => "vestido_falda",
    ChaquetaAbrigo => "chaqueta_abrigo",
    Accesorio => "accesorio",
    Coleccion => "coleccion",
});

db_enum!(Era {
    Sesentas => "60s",
    Setentas => "70s",
    Ochentas => "80s",
    Noventas => "90s",
    Y2k => "y2k",
    Otra => "otra",
});

db_enum!(Condition {
    Excelente => "excelente",
    MuyBueno => "muy_bueno",
    Bueno => "bueno",
    ConDetalles => "con_detalles",
});

db_enum!(Season {
    PrimaveraVerano => "primavera_verano",
    OtonoInvierno => "otono_invierno",
    TodoElAno => "todo_el_ano",
});

db_enum!(StoreLocation {
    Percha => "percha",
    Vitrina => "vitrina",
    Bodega => "bodega",
});

db_enum!(GarmentStatus {
    EnStock => "en_stock",
    Reservada => "reservada",
    Vendida => "vendida",
    EnReparacion => "en_reparacion",
    Donada => "donada",
});

db_enum!(
    /// Semáforo FEFO: "primero en perder valor comercial, primero en salir".
    Semaphore {
        Fresca => "fresca",
        RotarPronto => "rotar_pronto",
        RotarYa => "rotar_ya",
        Liquidar => "liquidar",
    }
);

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Garment {
    pub id: i64,
    pub sku: String,
    pub garment_type: GarmentType,
    pub brand: String,
    pub era: Era,
    pub size: String,
    pub color: String,
    pub material: String,
    pub condition: Condition,
    pub season: Season,
    pub cost: i64,
    pub price: i64,
    pub supplier_id: Option<i64>,
    pub lot_id: Option<i64>,
    pub intake_date: NaiveDate,
    pub location: StoreLocation,
    pub status: GarmentStatus,
    pub photo_paths: Vec<String>,
    pub description: String,
    /// Días acumulados en reparación/lavandería (pausan la FLR).
    pub paused_days: i64,
    pub paused_since: Option<NaiveDate>,
    pub sold_date: Option<NaiveDate>,
    pub sold_price: Option<i64>,
    pub last_discount_date: Option<NaiveDate>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct GarmentInput {
    pub garment_type: GarmentType,
    pub brand: String,
    pub era: Era,
    pub size: String,
    pub color: String,
    pub material: String,
    pub condition: Condition,
    pub season: Season,
    pub cost: i64,
    pub price: i64,
    pub supplier_id: Option<i64>,
    pub lot_id: Option<i64>,
    pub intake_date: NaiveDate,
    pub location: StoreLocation,
    pub photo_paths: Vec<String>,
    pub description: String,
}

/// Prenda + resultado del motor FEFO.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct GarmentView {
    pub garment: Garment,
    /// Fecha Límite de Rotación efectiva (incluye pausas).
    pub flr: NaiveDate,
    pub days_remaining: i64,
    pub semaphore: Semaphore,
    pub suggested_action: String,
    pub suggested_discount_pct: u32,
    /// Texto que se codifica en el QR de la etiqueta.
    pub qr_payload: String,
}

#[derive(Debug, Clone, Default, PartialEq, uniffi::Record)]
pub struct InventoryFilter {
    pub semaphore: Option<Semaphore>,
    pub garment_type: Option<GarmentType>,
    pub size: Option<String>,
    pub season: Option<Season>,
    pub era: Option<Era>,
    pub query: Option<String>,
    /// Incluir vendidas y donadas.
    pub include_inactive: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PriceChange {
    pub id: i64,
    pub garment_id: i64,
    pub date: NaiveDate,
    pub old_price: i64,
    pub new_price: i64,
    pub reason: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DiscountPreview {
    pub garment_id: i64,
    pub discount_pct: u32,
    pub old_price: i64,
    /// Nuevo precio de venta (con IVA), redondeado a la centena.
    pub new_price: i64,
    /// Nuevo precio sin IVA.
    pub new_price_net: i64,
    pub cost: i64,
    /// Margen = precio neto − costo.
    pub margin: i64,
    /// Margen sobre precio neto (fracción).
    pub margin_pct: Decimal,
    pub below_cost: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SaleInput {
    pub garment_id: i64,
    pub price: i64,
    pub date: NaiveDate,
    pub payment_method: PaymentMethod,
    pub document_type: DocumentType,
    pub category: Category,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Lot {
    pub id: i64,
    pub name: String,
    pub supplier_id: Option<i64>,
    pub purchase_date: NaiveDate,
    pub total_cost: i64,
    pub document_type: DocumentType,
    pub notes: String,
    pub garment_count: u32,
    pub movement_id: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LotInput {
    pub name: String,
    pub supplier_id: Option<i64>,
    pub purchase_date: NaiveDate,
    pub total_cost: i64,
    pub document_type: DocumentType,
    pub notes: String,
    /// Si es true se crea automáticamente el egreso "Compra de mercadería".
    pub register_expense: bool,
    pub payment_method: PaymentMethod,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct QrMatrix {
    pub size: u32,
    /// Módulos fila por fila (`true` = negro), largo `size * size`.
    pub modules: Vec<bool>,
}

// ───────────────────────────── Impuestos ─────────────────────────────

db_enum!(TaxRegime {
    ProPymeGeneral => "pro_pyme_general",
    ProPymeTransparente => "pro_pyme_transparente",
    RegimenGeneral => "regimen_general",
});

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MonthlyTax {
    pub year: i32,
    pub month: u32,
    pub sales_gross: i64,
    pub iva_debit: i64,
    pub iva_credit: i64,
    /// Remanente de crédito fiscal arrastrado del mes anterior.
    pub remanente_prev: i64,
    /// débito − crédito − remanente anterior (puede ser negativo).
    pub iva_result: i64,
    pub iva_to_pay: i64,
    /// Remanente a favor que pasa al mes siguiente.
    pub remanente_next: i64,
    pub ppm_base: i64,
    pub ppm_rate: Decimal,
    pub ppm: i64,
    pub total_f29: i64,
    pub due_date: NaiveDate,
    pub days_to_due: i64,
    pub filed: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AnnualTax {
    pub year: i32,
    pub regime: TaxRegime,
    pub income_net: i64,
    pub costs_net: i64,
    pub base: i64,
    pub idpc_rate: Decimal,
    pub tax: i64,
    pub ppm_paid: i64,
    /// impuesto − PPM pagados (negativo = posible devolución).
    pub balance: i64,
    pub possible_refund: bool,
    pub months_elapsed: u32,
    pub projected_base: i64,
    pub projected_tax: i64,
    pub projected_ppm: i64,
    pub projected_balance: i64,
    pub note: String,
}

// ───────────────────────────── Alertas ─────────────────────────────

db_enum!(
    /// Prioridad de alerta. El orden de declaración es de mayor a menor.
    AlertPriority {
        Critical => "critical",
        High => "high",
        Medium => "medium",
        Low => "low",
    }
);

impl AlertPriority {
    /// 0 = la más urgente.
    pub fn rank(&self) -> u8 {
        match self {
            AlertPriority::Critical => 0,
            AlertPriority::High => 1,
            AlertPriority::Medium => 2,
            AlertPriority::Low => 3,
        }
    }
}

db_enum!(AlertKind {
    F29Due => "f29_due",
    SupplierPaymentOverdue => "supplier_payment_overdue",
    SupplierPaymentDueSoon => "supplier_payment_due_soon",
    ReceivableOverdue => "receivable_overdue",
    ReceivableDueToday => "receivable_due_today",
    ReceivableDueSoon => "receivable_due_soon",
    CashShortfall => "cash_shortfall",
    InventoryLiquidate => "inventory_liquidate",
    InventoryRotateNow => "inventory_rotate_now",
    InventoryRotateSoon => "inventory_rotate_soon",
    PriceSuggestion => "price_suggestion",
    BackupReminder => "backup_reminder",
});

db_enum!(NotificationChannel {
    Criticas => "alertas_criticas",
    Resumen => "alertas_resumen",
});

/// Hacia dónde navega la app al tocar la alerta.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum AlertTarget {
    Dashboard,
    Taxes,
    Payable { id: i64 },
    Receivable { id: i64 },
    Inventory { semaphore: Option<Semaphore> },
    Garment { id: i64 },
    Settings,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Alert {
    /// Clave estable para deduplicación (ej. `payable_overdue:12`).
    pub key: String,
    pub kind: AlertKind,
    pub priority: AlertPriority,
    pub title: String,
    pub body: String,
    pub target: AlertTarget,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PushNotification {
    /// Id estable para `NotificationManager.notify`.
    pub notification_id: i32,
    pub channel: NotificationChannel,
    pub priority: AlertPriority,
    pub title: String,
    pub text: String,
    /// Líneas para InboxStyle (vacío si es individual).
    pub lines: Vec<String>,
    pub alert_keys: Vec<String>,
    pub target: AlertTarget,
    /// Presente si la acción "Marcar como pagado" aplica a un pago a proveedor.
    pub payable_id: Option<i64>,
    /// Presente si la acción "Marcar como pagado" aplica a un cobro.
    pub receivable_id: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PushPlan {
    pub notifications: Vec<PushNotification>,
    /// Si estamos en horario silencioso: minuto del día en que se debe reintentar.
    pub deferred_until_minute: Option<u32>,
    /// Push ya enviadas hoy (incluyendo las de este plan).
    pub sent_today: u32,
    /// Alertas que calificaban para push pero se omitieron (dedup/tope).
    pub suppressed: u32,
}

// ───────────────────────────── Reportes ─────────────────────────────

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MonthFlow {
    pub year: i32,
    pub month: u32,
    pub label: String,
    pub income: i64,
    pub expense: i64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct InventorySummary {
    pub in_stock: u32,
    pub value_cost: i64,
    pub value_price: i64,
    pub fresca: u32,
    pub rotar_pronto: u32,
    pub rotar_ya: u32,
    pub liquidar: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DebtSummary {
    pub receivables_total: i64,
    pub receivables_overdue: i64,
    pub receivables_count: u32,
    pub payables_total: i64,
    pub payables_overdue: i64,
    pub payables_count: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Dashboard {
    pub year: i32,
    pub month: u32,
    pub sales_gross: i64,
    pub expenses_gross: i64,
    /// Ingresos netos − costos netos del mes.
    pub profit_estimate: i64,
    /// Caja inicial + ingresos − egresos acumulados hasta hoy.
    pub cash_available: i64,
    pub tax: MonthlyTax,
    pub debts: DebtSummary,
    pub inventory: InventorySummary,
    /// Máximo 5, ordenadas por prioridad.
    pub attention: Vec<Alert>,
    pub chart: Vec<MonthFlow>,
    pub disclaimer: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TypeMetric {
    pub garment_type: GarmentType,
    pub sold: u32,
    pub avg_days_to_sell: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct EraMetric {
    pub era: Era,
    pub in_stock: u32,
    pub sold: u32,
    /// vendidas / (vendidas + en stock)
    pub rotation_pct: Decimal,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct InventoryMetrics {
    pub by_type: Vec<TypeMetric>,
    pub by_era: Vec<EraMetric>,
    pub oldest: Vec<GarmentView>,
    pub avg_margin: i64,
    pub avg_margin_pct: Decimal,
}
