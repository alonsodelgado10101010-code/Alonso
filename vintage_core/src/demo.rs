//! Datos de demostración: 40 prendas (todos los colores del semáforo),
//! 3 meses de movimientos, 4 cobros (1 vencido), 3 pagos a proveedores
//! (1 vencido) y 2 lotes/fardos. Deterministas (mismo resultado cada vez).

use chrono::{Datelike, Duration, NaiveDate};
use rusqlite::{params, Connection};

use crate::backup::TABLES;
use crate::error::CoreResult;
use crate::finance;
use crate::inventory_fefo::{self, adjusted_days, season_end};
use crate::models::*;
use crate::settings::{self, Settings};

/// Generador congruencial lineal simple (determinista, sin dependencias).
struct Lcg(u64);
impl Lcg {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        self.0 >> 33
    }
    fn range(&mut self, lo: i64, hi: i64) -> i64 {
        lo + (self.next() % ((hi - lo + 1) as u64)) as i64
    }
    fn pick<'a, T>(&mut self, items: &'a [T]) -> &'a T {
        &items[(self.next() % items.len() as u64) as usize]
    }
}

/// Borra todos los datos del negocio (mantiene los ajustes).
pub fn clear_business_data(conn: &Connection) -> CoreResult<()> {
    let tx = conn.unchecked_transaction()?;
    tx.pragma_update(None, "defer_foreign_keys", "ON")?;
    for t in TABLES.iter().rev().filter(|t| **t != "settings") {
        tx.execute(&format!("DELETE FROM {t}"), [])?;
    }
    tx.execute("DELETE FROM sqlite_sequence", []).ok();
    tx.commit()?;
    Ok(())
}

fn round100(x: i64) -> i64 {
    (x + 50) / 100 * 100
}

pub fn load_demo(conn: &Connection, s_in: &Settings, today: NaiveDate) -> CoreResult<()> {
    clear_business_data(conn)?;
    let mut s = s_in.clone();
    if s.initial_cash == 0 {
        s.initial_cash = 2_500_000;
    }
    if s.store_name == Settings::default().store_name {
        s.store_name = "Ropa con Historia".into();
    }
    settings::save(conn, &s)?;
    let mut rng = Lcg(20_261_119);

    // ── Proveedores ──
    let suppliers = [
        ("Fardos del Pacífico SpA", "76.086.428-5", "Marcela Rojas", "+56 9 8765 4321", "ventas@fardospacifico.cl"),
        ("Lavandería La Burbuja", "11.111.111-1", "Jorge Pérez", "+56 2 2345 6789", "contacto@laburbuja.cl"),
        ("Arriendos Barrio Italia", "", "Administración", "+56 9 1234 5678", ""),
    ];
    let mut supplier_ids = Vec::new();
    for (name, rut, contact, phone, email) in suppliers {
        supplier_ids.push(finance::add_supplier(conn, &SupplierInput {
            name: name.into(),
            rut: rut.into(),
            contact: contact.into(),
            phone: phone.into(),
            email: email.into(),
        })?);
    }

    // ── Prendas activas: días restantes objetivo para cubrir todo el semáforo ──
    // 12 frescas, 7 rotar pronto, 7 rotar ya, 8 liquidar (5 entraron esta semana).
    let targets: [i64; 34] = [
        120, 95, 80, 70, 64, 55, 48, 42, 38, 35, 33, 31, // fresca
        30, 27, 24, 21, 19, 17, 15, // rotar pronto
        14, 12, 9, 7, 5, 3, 1, // rotar ya
        -1, -2, -3, -5, -6, // liquidar (esta semana)
        -15, -24, -40, // liquidar (antiguas)
    ];
    let types = GarmentType::all();
    let eras = Era::all();
    let brands = ["Levi's", "Wrangler", "Adidas", "Benetton", "Esprit", "Laura Ashley", "Pepe Jeans", "Nike", "Tommy Hilfiger", "Lee", "Fiorucci", "Sin marca"];
    let colors = ["azul", "negro", "crema", "terracota", "verde oliva", "café", "rojo", "estampado floral", "mostaza"];
    let materials = ["algodón", "mezclilla", "seda", "lana", "poliéster", "cuero", "lino", "terciopelo", "viscosa"];
    let sizes = ["XS", "S", "M", "L", "XL", "38", "40", "42"];
    let conditions = Condition::all();
    let locations = StoreLocation::all();

    let mut active_ids = Vec::new();
    for (i, target) in targets.iter().enumerate() {
        let t = types[i % types.len()];
        let era = eras[(i * 7 + 3) % eras.len()];
        let material = *rng.pick(&materials);
        let condition = conditions[(i * 5) % conditions.len()];
        let days = adjusted_days(&s, t, material, condition, era);
        let offset = (days - target).max(0);
        let intake = today - Duration::days(offset);
        // Temporada: se asigna una estacional solo si no recorta la FLR deseada.
        let wanted = [Season::OtonoInvierno, Season::PrimaveraVerano][i % 2];
        let flr = intake + Duration::days(days);
        let season = match season_end(&s, wanted, intake) {
            Some(end) if end >= flr => wanted,
            _ => Season::TodoElAno,
        };
        let price = round100(match t {
            GarmentType::Coleccion => rng.range(45_000, 120_000),
            GarmentType::ChaquetaAbrigo => rng.range(22_000, 55_000),
            GarmentType::Accesorio => rng.range(5_000, 18_000),
            _ => rng.range(8_000, 28_000),
        });
        let cost = round100(price * rng.range(25, 45) / 100);
        let id = inventory_fefo::add_garment(conn, &GarmentInput {
            garment_type: t,
            brand: brands[i % brands.len()].into(),
            era,
            size: rng.pick(&sizes).to_string(),
            color: rng.pick(&colors).to_string(),
            material: material.into(),
            condition,
            season,
            cost,
            price,
            supplier_id: if i % 3 == 0 { Some(supplier_ids[0]) } else { None },
            lot_id: None,
            intake_date: intake,
            location: locations[i % locations.len()],
            photo_paths: vec![],
            description: format!("{} {} de los {}", inventory_fefo::type_label(t), brands[i % brands.len()], era_label(era)),
        })?;
        active_ids.push(id);
    }
    // Dos prendas en reparación/lavandería (FLR pausada).
    for id in [active_ids[3], active_ids[15]] {
        inventory_fefo::set_status(conn, id, GarmentStatus::EnReparacion, today - Duration::days(5))?;
    }
    // Una reservada
    inventory_fefo::set_status(conn, active_ids[8], GarmentStatus::Reservada, today)?;

    // ── 6 prendas vendidas (para métricas) ──
    for i in 0..6usize {
        let t = types[(i + 2) % types.len()];
        let intake = today - Duration::days(rng.range(40, 85));
        let price = round100(rng.range(12_000, 35_000));
        let id = inventory_fefo::add_garment(conn, &GarmentInput {
            garment_type: t,
            brand: brands[(i + 5) % brands.len()].into(),
            era: eras[i % eras.len()],
            size: rng.pick(&sizes).to_string(),
            color: rng.pick(&colors).to_string(),
            material: rng.pick(&materials).to_string(),
            condition: Condition::MuyBueno,
            season: Season::TodoElAno,
            cost: round100(price * 35 / 100),
            price,
            supplier_id: None,
            lot_id: None,
            intake_date: intake,
            location: StoreLocation::Percha,
            photo_paths: vec![],
            description: String::new(),
        })?;
        let sold = (intake + Duration::days(rng.range(5, 35))).min(today);
        inventory_fefo::sell(conn, &s, &SaleInput {
            garment_id: id,
            price,
            date: sold,
            payment_method: *rng.pick(&PaymentMethod::all()),
            document_type: DocumentType::Boleta,
            category: Category::VentaTienda,
        })?;
    }

    // ── 2 lotes/fardos ──
    let lot_specs = [
        ("Fardo invierno USA", 280_000, DocumentType::FacturaCompra, Some(supplier_ids[0]), &active_ids[0..8]),
        ("Lote particular Providencia", 120_000, DocumentType::SinDocumento, None, &active_ids[20..26]),
    ];
    for (name, total, doc, sup, garments) in lot_specs {
        let first_intake: NaiveDate = garments
            .iter()
            .map(|id| inventory_fefo::get_garment(conn, *id).map(|g| g.intake_date))
            .collect::<CoreResult<Vec<_>>>()?
            .into_iter()
            .min()
            .unwrap_or(today);
        let lot = inventory_fefo::add_lot(conn, &s, &LotInput {
            name: name.into(),
            supplier_id: sup,
            purchase_date: first_intake,
            total_cost: total,
            document_type: doc,
            notes: String::new(),
            register_expense: true,
            payment_method: PaymentMethod::Transfer,
        })?;
        for id in garments {
            conn.execute("UPDATE garments SET lot_id=?1 WHERE id=?2", params![lot, id])?;
        }
        inventory_fefo::prorate_lot(conn, lot)?;
    }

    // ── 3 meses de movimientos ──
    let month_starts = {
        let mut v = Vec::new();
        let (mut y, mut m) = (today.year(), today.month());
        for _ in 0..3 {
            v.push(NaiveDate::from_ymd_opt(y, m, 1).unwrap());
            (y, m) = crate::db::prev_month(y, m);
        }
        v.reverse();
        v
    };
    let income_cats = [Category::VentaTienda, Category::VentaTienda, Category::VentaOnline, Category::VentaFeria];
    for start in month_starts {
        let (y, m) = (start.year(), start.month());
        let day = |d: u32| NaiveDate::from_ymd_opt(y, m, d).unwrap();
        let add = |kind, date: NaiveDate, amount, cat, doc, method, desc: &str| -> CoreResult<()> {
            if date > today {
                return Ok(());
            }
            finance::add_movement(conn, &s, &MovementInput {
                kind,
                date,
                amount,
                includes_iva: true,
                category: cat,
                payment_method: method,
                document_type: doc,
                document_number: if doc == DocumentType::SinDocumento { None } else { Some(format!("{}", 1000 + date.ordinal())) },
                description: desc.into(),
                photo_path: None,
                garment_id: None,
            })?;
            Ok(())
        };
        for _ in 0..22 {
            let d = day(rng.range(1, 28) as u32);
            let cat = *rng.pick(&income_cats);
            let doc = if rng.range(0, 9) == 0 { DocumentType::Factura } else { DocumentType::Boleta };
            add(MovementKind::Income, d, round100(rng.range(10_000, 55_000)), cat, doc, *rng.pick(&PaymentMethod::all()), "Venta de prendas")?;
        }
        add(MovementKind::Expense, day(5), 450_000, Category::Arriendo, DocumentType::Factura, PaymentMethod::Transfer, "Arriendo local")?;
        add(MovementKind::Expense, day(8), round100(rng.range(55_000, 75_000)), Category::ServiciosBasicos, DocumentType::Factura, PaymentMethod::Debit, "Luz, agua e internet")?;
        add(MovementKind::Expense, day(10), round100(rng.range(25_000, 45_000)), Category::LavanderiaReparacion, DocumentType::Boleta, PaymentMethod::Debit, "Lavandería y arreglos")?;
        add(MovementKind::Expense, day(12), 25_000, Category::Marketing, DocumentType::SinDocumento, PaymentMethod::Credit, "Publicidad en redes sociales")?;
        add(MovementKind::Expense, day(15), round100(rng.range(12_000, 22_000)), Category::Comisiones, DocumentType::Factura, PaymentMethod::Debit, "Comisiones Transbank")?;
        add(MovementKind::Expense, day(18), round100(rng.range(8_000, 16_000)), Category::Envios, DocumentType::Boleta, PaymentMethod::Debit, "Envíos a regiones")?;
        add(MovementKind::Expense, day(28), 300_000, Category::SueldosHonorarios, DocumentType::SinDocumento, PaymentMethod::Transfer, "Honorarios vendedora fin de semana")?;
    }

    // ── 4 cobros pendientes (1 vencido) ──
    let receivables = [
        ("Camila Soto", 45_000, -40, -20, ReceivableConcept::VentaCredito, 0),
        ("Tienda Retro Valpo", 120_000, -10, 3, ReceivableConcept::Consignacion, 40_000),
        ("Josefa Muñoz", 28_000, -5, 10, ReceivableConcept::Apartado, 10_000),
        ("Feria Persa Bío Bío", 60_000, -2, 25, ReceivableConcept::Otro, 0),
    ];
    for (customer, amount, issued, due, concept, paid) in receivables {
        let id = finance::add_receivable(conn, &ReceivableInput {
            customer: customer.into(),
            amount,
            issue_date: today + Duration::days(issued),
            due_date: today + Duration::days(due),
            concept,
            notes: String::new(),
        })?;
        if paid > 0 {
            finance::pay_receivable(conn, &s, id, paid, today + Duration::days(issued + 1), PaymentMethod::Transfer)?;
        }
    }

    // ── 3 pagos a proveedores (1 vencido) ──
    let payables = [
        (supplier_ids[0], 238_000, -30, -4, DocumentType::FacturaCompra, Category::CompraMercaderia),
        (supplier_ids[1], 42_000, -10, 2, DocumentType::Factura, Category::LavanderiaReparacion),
        (supplier_ids[2], 450_000, -3, 20, DocumentType::Factura, Category::Arriendo),
    ];
    for (sid, amount, issued, due, doc, cat) in payables {
        finance::add_payable(conn, &PayableInput {
            supplier_id: Some(sid),
            supplier_name: String::new(),
            amount,
            issue_date: today + Duration::days(issued),
            due_date: today + Duration::days(due),
            document_type: doc,
            document_number: Some(format!("{}", 4500 + amount / 1000)),
            category: cat,
            notes: String::new(),
        })?;
    }
    Ok(())
}

pub fn era_label(e: Era) -> &'static str {
    match e {
        Era::Sesentas => "60s",
        Era::Setentas => "70s",
        Era::Ochentas => "80s",
        Era::Noventas => "90s",
        Era::Y2k => "Y2K",
        Era::Otra => "otra época",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::alerts;
    use crate::models::Semaphore;

    fn today() -> NaiveDate {
        NaiveDate::from_ymd_opt(2026, 11, 19).unwrap()
    }

    #[test]
    fn demo_has_required_shape() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        load_demo(&conn, &s, today()).unwrap();
        let s = settings::load(&conn).unwrap();
        let all = inventory_fefo::all_garments(&conn).unwrap();
        assert_eq!(all.len(), 40);
        let inv = inventory_fefo::list_inventory(&conn, &s, &InventoryFilter::default(), today()).unwrap();
        for sem in Semaphore::all() {
            assert!(inv.iter().any(|v| v.semaphore == sem), "falta color {sem:?}");
        }
        let rec = finance::list_receivables(&conn, today(), true).unwrap();
        assert_eq!(rec.len(), 4);
        assert_eq!(rec.iter().filter(|r| r.status == DebtStatus::Overdue).count(), 1);
        let pay = finance::list_payables(&conn, today(), true).unwrap();
        assert_eq!(pay.len(), 3);
        assert_eq!(pay.iter().filter(|p| p.status == DebtStatus::Overdue).count(), 1);
        assert_eq!(inventory_fefo::list_lots(&conn).unwrap().len(), 2);
        // Reload limpia y vuelve a cargar lo mismo
        load_demo(&conn, &s, today()).unwrap();
        assert_eq!(inventory_fefo::all_garments(&conn).unwrap().len(), 40);
    }

    #[test]
    fn demo_never_exceeds_three_pushes_per_day() {
        let conn = crate::db::open(":memory:").unwrap();
        load_demo(&conn, &Settings::default(), today()).unwrap();
        let s = settings::load(&conn).unwrap();
        // Se simulan 30 días, con chequeos cada hora (incluye horario silencioso).
        for offset in 0..30 {
            let day = today() + Duration::days(offset);
            let mut total = 0;
            for hour in 0..24 {
                let plan = alerts::run_check(&conn, &s, day, hour * 60).unwrap();
                total += plan.notifications.len();
                assert!(plan.sent_today <= 3);
            }
            assert!(total <= 3, "día {day}: {total} notificaciones");
            assert_eq!(alerts::sent_count(&conn, day).unwrap() as usize, total);
        }
    }

    #[test]
    fn demo_first_check_combines_into_summary() {
        let conn = crate::db::open(":memory:").unwrap();
        load_demo(&conn, &Settings::default(), today()).unwrap();
        let s = settings::load(&conn).unwrap();
        let plan = alerts::run_check(&conn, &s, today(), 9 * 60).unwrap();
        assert!(!plan.notifications.is_empty());
        assert!(plan.notifications.len() <= 3);
        let in_app = alerts::collect_alerts(&conn, &s, today()).unwrap();
        assert!(in_app.iter().any(|a| a.priority == AlertPriority::Critical));
        assert!(in_app.iter().any(|a| a.kind == AlertKind::InventoryRotateNow));
    }
}
