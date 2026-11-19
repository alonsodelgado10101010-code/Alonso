//! Motor FEFO adaptado a ropa vintage: "primero en perder valor comercial,
//! primero en salir".
//!
//! ```text
//! días = vida_comercial_base(tipo)
//!        × 0,80 si el material es delicado (seda, lana, cuero, piel, terciopelo)
//!        × 0,75 si el estado es "Con detalles"
//!        × 1,30 si la época está marcada "en alza"
//! (los factores se multiplican y el resultado se redondea a días enteros, mitad hacia arriba)
//! FLR_base = fecha_ingreso + días
//! si la temporada de la prenda termina antes → FLR_base = fin de esa temporada
//! FLR efectiva = FLR_base + días en reparación/lavandería (la FLR se pausa)
//! ```
//!
//! Temporadas (hemisferio sur, configurables en Ajustes):
//! Primavera-Verano 21-sep → 20-mar · Otoño-Invierno 21-mar → 20-sep.

use chrono::{Datelike, Duration, NaiveDate};
use rusqlite::{params, Connection, OptionalExtension, Row};
use rust_decimal::Decimal;

use crate::error::{CoreError, CoreResult};
use crate::finance::{add_movement_linked, MovementLinks};
use crate::models::*;
use crate::settings::Settings;
use crate::tax::{round_clp, split_iva};

// ───────────────────────────── cálculo puro ─────────────────────────────

/// Días de vida comercial ajustados (antes de temporada y pausas).
pub fn adjusted_days(s: &Settings, t: GarmentType, material: &str, condition: Condition, era: Era) -> i64 {
    let mut days = Decimal::from(s.lifetime_days(t));
    if s.is_delicate(material) {
        days *= dec!(0.80);
    }
    if condition == Condition::ConDetalles {
        days *= dec!(0.75);
    }
    if s.trending_eras.contains(&era) {
        days *= dec!(1.30);
    }
    round_clp(days).max(1)
}

fn ymd(y: i32, m: u32, d: u32) -> NaiveDate {
    NaiveDate::from_ymd_opt(y, m, d)
        .or_else(|| NaiveDate::from_ymd_opt(y, m, 28))
        .expect("fecha de temporada válida")
}

/// Fin de la temporada de la prenda que está en curso (o la próxima, si la
/// prenda ingresó fuera de temporada). `None` para "Todo el año".
pub fn season_end(s: &Settings, season: Season, intake: NaiveDate) -> Option<NaiveDate> {
    let y = intake.year();
    let pv_end_this = ymd(y, s.pv_end_month, s.pv_end_day);
    let oi_end_this = ymd(y, s.oi_end_month, s.oi_end_day);
    match season {
        Season::TodoElAno => None,
        // PV va desde el día siguiente al fin de OI hasta el fin de PV del año siguiente.
        Season::PrimaveraVerano => Some(if intake <= pv_end_this {
            pv_end_this
        } else {
            ymd(y + 1, s.pv_end_month, s.pv_end_day)
        }),
        // OI va desde el día siguiente al fin de PV hasta el fin de OI.
        Season::OtonoInvierno => Some(if intake <= oi_end_this {
            oi_end_this
        } else {
            ymd(y + 1, s.oi_end_month, s.oi_end_day)
        }),
    }
}

/// FLR base (sin pausas).
pub fn base_flr(s: &Settings, g: &Garment) -> NaiveDate {
    let days = adjusted_days(s, g.garment_type, &g.material, g.condition, g.era);
    let mut flr = g.intake_date + Duration::days(days);
    if let Some(end) = season_end(s, g.season, g.intake_date) {
        if end < flr && end >= g.intake_date {
            flr = end;
        }
    }
    flr
}

/// FLR efectiva al día `today`, incluyendo pausas por reparación/lavandería.
pub fn effective_flr(s: &Settings, g: &Garment, today: NaiveDate) -> NaiveDate {
    let mut paused = g.paused_days.max(0);
    if let Some(since) = g.paused_since {
        paused += (today - since).num_days().max(0);
    }
    base_flr(s, g) + Duration::days(paused)
}

pub fn classify(days_remaining: i64) -> Semaphore {
    match days_remaining {
        d if d > 30 => Semaphore::Fresca,
        d if d >= 15 => Semaphore::RotarPronto,
        d if d >= 0 => Semaphore::RotarYa,
        _ => Semaphore::Liquidar,
    }
}

/// Acción sugerida y descuento sugerido (%).
pub fn suggestion(sem: Semaphore) -> (&'static str, u32) {
    match sem {
        Semaphore::Fresca => ("Precio completo.", 0),
        Semaphore::RotarPronto => ("Muévela a vitrina o zona visible y publícala en redes.", 0),
        Semaphore::RotarYa => ("Aplica una rebaja del 20%.", 20),
        Semaphore::Liquidar => (
            "Rebaja 40–50%, arma un pack/outlet, o considera upcycling o donación.",
            40,
        ),
    }
}

pub fn view(s: &Settings, g: Garment, today: NaiveDate) -> GarmentView {
    let flr = effective_flr(s, &g, today);
    let days_remaining = (flr - today).num_days();
    let semaphore = classify(days_remaining);
    let (action, pct) = suggestion(semaphore);
    let qr_payload = format!("VINTAGE:{}", g.sku);
    GarmentView {
        garment: g,
        flr,
        days_remaining,
        semaphore,
        suggested_action: action.to_string(),
        suggested_discount_pct: pct,
        qr_payload,
    }
}

/// Precio rebajado redondeado a la centena (mitad hacia arriba).
pub fn discounted_price(price: i64, pct: u32) -> i64 {
    let raw = Decimal::from(price) * (Decimal::from(100u32.saturating_sub(pct.min(100))) / Decimal::from(100));
    round_clp(raw / Decimal::from(100)) * 100
}

pub fn preview_discount(g: &Garment, pct: u32, iva_rate: Decimal) -> DiscountPreview {
    let new_price = discounted_price(g.price, pct);
    let (_, net, _) = split_iva(new_price, true, iva_rate);
    let margin = net - g.cost;
    let margin_pct = if net > 0 {
        (Decimal::from(margin) / Decimal::from(net)).round_dp(4)
    } else {
        Decimal::ZERO
    };
    DiscountPreview {
        garment_id: g.id,
        discount_pct: pct,
        old_price: g.price,
        new_price,
        new_price_net: net,
        cost: g.cost,
        margin,
        margin_pct,
        below_cost: net < g.cost,
    }
}

/// Reparte `total` entre ítems proporcional a `weights` (o en partes iguales si
/// todos los pesos son 0) usando el método del mayor resto: la suma cuadra exacta.
pub fn prorate(total: i64, weights: &[i64]) -> Vec<i64> {
    let n = weights.len();
    if n == 0 {
        return vec![];
    }
    let w: Vec<i64> = if weights.iter().all(|w| *w <= 0) {
        vec![1; n]
    } else {
        weights.iter().map(|w| (*w).max(0)).collect()
    };
    let sum: i128 = w.iter().map(|x| *x as i128).sum();
    let mut parts: Vec<i64> = Vec::with_capacity(n);
    let mut rems: Vec<(i128, usize)> = Vec::with_capacity(n);
    for (i, wi) in w.iter().enumerate() {
        let num = total as i128 * *wi as i128;
        parts.push((num / sum) as i64);
        rems.push((num % sum, i));
    }
    let mut left = total - parts.iter().sum::<i64>();
    rems.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    for (_, i) in rems {
        if left <= 0 {
            break;
        }
        parts[i] += 1;
        left -= 1;
    }
    parts
}

// ───────────────────────────── persistencia ─────────────────────────────

const GARMENT_COLS: &str = "id, sku, garment_type, brand, era, size, color, material, condition, season, cost, price, \
    supplier_id, lot_id, intake_date, location, status, photo_paths, description, paused_days, paused_since, \
    sold_date, sold_price, last_discount_date";

fn row_to_garment(r: &Row) -> rusqlite::Result<Garment> {
    let photos: String = r.get(17)?;
    Ok(Garment {
        id: r.get(0)?,
        sku: r.get(1)?,
        garment_type: r.get(2)?,
        brand: r.get(3)?,
        era: r.get(4)?,
        size: r.get(5)?,
        color: r.get(6)?,
        material: r.get(7)?,
        condition: r.get(8)?,
        season: r.get(9)?,
        cost: r.get(10)?,
        price: r.get(11)?,
        supplier_id: r.get(12)?,
        lot_id: r.get(13)?,
        intake_date: r.get(14)?,
        location: r.get(15)?,
        status: r.get(16)?,
        photo_paths: serde_json::from_str(&photos).unwrap_or_default(),
        description: r.get(18)?,
        paused_days: r.get(19)?,
        paused_since: r.get(20)?,
        sold_date: r.get(21)?,
        sold_price: r.get(22)?,
        last_discount_date: r.get(23)?,
    })
}

fn validate_garment(i: &GarmentInput) -> CoreResult<()> {
    if i.cost < 0 || i.price < 0 {
        return Err(CoreError::validation("costo y precio no pueden ser negativos"));
    }
    Ok(())
}

pub fn add_garment(conn: &Connection, i: &GarmentInput) -> CoreResult<i64> {
    validate_garment(i)?;
    let tx = conn.unchecked_transaction()?;
    let tmp_sku = format!("TMP-{}", i.intake_date);
    tx.execute(
        "INSERT INTO garments (sku, garment_type, brand, era, size, color, material, condition, season, cost, price,
            supplier_id, lot_id, intake_date, location, status, photo_paths, description)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, 'en_stock', ?16, ?17)",
        params![
            tmp_sku,
            i.garment_type,
            i.brand,
            i.era,
            i.size,
            i.color,
            i.material,
            i.condition,
            i.season,
            i.cost,
            i.price,
            i.supplier_id,
            i.lot_id,
            i.intake_date,
            i.location,
            serde_json::to_string(&i.photo_paths)?,
            i.description
        ],
    )?;
    let id = tx.last_insert_rowid();
    tx.execute("UPDATE garments SET sku=?1 WHERE id=?2", params![sku_for(id), id])?;
    if let Some(lot) = i.lot_id {
        prorate_lot(&tx, lot)?;
    }
    tx.commit()?;
    Ok(id)
}

/// SKU automático: VM-00001.
pub fn sku_for(id: i64) -> String {
    format!("VM-{id:05}")
}

pub fn update_garment(conn: &Connection, id: i64, i: &GarmentInput, today: NaiveDate) -> CoreResult<()> {
    validate_garment(i)?;
    let old = get_garment(conn, id)?;
    let tx = conn.unchecked_transaction()?;
    tx.execute(
        "UPDATE garments SET garment_type=?1, brand=?2, era=?3, size=?4, color=?5, material=?6, condition=?7,
            season=?8, cost=?9, price=?10, supplier_id=?11, lot_id=?12, intake_date=?13, location=?14,
            photo_paths=?15, description=?16 WHERE id=?17",
        params![
            i.garment_type,
            i.brand,
            i.era,
            i.size,
            i.color,
            i.material,
            i.condition,
            i.season,
            i.cost,
            i.price,
            i.supplier_id,
            i.lot_id,
            i.intake_date,
            i.location,
            serde_json::to_string(&i.photo_paths)?,
            i.description,
            id
        ],
    )?;
    if old.price != i.price {
        tx.execute(
            "INSERT INTO price_changes (garment_id, date, old_price, new_price, reason) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![id, today, old.price, i.price, "Edición manual"],
        )?;
    }
    for lot in [old.lot_id, i.lot_id].into_iter().flatten() {
        prorate_lot(&tx, lot)?;
    }
    tx.commit()?;
    Ok(())
}

pub fn delete_garment(conn: &Connection, id: i64) -> CoreResult<()> {
    let g = get_garment(conn, id)?;
    let tx = conn.unchecked_transaction()?;
    tx.execute("UPDATE movements SET garment_id=NULL WHERE garment_id=?1", params![id])?;
    tx.execute("DELETE FROM garments WHERE id=?1", params![id])?;
    if let Some(lot) = g.lot_id {
        prorate_lot(&tx, lot)?;
    }
    tx.commit()?;
    Ok(())
}

pub fn get_garment(conn: &Connection, id: i64) -> CoreResult<Garment> {
    conn.query_row(
        &format!("SELECT {GARMENT_COLS} FROM garments WHERE id=?1"),
        params![id],
        row_to_garment,
    )
    .optional()?
    .ok_or_else(|| CoreError::not_found("prenda", id))
}

pub fn find_by_sku(conn: &Connection, sku: &str) -> CoreResult<Option<Garment>> {
    let sku = sku.trim().trim_start_matches("VINTAGE:");
    Ok(conn
        .query_row(
            &format!("SELECT {GARMENT_COLS} FROM garments WHERE sku=?1 COLLATE NOCASE"),
            params![sku],
            row_to_garment,
        )
        .optional()?)
}

pub fn all_garments(conn: &Connection) -> CoreResult<Vec<Garment>> {
    let mut stmt = conn.prepare(&format!("SELECT {GARMENT_COLS} FROM garments"))?;
    let rows = stmt.query_map([], row_to_garment)?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// Prendas que siguen en la tienda (cuentan para el semáforo).
pub fn is_active(status: GarmentStatus) -> bool {
    matches!(
        status,
        GarmentStatus::EnStock | GarmentStatus::Reservada | GarmentStatus::EnReparacion
    )
}

/// Inventario filtrado y **siempre ordenado por FLR ascendente**.
pub fn list_inventory(
    conn: &Connection,
    s: &Settings,
    f: &InventoryFilter,
    today: NaiveDate,
) -> CoreResult<Vec<GarmentView>> {
    let q = f.query.as_ref().map(|q| q.trim().to_lowercase()).filter(|q| !q.is_empty());
    let mut out: Vec<GarmentView> = all_garments(conn)?
        .into_iter()
        .filter(|g| f.include_inactive || is_active(g.status))
        .filter(|g| f.garment_type.map_or(true, |t| g.garment_type == t))
        .filter(|g| f.season.map_or(true, |t| g.season == t))
        .filter(|g| f.era.map_or(true, |t| g.era == t))
        .filter(|g| {
            f.size
                .as_ref()
                .map_or(true, |sz| sz.trim().is_empty() || g.size.eq_ignore_ascii_case(sz.trim()))
        })
        .filter(|g| {
            q.as_ref().map_or(true, |q| {
                [&g.sku, &g.brand, &g.color, &g.material, &g.description, &g.size]
                    .iter()
                    .any(|field| field.to_lowercase().contains(q.as_str()))
            })
        })
        .map(|g| view(s, g, today))
        .filter(|v| f.semaphore.map_or(true, |sem| v.semaphore == sem))
        .collect();
    out.sort_by(|a, b| a.flr.cmp(&b.flr).then(a.garment.id.cmp(&b.garment.id)));
    Ok(out)
}

/// Cambia el estado de la prenda. Entrar a "En reparación" pausa la FLR y
/// salir de ese estado acumula los días pausados.
pub fn set_status(conn: &Connection, id: i64, status: GarmentStatus, today: NaiveDate) -> CoreResult<Garment> {
    let g = get_garment(conn, id)?;
    if status == GarmentStatus::Vendida {
        return Err(CoreError::validation("para vender usa 'Registrar venta' (genera el ingreso)"));
    }
    let mut paused_days = g.paused_days;
    let mut paused_since = g.paused_since;
    if g.status == GarmentStatus::EnReparacion && status != GarmentStatus::EnReparacion {
        if let Some(since) = paused_since.take() {
            paused_days += (today - since).num_days().max(0);
        }
    }
    if status == GarmentStatus::EnReparacion && g.status != GarmentStatus::EnReparacion {
        paused_since = Some(today);
    }
    conn.execute(
        "UPDATE garments SET status=?1, paused_days=?2, paused_since=?3 WHERE id=?4",
        params![status, paused_days, paused_since, id],
    )?;
    get_garment(conn, id)
}

pub fn set_location(conn: &Connection, id: i64, location: StoreLocation) -> CoreResult<()> {
    conn.execute("UPDATE garments SET location=?1 WHERE id=?2", params![location, id])?;
    Ok(())
}

/// Vende la prenda: la descuenta del stock y crea el ingreso con su IVA.
pub fn sell(conn: &Connection, s: &Settings, sale: &SaleInput) -> CoreResult<i64> {
    let g = get_garment(conn, sale.garment_id)?;
    if !is_active(g.status) {
        return Err(CoreError::validation("la prenda no está disponible para la venta"));
    }
    if sale.price <= 0 {
        return Err(CoreError::validation("el precio de venta debe ser mayor a cero"));
    }
    if sale.category.kind() != MovementKind::Income {
        return Err(CoreError::validation("la categoría debe ser de ingreso"));
    }
    let tx = conn.unchecked_transaction()?;
    let input = MovementInput {
        kind: MovementKind::Income,
        date: sale.date,
        amount: sale.price,
        includes_iva: true,
        category: sale.category,
        payment_method: sale.payment_method,
        document_type: sale.document_type,
        document_number: None,
        description: format!("Venta {} · {} {}", g.sku, type_label(g.garment_type), g.brand).trim().to_string(),
        photo_path: None,
        garment_id: Some(g.id),
    };
    let mid = add_movement_linked(&tx, s, &input, MovementLinks::default())?;
    let mut paused_days = g.paused_days;
    if let Some(since) = g.paused_since {
        paused_days += (sale.date - since).num_days().max(0);
    }
    tx.execute(
        "UPDATE garments SET status='vendida', sold_date=?1, sold_price=?2, paused_days=?3, paused_since=NULL WHERE id=?4",
        params![sale.date, sale.price, paused_days, g.id],
    )?;
    tx.commit()?;
    Ok(mid)
}

pub fn type_label(t: GarmentType) -> &'static str {
    match t {
        GarmentType::BasicoPolera => "Básico/polera",
        GarmentType::JeansPantalon => "Jeans/pantalón",
        GarmentType::VestidoFalda => "Vestido/falda",
        GarmentType::ChaquetaAbrigo => "Chaqueta/abrigo",
        GarmentType::Accesorio => "Accesorio",
        GarmentType::Coleccion => "Pieza de colección",
    }
}

/// Aplica una rebaja y la deja en el historial de precios.
pub fn apply_discount(
    conn: &Connection,
    s: &Settings,
    id: i64,
    pct: u32,
    today: NaiveDate,
) -> CoreResult<DiscountPreview> {
    if pct == 0 || pct >= 100 {
        return Err(CoreError::validation("la rebaja debe estar entre 1% y 99%"));
    }
    let g = get_garment(conn, id)?;
    let p = preview_discount(&g, pct, s.iva_rate);
    set_price(conn, id, p.new_price, today, &format!("Rebaja {pct}%"))?;
    Ok(p)
}

pub fn set_price(conn: &Connection, id: i64, new_price: i64, today: NaiveDate, reason: &str) -> CoreResult<()> {
    let g = get_garment(conn, id)?;
    if new_price < 0 {
        return Err(CoreError::validation("precio inválido"));
    }
    let tx = conn.unchecked_transaction()?;
    tx.execute(
        "INSERT INTO price_changes (garment_id, date, old_price, new_price, reason) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![id, today, g.price, new_price, reason],
    )?;
    tx.execute(
        "UPDATE garments SET price=?1, last_discount_date=?2 WHERE id=?3",
        params![new_price, today, id],
    )?;
    tx.commit()?;
    Ok(())
}

pub fn price_history(conn: &Connection, garment_id: i64) -> CoreResult<Vec<PriceChange>> {
    let mut stmt = conn.prepare(
        "SELECT id, garment_id, date, old_price, new_price, reason FROM price_changes
         WHERE garment_id=?1 ORDER BY date DESC, id DESC",
    )?;
    let rows = stmt.query_map(params![garment_id], |r| {
        Ok(PriceChange {
            id: r.get(0)?,
            garment_id: r.get(1)?,
            date: r.get(2)?,
            old_price: r.get(3)?,
            new_price: r.get(4)?,
            reason: r.get(5)?,
        })
    })?;
    Ok(rows.collect::<Result<_, _>>()?)
}

// ───────────────────────────── Lotes / fardos ─────────────────────────────

pub fn add_lot(conn: &Connection, s: &Settings, i: &LotInput) -> CoreResult<i64> {
    if i.name.trim().is_empty() {
        return Err(CoreError::validation("el lote necesita un nombre"));
    }
    if i.total_cost < 0 {
        return Err(CoreError::validation("el costo del lote no puede ser negativo"));
    }
    let tx = conn.unchecked_transaction()?;
    tx.execute(
        "INSERT INTO lots (name, supplier_id, purchase_date, total_cost, document_type, notes)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![i.name.trim(), i.supplier_id, i.purchase_date, i.total_cost, i.document_type, i.notes],
    )?;
    let lot_id = tx.last_insert_rowid();
    if i.register_expense && i.total_cost > 0 {
        let mi = MovementInput {
            kind: MovementKind::Expense,
            date: i.purchase_date,
            amount: i.total_cost,
            includes_iva: true,
            category: Category::CompraMercaderia,
            payment_method: i.payment_method,
            document_type: i.document_type,
            document_number: None,
            description: format!("Compra lote: {}", i.name.trim()),
            photo_path: None,
            garment_id: None,
        };
        let mid = add_movement_linked(&tx, s, &mi, MovementLinks { lot_id: Some(lot_id), ..Default::default() })?;
        tx.execute("UPDATE lots SET movement_id=?1 WHERE id=?2", params![mid, lot_id])?;
    }
    tx.commit()?;
    Ok(lot_id)
}

pub fn delete_lot(conn: &Connection, id: i64) -> CoreResult<()> {
    conn.execute("DELETE FROM lots WHERE id=?1", params![id])?;
    Ok(())
}

/// Costo prorrateable del lote: si el lote se compró con factura, el costo es el neto
/// (el IVA es crédito fiscal); si no, el total pagado.
fn lot_cost_basis(total: i64, doc: DocumentType, iva_rate: Decimal) -> i64 {
    if doc.gives_iva_credit() {
        split_iva(total, true, iva_rate).1
    } else {
        total
    }
}

/// Reparte el costo del lote entre sus prendas, proporcional al precio de venta.
pub fn prorate_lot(conn: &Connection, lot_id: i64) -> CoreResult<()> {
    let s = crate::settings::load(conn)?;
    let lot: Option<(i64, DocumentType)> = conn
        .query_row(
            "SELECT total_cost, document_type FROM lots WHERE id=?1",
            params![lot_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let Some((total, doc)) = lot else { return Ok(()) };
    let items: Vec<(i64, i64)> = {
        let mut stmt = conn.prepare("SELECT id, price FROM garments WHERE lot_id=?1 ORDER BY id")?;
        let rows = stmt.query_map(params![lot_id], |r| Ok((r.get(0)?, r.get(1)?)))?;
        rows.collect::<Result<_, _>>()?
    };
    let weights: Vec<i64> = items.iter().map(|(_, p)| *p).collect();
    let parts = prorate(lot_cost_basis(total, doc, s.iva_rate), &weights);
    for ((gid, _), cost) in items.iter().zip(parts) {
        conn.execute("UPDATE garments SET cost=?1 WHERE id=?2", params![cost, gid])?;
    }
    Ok(())
}

pub fn list_lots(conn: &Connection) -> CoreResult<Vec<Lot>> {
    let mut stmt = conn.prepare(
        "SELECT l.id, l.name, l.supplier_id, l.purchase_date, l.total_cost, l.document_type, l.notes,
                (SELECT count(*) FROM garments g WHERE g.lot_id = l.id), l.movement_id
         FROM lots l ORDER BY l.purchase_date DESC, l.id DESC",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok(Lot {
            id: r.get(0)?,
            name: r.get(1)?,
            supplier_id: r.get(2)?,
            purchase_date: r.get(3)?,
            total_cost: r.get(4)?,
            document_type: r.get(5)?,
            notes: r.get(6)?,
            garment_count: r.get(7)?,
            movement_id: r.get(8)?,
        })
    })?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// Matriz QR para imprimir la etiqueta de la prenda.
pub fn qr_matrix(text: &str) -> CoreResult<QrMatrix> {
    let code = qrcode::QrCode::new(text.as_bytes())
        .map_err(|e| CoreError::validation(format!("no se pudo generar el QR: {e}")))?;
    let size = code.width() as u32;
    let modules = code
        .to_colors()
        .into_iter()
        .map(|c| c == qrcode::Color::Dark)
        .collect();
    Ok(QrMatrix { size, modules })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(y: i32, m: u32, day: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, day).unwrap()
    }

    pub(crate) fn garment(t: GarmentType, season: Season, intake: NaiveDate) -> Garment {
        Garment {
            id: 1,
            sku: "VM-00001".into(),
            garment_type: t,
            brand: "Levi's".into(),
            era: Era::Noventas,
            size: "M".into(),
            color: "azul".into(),
            material: "algodón".into(),
            condition: Condition::MuyBueno,
            season,
            cost: 5_000,
            price: 20_000,
            supplier_id: None,
            lot_id: None,
            intake_date: intake,
            location: StoreLocation::Percha,
            status: GarmentStatus::EnStock,
            photo_paths: vec![],
            description: String::new(),
            paused_days: 0,
            paused_since: None,
            sold_date: None,
            sold_price: None,
            last_discount_date: None,
        }
    }

    #[test]
    fn base_lifetime_without_adjustments() {
        let s = Settings::default();
        let g = garment(GarmentType::JeansPantalon, Season::TodoElAno, d(2026, 1, 1));
        assert_eq!(base_flr(&s, &g), d(2026, 4, 1)); // +90 días
    }

    #[test]
    fn adjustments_are_multiplicative() {
        let mut s = Settings::default();
        // 120 × 0,8 (lana) × 0,75 (con detalles) = 72
        assert_eq!(adjusted_days(&s, GarmentType::ChaquetaAbrigo, "Lana", Condition::ConDetalles, Era::Setentas), 72);
        s.trending_eras = vec![Era::Setentas];
        // 72 × 1,3 = 93,6 → 94
        assert_eq!(adjusted_days(&s, GarmentType::ChaquetaAbrigo, "Lana", Condition::ConDetalles, Era::Setentas), 94);
        // 60 × 1,3 = 78
        assert_eq!(adjusted_days(&s, GarmentType::BasicoPolera, "algodón", Condition::Bueno, Era::Setentas), 78);
    }

    #[test]
    fn season_change_cuts_flr() {
        let s = Settings::default();
        // Abrigo OI ingresa el 1-ago: 120 días → 29-nov, pero OI termina el 20-sep.
        let g = garment(GarmentType::ChaquetaAbrigo, Season::OtonoInvierno, d(2026, 8, 1));
        assert_eq!(base_flr(&s, &g), d(2026, 9, 20));
        // Vestido PV ingresa el 1-feb: 75 días → 17-abr, PV termina el 20-mar.
        let g = garment(GarmentType::VestidoFalda, Season::PrimaveraVerano, d(2026, 2, 1));
        assert_eq!(base_flr(&s, &g), d(2026, 3, 20));
        // Vestido PV ingresa el 1-oct: PV termina el 20-mar del año siguiente; 75 días → 15-dic.
        let g = garment(GarmentType::VestidoFalda, Season::PrimaveraVerano, d(2026, 10, 1));
        assert_eq!(base_flr(&s, &g), d(2026, 12, 15));
        // Abrigo OI que ingresa fuera de temporada (octubre) → la próxima OI termina en sep. siguiente.
        let g = garment(GarmentType::ChaquetaAbrigo, Season::OtonoInvierno, d(2026, 10, 1));
        assert_eq!(season_end(&s, Season::OtonoInvierno, g.intake_date), Some(d(2027, 9, 20)));
        assert_eq!(base_flr(&s, &g), d(2027, 1, 29));
    }

    #[test]
    fn semaphore_thresholds() {
        assert_eq!(classify(31), Semaphore::Fresca);
        assert_eq!(classify(30), Semaphore::RotarPronto);
        assert_eq!(classify(15), Semaphore::RotarPronto);
        assert_eq!(classify(14), Semaphore::RotarYa);
        assert_eq!(classify(0), Semaphore::RotarYa);
        assert_eq!(classify(-1), Semaphore::Liquidar);
        assert_eq!(suggestion(Semaphore::RotarYa).1, 20);
    }

    #[test]
    fn repair_pauses_flr() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        let g = garment(GarmentType::JeansPantalon, Season::TodoElAno, d(2026, 1, 1));
        let id = add_garment(&conn, &to_input(&g)).unwrap();
        set_status(&conn, id, GarmentStatus::EnReparacion, d(2026, 2, 1)).unwrap();
        let during = get_garment(&conn, id).unwrap();
        // Mientras está en reparación los días restantes no bajan.
        let v1 = view(&s, during.clone(), d(2026, 2, 1));
        let v2 = view(&s, during, d(2026, 2, 11));
        assert_eq!(v1.days_remaining, v2.days_remaining);
        let back = set_status(&conn, id, GarmentStatus::EnStock, d(2026, 2, 11)).unwrap();
        assert_eq!(back.paused_days, 10);
        assert_eq!(effective_flr(&s, &back, d(2026, 3, 1)), d(2026, 4, 11));
    }

    fn to_input(g: &Garment) -> GarmentInput {
        GarmentInput {
            garment_type: g.garment_type,
            brand: g.brand.clone(),
            era: g.era,
            size: g.size.clone(),
            color: g.color.clone(),
            material: g.material.clone(),
            condition: g.condition,
            season: g.season,
            cost: g.cost,
            price: g.price,
            supplier_id: None,
            lot_id: g.lot_id,
            intake_date: g.intake_date,
            location: g.location,
            photo_paths: vec![],
            description: String::new(),
        }
    }

    #[test]
    fn discount_below_cost_is_flagged() {
        let s = Settings::default();
        let mut g = garment(GarmentType::BasicoPolera, Season::TodoElAno, d(2026, 1, 1));
        g.price = 10_000;
        g.cost = 6_000;
        // 20% → 8.000; neto 6.723 > 6.000
        let p = preview_discount(&g, 20, s.iva_rate);
        assert_eq!(p.new_price, 8_000);
        assert_eq!(p.new_price_net, 6_723);
        assert_eq!(p.margin, 723);
        assert!(!p.below_cost);
        // 50% → 5.000; neto 4.202 < 6.000 → bajo costo
        let p = preview_discount(&g, 50, s.iva_rate);
        assert_eq!(p.new_price, 5_000);
        assert!(p.below_cost);
        assert!(p.margin < 0);
    }

    #[test]
    fn discounted_price_rounds_to_hundreds() {
        assert_eq!(discounted_price(12_990, 20), 10_400); // 10.392 → 10.400
        assert_eq!(discounted_price(12_490, 40), 7_500); // 7.494 → 7.500
        assert_eq!(discounted_price(250, 20), 200);
    }

    #[test]
    fn apply_discount_records_history() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        let g = garment(GarmentType::BasicoPolera, Season::TodoElAno, d(2026, 1, 1));
        let id = add_garment(&conn, &to_input(&g)).unwrap();
        apply_discount(&conn, &s, id, 20, d(2026, 3, 1)).unwrap();
        let h = price_history(&conn, id).unwrap();
        assert_eq!(h.len(), 1);
        assert_eq!((h[0].old_price, h[0].new_price), (20_000, 16_000));
        assert_eq!(get_garment(&conn, id).unwrap().price, 16_000);
        assert!(apply_discount(&conn, &s, id, 0, d(2026, 3, 1)).is_err());
    }

    #[test]
    fn prorate_is_exact() {
        assert_eq!(prorate(100, &[1, 1, 1]), vec![34, 33, 33]);
        assert_eq!(prorate(100_000, &[10_000, 30_000]), vec![25_000, 75_000]);
        assert_eq!(prorate(10, &[0, 0]), vec![5, 5]);
        let p = prorate(99_999, &[7, 13, 29, 51]);
        assert_eq!(p.iter().sum::<i64>(), 99_999);
    }

    #[test]
    fn lot_cost_is_prorated_among_garments() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        let lot = add_lot(&conn, &s, &LotInput {
            name: "Fardo invierno".into(),
            supplier_id: None,
            purchase_date: d(2026, 5, 1),
            total_cost: 90_000,
            document_type: DocumentType::SinDocumento,
            notes: String::new(),
            register_expense: true,
            payment_method: PaymentMethod::Cash,
        })
        .unwrap();
        let mut g = garment(GarmentType::ChaquetaAbrigo, Season::OtonoInvierno, d(2026, 5, 2));
        g.lot_id = Some(lot);
        g.price = 10_000;
        let a = add_garment(&conn, &to_input(&g)).unwrap();
        g.price = 20_000;
        let b = add_garment(&conn, &to_input(&g)).unwrap();
        assert_eq!(get_garment(&conn, a).unwrap().cost, 30_000);
        assert_eq!(get_garment(&conn, b).unwrap().cost, 60_000);
        let lots = list_lots(&conn).unwrap();
        assert_eq!(lots[0].garment_count, 2);
        assert!(lots[0].movement_id.is_some());
    }

    #[test]
    fn selling_creates_income_and_removes_from_stock() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        let g = garment(GarmentType::BasicoPolera, Season::TodoElAno, d(2026, 1, 1));
        let id = add_garment(&conn, &to_input(&g)).unwrap();
        assert_eq!(get_garment(&conn, id).unwrap().sku, "VM-00001");
        let mid = sell(&conn, &s, &SaleInput {
            garment_id: id,
            price: 11_900,
            date: d(2026, 1, 20),
            payment_method: PaymentMethod::Debit,
            document_type: DocumentType::Boleta,
            category: Category::VentaTienda,
        })
        .unwrap();
        let m = crate::finance::get_movement(&conn, mid).unwrap();
        assert_eq!((m.net, m.iva), (10_000, 1_900));
        let inv = list_inventory(&conn, &s, &InventoryFilter::default(), d(2026, 1, 21)).unwrap();
        assert!(inv.is_empty());
        assert!(sell(&conn, &s, &SaleInput { garment_id: id, price: 1, date: d(2026, 1, 21), payment_method: PaymentMethod::Cash, document_type: DocumentType::Boleta, category: Category::VentaTienda }).is_err());
        // Borrar la venta devuelve la prenda a stock
        crate::finance::delete_movement(&conn, mid).unwrap();
        assert_eq!(get_garment(&conn, id).unwrap().status, GarmentStatus::EnStock);
    }

    #[test]
    fn inventory_sorted_by_flr() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        for (t, day) in [(GarmentType::Coleccion, 1), (GarmentType::BasicoPolera, 10), (GarmentType::JeansPantalon, 5)] {
            add_garment(&conn, &to_input(&garment(t, Season::TodoElAno, d(2026, 1, day)))).unwrap();
        }
        let inv = list_inventory(&conn, &s, &InventoryFilter::default(), d(2026, 2, 1)).unwrap();
        let flrs: Vec<_> = inv.iter().map(|v| v.flr).collect();
        let mut sorted = flrs.clone();
        sorted.sort();
        assert_eq!(flrs, sorted);
        assert_eq!(inv[0].garment.garment_type, GarmentType::BasicoPolera);
    }

    #[test]
    fn qr_and_sku_lookup() {
        let q = qr_matrix("VINTAGE:VM-00001").unwrap();
        assert_eq!(q.modules.len() as u32, q.size * q.size);
        let conn = crate::db::open(":memory:").unwrap();
        let g = garment(GarmentType::BasicoPolera, Season::TodoElAno, d(2026, 1, 1));
        add_garment(&conn, &to_input(&g)).unwrap();
        assert!(find_by_sku(&conn, "VINTAGE:VM-00001").unwrap().is_some());
        assert!(find_by_sku(&conn, "vm-00001").unwrap().is_some());
    }
}
