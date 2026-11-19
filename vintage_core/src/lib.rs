//! # vintage_core
//!
//! Núcleo nativo de **Vintage Manager**. Toda la lógica de negocio vive aquí:
//! persistencia SQLite, motor tributario chileno, motor FEFO de prendas, motor
//! de alertas, reportes y respaldo. La capa Android (Kotlin) solo muestra lo que
//! este núcleo devuelve; los bindings se generan con UniFFI.
//!
//! `VintageCore` es `Send + Sync`: la conexión está protegida por un `Mutex` y
//! todas las funciones son síncronas (Kotlin las llama desde `Dispatchers.IO`).

uniffi::setup_scaffolding!();

/// `dec!(0.19)` → `Decimal` exacto (sin pasar por punto flotante).
macro_rules! dec {
    ($s:literal) => {
        <rust_decimal::Decimal as std::str::FromStr>::from_str(stringify!($s)).unwrap()
    };
}

pub mod alerts;
pub mod backup;
pub mod db;
pub mod demo;
pub mod error;
pub mod finance;
pub mod inventory_fefo;
pub mod models;
pub mod reports;
pub mod settings;
pub mod sync;
pub mod tax;
mod types;

use std::sync::{Arc, Mutex, MutexGuard};

use chrono::NaiveDate;
use rusqlite::Connection;

pub use error::{CoreError, CoreResult};
pub use models::*;
pub use settings::{GarmentLifetime, Settings};

/// Desglose de un monto ingresado en el formulario (para previsualizar el IVA).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AmountBreakdown {
    pub gross: i64,
    pub net: i64,
    pub iva: i64,
    pub iva_creditable: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CategoryTotal {
    pub category: Category,
    pub total: i64,
}

/// Punto de entrada único para Kotlin.
#[derive(uniffi::Object)]
pub struct VintageCore {
    conn: Mutex<Connection>,
}

impl VintageCore {
    fn conn(&self) -> CoreResult<MutexGuard<'_, Connection>> {
        self.conn.lock().map_err(|_| CoreError::Database { msg: "conexión bloqueada".into() })
    }

    fn with<T>(&self, f: impl FnOnce(&Connection) -> CoreResult<T>) -> CoreResult<T> {
        let c = self.conn()?;
        f(&c)
    }

    fn with_settings<T>(&self, f: impl FnOnce(&Connection, &Settings) -> CoreResult<T>) -> CoreResult<T> {
        let c = self.conn()?;
        let s = settings::load(&c)?;
        f(&c, &s)
    }
}

#[uniffi::export]
impl VintageCore {
    /// Abre (o crea) la base de datos en `db_path` y aplica migraciones.
    /// Usa `":memory:"` para una base temporal (tests).
    #[uniffi::constructor]
    pub fn new(db_path: String) -> CoreResult<Arc<Self>> {
        let conn = db::open(&db_path)?;
        Ok(Arc::new(Self { conn: Mutex::new(conn) }))
    }

    // ─────────────── Ajustes ───────────────

    pub fn get_settings(&self) -> CoreResult<Settings> {
        self.with(settings::load)
    }

    pub fn save_settings(&self, s: Settings) -> CoreResult<()> {
        self.with(|c| {
            // El hash del PIN solo se cambia con set_pin.
            let current = settings::load(c)?;
            settings::save(c, &Settings { pin_hash: current.pin_hash, ..s })
        })
    }

    /// Cambia el régimen tributario aplicando sus tasas por defecto.
    pub fn set_regime(&self, regime: TaxRegime) -> CoreResult<Settings> {
        self.with(|c| {
            let mut s = settings::load(c)?;
            settings::apply_regime(&mut s, regime);
            settings::save(c, &s)?;
            Ok(s)
        })
    }

    pub fn reset_lifetimes(&self) -> CoreResult<Settings> {
        self.with(|c| {
            let mut s = settings::load(c)?;
            s.lifetimes = settings::default_lifetimes();
            settings::save(c, &s)?;
            Ok(s)
        })
    }

    /// Define o elimina (None) el PIN de bloqueo.
    pub fn set_pin(&self, pin: Option<String>) -> CoreResult<()> {
        self.with(|c| {
            let mut s = settings::load(c)?;
            match pin {
                Some(p) => {
                    if p.len() < 4 || !p.chars().all(|ch| ch.is_ascii_digit()) {
                        return Err(CoreError::validation("el PIN debe tener al menos 4 dígitos"));
                    }
                    s.pin_hash = Some(settings::hash_pin(&p));
                    s.lock_enabled = true;
                }
                None => {
                    s.pin_hash = None;
                    s.lock_enabled = false;
                    s.biometric_enabled = false;
                }
            }
            settings::save(c, &s)
        })
    }

    pub fn verify_pin(&self, pin: String) -> CoreResult<bool> {
        self.with(|c| {
            let s = settings::load(c)?;
            Ok(s.pin_hash.as_deref() == Some(settings::hash_pin(&pin).as_str()))
        })
    }

    // ─────────────── Movimientos ───────────────

    pub fn preview_amounts(&self, amount: i64, includes_iva: bool, kind: MovementKind, document_type: DocumentType) -> CoreResult<AmountBreakdown> {
        self.with_settings(|_, s| {
            let input = MovementInput {
                kind,
                date: NaiveDate::default(),
                amount,
                includes_iva,
                category: if kind == MovementKind::Income { Category::OtroIngreso } else { Category::OtroEgreso },
                payment_method: PaymentMethod::Cash,
                document_type,
                document_number: None,
                description: String::new(),
                photo_path: None,
                garment_id: None,
            };
            let (gross, net, iva) = finance::amounts_for(&input, s);
            Ok(AmountBreakdown {
                gross,
                net,
                iva,
                iva_creditable: kind == MovementKind::Expense && document_type.gives_iva_credit(),
            })
        })
    }

    pub fn add_movement(&self, input: MovementInput) -> CoreResult<Movement> {
        self.with_settings(|c, s| {
            let id = finance::add_movement(c, s, &input)?;
            finance::get_movement(c, id)
        })
    }

    pub fn update_movement(&self, id: i64, input: MovementInput) -> CoreResult<Movement> {
        self.with_settings(|c, s| {
            finance::update_movement(c, s, id, &input)?;
            finance::get_movement(c, id)
        })
    }

    pub fn delete_movement(&self, id: i64) -> CoreResult<()> {
        self.with(|c| finance::delete_movement(c, id))
    }

    pub fn get_movement(&self, id: i64) -> CoreResult<Movement> {
        self.with(|c| finance::get_movement(c, id))
    }

    pub fn list_movements(&self, filter: MovementFilter) -> CoreResult<Vec<Movement>> {
        self.with(|c| finance::list_movements(c, &filter))
    }

    pub fn category_totals(&self, year: i32, month: u32, kind: MovementKind) -> CoreResult<Vec<CategoryTotal>> {
        self.with(|c| {
            Ok(reports::category_totals(c, year, month, kind)?
                .into_iter()
                .map(|(category, total)| CategoryTotal { category, total })
                .collect())
        })
    }

    // ─────────────── Cobros pendientes ───────────────

    pub fn add_receivable(&self, input: ReceivableInput, today: NaiveDate) -> CoreResult<Receivable> {
        self.with(|c| {
            let id = finance::add_receivable(c, &input)?;
            finance::get_receivable(c, id, today)
        })
    }

    pub fn update_receivable(&self, id: i64, input: ReceivableInput, today: NaiveDate) -> CoreResult<Receivable> {
        self.with(|c| {
            finance::update_receivable(c, id, &input)?;
            finance::get_receivable(c, id, today)
        })
    }

    pub fn delete_receivable(&self, id: i64) -> CoreResult<()> {
        self.with(|c| finance::delete_receivable(c, id))
    }

    pub fn get_receivable(&self, id: i64, today: NaiveDate) -> CoreResult<Receivable> {
        self.with(|c| finance::get_receivable(c, id, today))
    }

    pub fn list_receivables(&self, today: NaiveDate, only_open: bool) -> CoreResult<Vec<Receivable>> {
        self.with(|c| finance::list_receivables(c, today, only_open))
    }

    /// Abono parcial: genera el ingreso automáticamente.
    pub fn pay_receivable(&self, id: i64, amount: i64, date: NaiveDate, method: PaymentMethod) -> CoreResult<Receivable> {
        self.with_settings(|c, s| finance::pay_receivable(c, s, id, amount, date, method))
    }

    /// Marca como pagado (abona el saldo completo).
    pub fn mark_receivable_paid(&self, id: i64, date: NaiveDate, method: PaymentMethod) -> CoreResult<Receivable> {
        self.with_settings(|c, s| {
            let r = finance::get_receivable(c, id, date)?;
            if r.remaining == 0 {
                return Ok(r);
            }
            finance::pay_receivable(c, s, id, r.remaining, date, method)
        })
    }

    // ─────────────── Pagos a proveedores ───────────────

    pub fn add_payable(&self, input: PayableInput, today: NaiveDate) -> CoreResult<Payable> {
        self.with(|c| {
            let id = finance::add_payable(c, &input)?;
            finance::get_payable(c, id, today)
        })
    }

    pub fn update_payable(&self, id: i64, input: PayableInput, today: NaiveDate) -> CoreResult<Payable> {
        self.with(|c| {
            finance::update_payable(c, id, &input)?;
            finance::get_payable(c, id, today)
        })
    }

    pub fn delete_payable(&self, id: i64) -> CoreResult<()> {
        self.with(|c| finance::delete_payable(c, id))
    }

    pub fn get_payable(&self, id: i64, today: NaiveDate) -> CoreResult<Payable> {
        self.with(|c| finance::get_payable(c, id, today))
    }

    pub fn list_payables(&self, today: NaiveDate, only_open: bool) -> CoreResult<Vec<Payable>> {
        self.with(|c| finance::list_payables(c, today, only_open))
    }

    pub fn pay_payable(&self, id: i64, amount: i64, date: NaiveDate, method: PaymentMethod) -> CoreResult<Payable> {
        self.with_settings(|c, s| finance::pay_payable(c, s, id, amount, date, method))
    }

    pub fn mark_payable_paid(&self, id: i64, date: NaiveDate, method: PaymentMethod) -> CoreResult<Payable> {
        self.with_settings(|c, s| {
            let p = finance::get_payable(c, id, date)?;
            if p.remaining == 0 {
                return Ok(p);
            }
            finance::pay_payable(c, s, id, p.remaining, date, method)
        })
    }

    // ─────────────── Proveedores ───────────────

    pub fn add_supplier(&self, input: SupplierInput) -> CoreResult<Supplier> {
        self.with(|c| {
            let id = finance::add_supplier(c, &input)?;
            finance::get_supplier(c, id)
        })
    }

    pub fn update_supplier(&self, id: i64, input: SupplierInput) -> CoreResult<Supplier> {
        self.with(|c| {
            finance::update_supplier(c, id, &input)?;
            finance::get_supplier(c, id)
        })
    }

    pub fn delete_supplier(&self, id: i64) -> CoreResult<()> {
        self.with(|c| finance::delete_supplier(c, id))
    }

    pub fn list_suppliers(&self) -> CoreResult<Vec<Supplier>> {
        self.with(finance::list_suppliers)
    }

    // ─────────────── Inventario FEFO ───────────────

    pub fn add_garment(&self, input: GarmentInput, today: NaiveDate) -> CoreResult<GarmentView> {
        self.with_settings(|c, s| {
            let id = inventory_fefo::add_garment(c, &input)?;
            Ok(inventory_fefo::view(s, inventory_fefo::get_garment(c, id)?, today))
        })
    }

    pub fn update_garment(&self, id: i64, input: GarmentInput, today: NaiveDate) -> CoreResult<GarmentView> {
        self.with_settings(|c, s| {
            inventory_fefo::update_garment(c, id, &input, today)?;
            Ok(inventory_fefo::view(s, inventory_fefo::get_garment(c, id)?, today))
        })
    }

    pub fn delete_garment(&self, id: i64) -> CoreResult<()> {
        self.with(|c| inventory_fefo::delete_garment(c, id))
    }

    pub fn get_garment(&self, id: i64, today: NaiveDate) -> CoreResult<GarmentView> {
        self.with_settings(|c, s| Ok(inventory_fefo::view(s, inventory_fefo::get_garment(c, id)?, today)))
    }

    /// Inventario filtrado, siempre ordenado por Fecha Límite de Rotación ascendente.
    pub fn list_inventory(&self, filter: InventoryFilter, today: NaiveDate) -> CoreResult<Vec<GarmentView>> {
        self.with_settings(|c, s| inventory_fefo::list_inventory(c, s, &filter, today))
    }

    /// Busca una prenda por el contenido de su QR (`VINTAGE:VM-00001`) o SKU.
    pub fn find_by_code(&self, code: String, today: NaiveDate) -> CoreResult<Option<GarmentView>> {
        self.with_settings(|c, s| Ok(inventory_fefo::find_by_sku(c, &code)?.map(|g| inventory_fefo::view(s, g, today))))
    }

    pub fn set_garment_status(&self, id: i64, status: GarmentStatus, today: NaiveDate) -> CoreResult<GarmentView> {
        self.with_settings(|c, s| Ok(inventory_fefo::view(s, inventory_fefo::set_status(c, id, status, today)?, today)))
    }

    pub fn set_garment_location(&self, id: i64, location: StoreLocation) -> CoreResult<()> {
        self.with(|c| inventory_fefo::set_location(c, id, location))
    }

    /// Vende una prenda: la descuenta del stock y crea el ingreso con su IVA.
    pub fn sell_garment(&self, sale: SaleInput) -> CoreResult<Movement> {
        self.with_settings(|c, s| {
            let id = inventory_fefo::sell(c, s, &sale)?;
            finance::get_movement(c, id)
        })
    }

    pub fn preview_discount(&self, id: i64, pct: u32) -> CoreResult<DiscountPreview> {
        self.with_settings(|c, s| Ok(inventory_fefo::preview_discount(&inventory_fefo::get_garment(c, id)?, pct, s.iva_rate)))
    }

    pub fn apply_discount(&self, id: i64, pct: u32, today: NaiveDate) -> CoreResult<DiscountPreview> {
        self.with_settings(|c, s| inventory_fefo::apply_discount(c, s, id, pct, today))
    }

    pub fn price_history(&self, id: i64) -> CoreResult<Vec<PriceChange>> {
        self.with(|c| inventory_fefo::price_history(c, id))
    }

    pub fn add_lot(&self, input: LotInput) -> CoreResult<Lot> {
        self.with_settings(|c, s| {
            let id = inventory_fefo::add_lot(c, s, &input)?;
            inventory_fefo::list_lots(c)?
                .into_iter()
                .find(|l| l.id == id)
                .ok_or_else(|| CoreError::not_found("lote", id))
        })
    }

    pub fn list_lots(&self) -> CoreResult<Vec<Lot>> {
        self.with(inventory_fefo::list_lots)
    }

    pub fn delete_lot(&self, id: i64) -> CoreResult<()> {
        self.with(|c| inventory_fefo::delete_lot(c, id))
    }

    pub fn inventory_metrics(&self, today: NaiveDate) -> CoreResult<InventoryMetrics> {
        self.with_settings(|c, s| reports::inventory_metrics(c, s, today))
    }

    // ─────────────── Impuestos ───────────────

    pub fn monthly_tax(&self, year: i32, month: u32, today: NaiveDate) -> CoreResult<MonthlyTax> {
        self.with_settings(|c, s| tax::monthly_tax(c, s, year, month, today))
    }

    pub fn tax_year(&self, year: i32, today: NaiveDate) -> CoreResult<Vec<MonthlyTax>> {
        self.with_settings(|c, s| tax::year_breakdown(c, s, year, today))
    }

    pub fn annual_tax(&self, year: i32, today: NaiveDate) -> CoreResult<AnnualTax> {
        self.with_settings(|c, s| tax::annual_tax(c, s, year, today))
    }

    pub fn mark_f29_filed(&self, year: i32, month: u32, date: NaiveDate) -> CoreResult<()> {
        self.with(|c| tax::mark_f29_filed(c, year, month, date))
    }

    pub fn unmark_f29_filed(&self, year: i32, month: u32) -> CoreResult<()> {
        self.with(|c| tax::unmark_f29_filed(c, year, month))
    }

    /// CSV (`;`) para Excel con el desglose del año y el resumen de renta.
    pub fn tax_csv(&self, year: i32, today: NaiveDate) -> CoreResult<String> {
        self.with_settings(|c, s| reports::tax_csv(c, s, year, today))
    }

    pub fn movements_csv(&self, filter: MovementFilter) -> CoreResult<String> {
        self.with(|c| reports::movements_csv(c, &filter))
    }

    // ─────────────── Resumen ───────────────

    pub fn dashboard(&self, year: i32, month: u32, today: NaiveDate) -> CoreResult<Dashboard> {
        self.with_settings(|c, s| reports::dashboard(c, s, year, month, today))
    }

    // ─────────────── Alertas ───────────────

    /// Todas las alertas vigentes (para la bandeja dentro de la app).
    pub fn current_alerts(&self, today: NaiveDate) -> CoreResult<Vec<Alert>> {
        self.with_settings(|c, s| alerts::collect_alerts(c, s, today))
    }

    /// Evalúa reglas, deduplica, aplica horario silencioso y tope diario.
    /// Devuelve las notificaciones a publicar (y las registra como enviadas).
    pub fn evaluate_alerts(&self, today: NaiveDate, minute_of_day: u32) -> CoreResult<PushPlan> {
        self.with_settings(|c, s| alerts::run_check(c, s, today, minute_of_day.min(1439)))
    }

    pub fn snooze_alerts(&self, keys: Vec<String>, until: NaiveDate) -> CoreResult<()> {
        self.with(|c| alerts::snooze(c, &keys, until))
    }

    /// Ids de notificaciones cuya causa ya se resolvió (Kotlin las retira).
    pub fn stale_notification_ids(&self, today: NaiveDate) -> CoreResult<Vec<i32>> {
        self.with_settings(|c, s| alerts::stale_notification_ids(c, s, today))
    }

    // ─────────────── Respaldo y demo ───────────────

    pub fn export_backup(&self, today: NaiveDate) -> CoreResult<String> {
        self.with(|c| {
            let json = backup::export_json(c, today)?;
            let mut s = settings::load(c)?;
            s.last_backup_date = Some(today);
            settings::save(c, &s)?;
            Ok(json)
        })
    }

    pub fn import_backup(&self, json: String) -> CoreResult<u64> {
        self.with(|c| backup::import_json(c, &json))
    }

    pub fn load_demo_data(&self, today: NaiveDate) -> CoreResult<()> {
        self.with_settings(|c, s| demo::load_demo(c, s, today))
    }

    pub fn clear_all_data(&self) -> CoreResult<()> {
        self.with(demo::clear_business_data)
    }
}

/// `$ 1.250.000`
#[uniffi::export]
pub fn format_clp(amount: i64) -> String {
    reports::format_clp(amount)
}

#[uniffi::export]
pub fn is_valid_rut(rut: String) -> bool {
    finance::is_valid_rut(&rut)
}

#[uniffi::export]
pub fn qr_matrix(text: String) -> CoreResult<QrMatrix> {
    inventory_fefo::qr_matrix(&text)
}

#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn today() -> NaiveDate {
        NaiveDate::from_ymd_opt(2026, 11, 19).unwrap()
    }

    #[test]
    fn core_is_send_sync() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<VintageCore>();
    }

    #[test]
    fn sale_with_iva_updates_dashboard_debit_and_ppm() {
        let core = VintageCore::new(":memory:".into()).unwrap();
        let before = core.dashboard(2026, 11, today()).unwrap();
        core.add_movement(MovementInput {
            kind: MovementKind::Income,
            date: today(),
            amount: 119_000,
            includes_iva: true,
            category: Category::VentaTienda,
            payment_method: PaymentMethod::Debit,
            document_type: DocumentType::Boleta,
            document_number: None,
            description: String::new(),
            photo_path: None,
            garment_id: None,
        })
        .unwrap();
        let after = core.dashboard(2026, 11, today()).unwrap();
        assert_eq!(after.sales_gross - before.sales_gross, 119_000);
        assert_eq!(after.tax.iva_debit - before.tax.iva_debit, 19_000);
        assert_eq!(after.tax.ppm - before.tax.ppm, 125);
        assert_eq!(after.chart.len(), 6);
    }

    #[test]
    fn pin_flow() {
        let core = VintageCore::new(":memory:".into()).unwrap();
        assert!(core.set_pin(Some("12".into())).is_err());
        core.set_pin(Some("1234".into())).unwrap();
        assert!(core.verify_pin("1234".into()).unwrap());
        assert!(!core.verify_pin("0000".into()).unwrap());
        // save_settings no puede borrar el PIN
        let s = core.get_settings().unwrap();
        core.save_settings(Settings { pin_hash: None, ..s }).unwrap();
        assert!(core.verify_pin("1234".into()).unwrap());
    }

    #[test]
    fn dashboard_attention_max_five() {
        let core = VintageCore::new(":memory:".into()).unwrap();
        core.load_demo_data(today()).unwrap();
        let d = core.dashboard(2026, 11, today()).unwrap();
        assert!(d.attention.len() <= 5);
        assert!(d.inventory.in_stock > 0);
        assert_eq!(d.debts.receivables_count, 4);
        let ranks: Vec<u8> = d.attention.iter().map(|a| a.priority.rank()).collect();
        let mut sorted = ranks.clone();
        sorted.sort();
        assert_eq!(ranks, sorted);
    }

    #[test]
    fn invalid_settings_are_rejected() {
        let core = VintageCore::new(":memory:".into()).unwrap();
        let s = core.get_settings().unwrap();
        let bad = Settings { iva_rate: dec!(1.5), ..s };
        assert!(matches!(core.save_settings(bad), Err(CoreError::Validation { .. })));
    }
}
