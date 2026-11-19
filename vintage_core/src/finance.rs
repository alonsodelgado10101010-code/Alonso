//! Movimientos de caja, cuentas por cobrar, cuentas por pagar y proveedores.

use chrono::NaiveDate;
use rusqlite::{params, Connection, OptionalExtension, Row};

use crate::db::month_bounds;
use crate::error::{CoreError, CoreResult};
use crate::models::*;
use crate::settings::Settings;
use crate::tax::split_iva;

// ───────────────────────────── Movimientos ─────────────────────────────

const MOVEMENT_COLS: &str = "id, kind, date, gross, net, iva, includes_iva, category, payment_method, \
     document_type, document_number, description, photo_path, garment_id, receivable_id, payable_id, lot_id";

fn row_to_movement(r: &Row) -> rusqlite::Result<Movement> {
    let kind: MovementKind = r.get(1)?;
    let doc: DocumentType = r.get(9)?;
    Ok(Movement {
        id: r.get(0)?,
        kind,
        date: r.get(2)?,
        gross: r.get(3)?,
        net: r.get(4)?,
        iva: r.get(5)?,
        includes_iva: r.get(6)?,
        category: r.get(7)?,
        payment_method: r.get(8)?,
        document_type: doc,
        document_number: r.get(10)?,
        description: r.get(11)?,
        photo_path: r.get(12)?,
        garment_id: r.get(13)?,
        receivable_id: r.get(14)?,
        payable_id: r.get(15)?,
        lot_id: r.get(16)?,
        iva_creditable: kind == MovementKind::Expense && doc.gives_iva_credit(),
    })
}

fn validate_movement(input: &MovementInput) -> CoreResult<()> {
    if input.amount <= 0 {
        return Err(CoreError::validation("el monto debe ser mayor a cero"));
    }
    if input.category.kind() != input.kind {
        return Err(CoreError::validation("la categoría no corresponde al tipo de movimiento"));
    }
    if input.kind == MovementKind::Income && input.document_type == DocumentType::FacturaCompra {
        return Err(CoreError::validation("una venta no puede respaldarse con factura de compra"));
    }
    Ok(())
}

/// Calcula (bruto, neto, iva) según documento y tasa vigente.
pub fn amounts_for(input: &MovementInput, s: &Settings) -> (i64, i64, i64) {
    if input.document_type.has_iva() {
        split_iva(input.amount, input.includes_iva, s.iva_rate)
    } else {
        (input.amount, input.amount, 0)
    }
}

/// Vínculos opcionales con otras entidades.
#[derive(Default, Clone, Copy)]
pub struct MovementLinks {
    pub receivable_id: Option<i64>,
    pub payable_id: Option<i64>,
    pub lot_id: Option<i64>,
}

pub fn add_movement(conn: &Connection, s: &Settings, input: &MovementInput) -> CoreResult<i64> {
    add_movement_linked(conn, s, input, MovementLinks::default())
}

pub fn add_movement_linked(
    conn: &Connection,
    s: &Settings,
    input: &MovementInput,
    links: MovementLinks,
) -> CoreResult<i64> {
    validate_movement(input)?;
    let (gross, net, iva) = amounts_for(input, s);
    conn.execute(
        "INSERT INTO movements (kind, date, gross, net, iva, includes_iva, category, payment_method,
            document_type, document_number, description, photo_path, garment_id, receivable_id, payable_id, lot_id)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16)",
        params![
            input.kind,
            input.date,
            gross,
            net,
            iva,
            input.includes_iva,
            input.category,
            input.payment_method,
            input.document_type,
            input.document_number,
            input.description,
            input.photo_path,
            input.garment_id,
            links.receivable_id,
            links.payable_id,
            links.lot_id,
        ],
    )?;
    Ok(conn.last_insert_rowid())
}

pub fn update_movement(conn: &Connection, s: &Settings, id: i64, input: &MovementInput) -> CoreResult<()> {
    validate_movement(input)?;
    let (gross, net, iva) = amounts_for(input, s);
    let n = conn.execute(
        "UPDATE movements SET kind=?1, date=?2, gross=?3, net=?4, iva=?5, includes_iva=?6, category=?7,
            payment_method=?8, document_type=?9, document_number=?10, description=?11, photo_path=?12, garment_id=?13
         WHERE id=?14",
        params![
            input.kind,
            input.date,
            gross,
            net,
            iva,
            input.includes_iva,
            input.category,
            input.payment_method,
            input.document_type,
            input.document_number,
            input.description,
            input.photo_path,
            input.garment_id,
            id
        ],
    )?;
    if n == 0 {
        return Err(CoreError::not_found("movimiento", id));
    }
    Ok(())
}

pub fn get_movement(conn: &Connection, id: i64) -> CoreResult<Movement> {
    conn.query_row(
        &format!("SELECT {MOVEMENT_COLS} FROM movements WHERE id=?1"),
        params![id],
        row_to_movement,
    )
    .optional()?
    .ok_or_else(|| CoreError::not_found("movimiento", id))
}

/// Elimina un movimiento y deshace sus efectos colaterales:
/// si era la venta de una prenda, la prenda vuelve a stock; si era un abono,
/// se elimina el abono.
pub fn delete_movement(conn: &Connection, id: i64) -> CoreResult<()> {
    let m = get_movement(conn, id)?;
    let tx = conn.unchecked_transaction()?;
    if let (MovementKind::Income, Some(gid)) = (m.kind, m.garment_id) {
        tx.execute(
            "UPDATE garments SET status='en_stock', sold_date=NULL, sold_price=NULL
             WHERE id=?1 AND status='vendida'",
            params![gid],
        )?;
    }
    tx.execute("DELETE FROM receivable_payments WHERE movement_id=?1", params![id])?;
    tx.execute("DELETE FROM payable_payments WHERE movement_id=?1", params![id])?;
    tx.execute("UPDATE lots SET movement_id=NULL WHERE movement_id=?1", params![id])?;
    tx.execute("DELETE FROM movements WHERE id=?1", params![id])?;
    tx.commit()?;
    Ok(())
}

pub fn list_movements(conn: &Connection, f: &MovementFilter) -> CoreResult<Vec<Movement>> {
    let mut sql = format!("SELECT {MOVEMENT_COLS} FROM movements WHERE 1=1");
    let mut args: Vec<Box<dyn rusqlite::ToSql>> = Vec::new();
    match (f.year, f.month) {
        (Some(y), Some(m)) => {
            let (from, to) = month_bounds(y, m)?;
            sql.push_str(" AND date >= ? AND date < ?");
            args.push(Box::new(from));
            args.push(Box::new(to));
        }
        (Some(y), None) => {
            sql.push_str(" AND date >= ? AND date < ?");
            args.push(Box::new(format!("{y:04}-01-01")));
            args.push(Box::new(format!("{:04}-01-01", y + 1)));
        }
        _ => {}
    }
    if let Some(k) = f.kind {
        sql.push_str(" AND kind = ?");
        args.push(Box::new(k));
    }
    if let Some(c) = f.category {
        sql.push_str(" AND category = ?");
        args.push(Box::new(c));
    }
    sql.push_str(" ORDER BY date DESC, id DESC");
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map(rusqlite::params_from_iter(args.iter().map(|b| b.as_ref())), row_to_movement)?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// Caja estimada: caja inicial + ingresos − egresos hasta `today` (inclusive).
pub fn cash_available(conn: &Connection, s: &Settings, today: NaiveDate) -> CoreResult<i64> {
    let net_flow: i64 = conn.query_row(
        "SELECT COALESCE(SUM(CASE WHEN kind='income' THEN gross ELSE -gross END), 0)
         FROM movements WHERE date <= ?1",
        params![today],
        |r| r.get(0),
    )?;
    Ok(s.initial_cash + net_flow)
}

// ───────────────────────── Estado de deudas ─────────────────────────

pub fn debt_status(amount: i64, paid: i64, due: NaiveDate, today: NaiveDate) -> DebtStatus {
    if paid >= amount {
        DebtStatus::Paid
    } else if due < today {
        DebtStatus::Overdue
    } else if paid > 0 {
        DebtStatus::Partial
    } else {
        DebtStatus::Pending
    }
}

fn load_payments(conn: &Connection, table: &str, fk: &str, id: i64) -> CoreResult<Vec<DebtPayment>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT id, date, amount, movement_id FROM {table} WHERE {fk}=?1 ORDER BY date, id"
    ))?;
    let rows = stmt.query_map(params![id], |r| {
        Ok(DebtPayment {
            id: r.get(0)?,
            date: r.get(1)?,
            amount: r.get(2)?,
            movement_id: r.get(3)?,
        })
    })?;
    Ok(rows.collect::<Result<_, _>>()?)
}

// ───────────────────────── Cuentas por cobrar ─────────────────────────

fn validate_receivable(i: &ReceivableInput) -> CoreResult<()> {
    if i.customer.trim().is_empty() {
        return Err(CoreError::validation("indica el nombre del cliente"));
    }
    if i.amount <= 0 {
        return Err(CoreError::validation("el monto debe ser mayor a cero"));
    }
    if i.due_date < i.issue_date {
        return Err(CoreError::validation("el vencimiento no puede ser anterior a la emisión"));
    }
    Ok(())
}

pub fn add_receivable(conn: &Connection, i: &ReceivableInput) -> CoreResult<i64> {
    validate_receivable(i)?;
    conn.execute(
        "INSERT INTO receivables (customer, amount, issue_date, due_date, concept, notes)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![i.customer.trim(), i.amount, i.issue_date, i.due_date, i.concept, i.notes],
    )?;
    Ok(conn.last_insert_rowid())
}

pub fn update_receivable(conn: &Connection, id: i64, i: &ReceivableInput) -> CoreResult<()> {
    validate_receivable(i)?;
    let n = conn.execute(
        "UPDATE receivables SET customer=?1, amount=?2, issue_date=?3, due_date=?4, concept=?5, notes=?6 WHERE id=?7",
        params![i.customer.trim(), i.amount, i.issue_date, i.due_date, i.concept, i.notes, id],
    )?;
    if n == 0 {
        return Err(CoreError::not_found("cobro", id));
    }
    Ok(())
}

pub fn delete_receivable(conn: &Connection, id: i64) -> CoreResult<()> {
    conn.execute("DELETE FROM receivables WHERE id=?1", params![id])?;
    Ok(())
}

pub fn get_receivable(conn: &Connection, id: i64, today: NaiveDate) -> CoreResult<Receivable> {
    let base = conn
        .query_row(
            "SELECT r.id, r.customer, r.amount, r.issue_date, r.due_date, r.concept, r.notes,
                    COALESCE((SELECT SUM(amount) FROM receivable_payments p WHERE p.receivable_id = r.id), 0)
             FROM receivables r WHERE r.id = ?1",
            params![id],
            |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, NaiveDate>(3)?,
                    r.get::<_, NaiveDate>(4)?,
                    r.get::<_, ReceivableConcept>(5)?,
                    r.get::<_, String>(6)?,
                    r.get::<_, i64>(7)?,
                ))
            },
        )
        .optional()?
        .ok_or_else(|| CoreError::not_found("cobro", id))?;
    let (id, customer, amount, issue_date, due_date, concept, notes, paid) = base;
    Ok(Receivable {
        id,
        customer,
        amount,
        issue_date,
        due_date,
        concept,
        notes,
        paid_amount: paid,
        remaining: (amount - paid).max(0),
        status: debt_status(amount, paid, due_date, today),
        days_to_due: (due_date - today).num_days(),
        payments: load_payments(conn, "receivable_payments", "receivable_id", id)?,
    })
}

/// Lista de cobros. Si `only_open` es true excluye los pagados.
pub fn list_receivables(conn: &Connection, today: NaiveDate, only_open: bool) -> CoreResult<Vec<Receivable>> {
    let ids: Vec<i64> = {
        let mut stmt = conn.prepare("SELECT id FROM receivables ORDER BY due_date, id")?;
        let rows = stmt.query_map([], |r| r.get(0))?;
        rows.collect::<Result<_, _>>()?
    };
    let mut out = Vec::with_capacity(ids.len());
    for id in ids {
        let r = get_receivable(conn, id, today)?;
        if !only_open || r.status != DebtStatus::Paid {
            out.push(r);
        }
    }
    Ok(out)
}

/// Registra un abono. Genera automáticamente el ingreso en movimientos.
pub fn pay_receivable(
    conn: &Connection,
    s: &Settings,
    id: i64,
    amount: i64,
    date: NaiveDate,
    method: PaymentMethod,
) -> CoreResult<Receivable> {
    let r = get_receivable(conn, id, date)?;
    if amount <= 0 {
        return Err(CoreError::validation("el abono debe ser mayor a cero"));
    }
    if amount > r.remaining {
        return Err(CoreError::validation(format!(
            "el abono supera el saldo pendiente (${})",
            r.remaining
        )));
    }
    let tx = conn.unchecked_transaction()?;
    let movement = MovementInput {
        kind: MovementKind::Income,
        date,
        amount,
        includes_iva: true,
        category: Category::VentaTienda,
        payment_method: method,
        document_type: DocumentType::Boleta,
        document_number: None,
        description: format!("Cobro a {} ({})", r.customer, concept_label(r.concept)),
        photo_path: None,
        garment_id: None,
    };
    let mid = add_movement_linked(&tx, s, &movement, MovementLinks { receivable_id: Some(id), ..Default::default() })?;
    tx.execute(
        "INSERT INTO receivable_payments (receivable_id, date, amount, movement_id) VALUES (?1, ?2, ?3, ?4)",
        params![id, date, amount, mid],
    )?;
    tx.commit()?;
    get_receivable(conn, id, date)
}

pub fn concept_label(c: ReceivableConcept) -> &'static str {
    match c {
        ReceivableConcept::VentaCredito => "venta a crédito",
        ReceivableConcept::Consignacion => "consignación",
        ReceivableConcept::Apartado => "apartado/reserva",
        ReceivableConcept::Otro => "otro",
    }
}

// ───────────────────────── Cuentas por pagar ─────────────────────────

fn validate_payable(i: &PayableInput) -> CoreResult<()> {
    if i.supplier_name.trim().is_empty() && i.supplier_id.is_none() {
        return Err(CoreError::validation("indica el proveedor"));
    }
    if i.amount <= 0 {
        return Err(CoreError::validation("el monto debe ser mayor a cero"));
    }
    if i.category.kind() != MovementKind::Expense {
        return Err(CoreError::validation("la categoría debe ser de egreso"));
    }
    if i.due_date < i.issue_date {
        return Err(CoreError::validation("el vencimiento no puede ser anterior a la emisión"));
    }
    Ok(())
}

fn resolve_supplier_name(conn: &Connection, i: &PayableInput) -> CoreResult<String> {
    if let Some(sid) = i.supplier_id {
        if i.supplier_name.trim().is_empty() {
            return Ok(get_supplier(conn, sid)?.name);
        }
    }
    Ok(i.supplier_name.trim().to_string())
}

pub fn add_payable(conn: &Connection, i: &PayableInput) -> CoreResult<i64> {
    validate_payable(i)?;
    let name = resolve_supplier_name(conn, i)?;
    conn.execute(
        "INSERT INTO payables (supplier_id, supplier_name, amount, issue_date, due_date, document_type,
            document_number, category, notes) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        params![i.supplier_id, name, i.amount, i.issue_date, i.due_date, i.document_type, i.document_number, i.category, i.notes],
    )?;
    Ok(conn.last_insert_rowid())
}

pub fn update_payable(conn: &Connection, id: i64, i: &PayableInput) -> CoreResult<()> {
    validate_payable(i)?;
    let name = resolve_supplier_name(conn, i)?;
    let n = conn.execute(
        "UPDATE payables SET supplier_id=?1, supplier_name=?2, amount=?3, issue_date=?4, due_date=?5,
            document_type=?6, document_number=?7, category=?8, notes=?9 WHERE id=?10",
        params![i.supplier_id, name, i.amount, i.issue_date, i.due_date, i.document_type, i.document_number, i.category, i.notes, id],
    )?;
    if n == 0 {
        return Err(CoreError::not_found("pago a proveedor", id));
    }
    Ok(())
}

pub fn delete_payable(conn: &Connection, id: i64) -> CoreResult<()> {
    conn.execute("DELETE FROM payables WHERE id=?1", params![id])?;
    Ok(())
}

pub fn get_payable(conn: &Connection, id: i64, today: NaiveDate) -> CoreResult<Payable> {
    let p = conn
        .query_row(
            "SELECT p.id, p.supplier_id, p.supplier_name, p.amount, p.issue_date, p.due_date, p.document_type,
                    p.document_number, p.category, p.notes,
                    COALESCE((SELECT SUM(amount) FROM payable_payments x WHERE x.payable_id = p.id), 0)
             FROM payables p WHERE p.id = ?1",
            params![id],
            |r| {
                let amount: i64 = r.get(3)?;
                let due: NaiveDate = r.get(5)?;
                let paid: i64 = r.get(10)?;
                Ok(Payable {
                    id: r.get(0)?,
                    supplier_id: r.get(1)?,
                    supplier_name: r.get(2)?,
                    amount,
                    issue_date: r.get(4)?,
                    due_date: due,
                    document_type: r.get(6)?,
                    document_number: r.get(7)?,
                    category: r.get(8)?,
                    notes: r.get(9)?,
                    paid_amount: paid,
                    remaining: (amount - paid).max(0),
                    status: debt_status(amount, paid, due, today),
                    days_to_due: (due - today).num_days(),
                    payments: vec![],
                })
            },
        )
        .optional()?
        .ok_or_else(|| CoreError::not_found("pago a proveedor", id))?;
    Ok(Payable { payments: load_payments(conn, "payable_payments", "payable_id", id)?, ..p })
}

pub fn list_payables(conn: &Connection, today: NaiveDate, only_open: bool) -> CoreResult<Vec<Payable>> {
    let ids: Vec<i64> = {
        let mut stmt = conn.prepare("SELECT id FROM payables ORDER BY due_date, id")?;
        let rows = stmt.query_map([], |r| r.get(0))?;
        rows.collect::<Result<_, _>>()?
    };
    let mut out = Vec::with_capacity(ids.len());
    for id in ids {
        let p = get_payable(conn, id, today)?;
        if !only_open || p.status != DebtStatus::Paid {
            out.push(p);
        }
    }
    Ok(out)
}

/// Registra un abono a proveedor. Genera automáticamente el egreso.
pub fn pay_payable(
    conn: &Connection,
    s: &Settings,
    id: i64,
    amount: i64,
    date: NaiveDate,
    method: PaymentMethod,
) -> CoreResult<Payable> {
    let p = get_payable(conn, id, date)?;
    if amount <= 0 {
        return Err(CoreError::validation("el abono debe ser mayor a cero"));
    }
    if amount > p.remaining {
        return Err(CoreError::validation(format!(
            "el abono supera el saldo pendiente (${})",
            p.remaining
        )));
    }
    let tx = conn.unchecked_transaction()?;
    let movement = MovementInput {
        kind: MovementKind::Expense,
        date,
        amount,
        includes_iva: true,
        category: p.category,
        payment_method: method,
        document_type: p.document_type,
        document_number: p.document_number.clone(),
        description: format!("Pago a {}", p.supplier_name),
        photo_path: None,
        garment_id: None,
    };
    let mid = add_movement_linked(&tx, s, &movement, MovementLinks { payable_id: Some(id), ..Default::default() })?;
    tx.execute(
        "INSERT INTO payable_payments (payable_id, date, amount, movement_id) VALUES (?1, ?2, ?3, ?4)",
        params![id, date, amount, mid],
    )?;
    tx.commit()?;
    get_payable(conn, id, date)
}

// ───────────────────────────── Proveedores ─────────────────────────────

fn validate_supplier(i: &SupplierInput) -> CoreResult<()> {
    if i.name.trim().is_empty() {
        return Err(CoreError::validation("el proveedor necesita un nombre"));
    }
    if !i.rut.trim().is_empty() && !is_valid_rut(&i.rut) {
        return Err(CoreError::validation("el RUT no es válido"));
    }
    Ok(())
}

pub fn add_supplier(conn: &Connection, i: &SupplierInput) -> CoreResult<i64> {
    validate_supplier(i)?;
    conn.execute(
        "INSERT INTO suppliers (name, rut, contact, phone, email) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![i.name.trim(), i.rut.trim(), i.contact, i.phone, i.email],
    )?;
    Ok(conn.last_insert_rowid())
}

pub fn update_supplier(conn: &Connection, id: i64, i: &SupplierInput) -> CoreResult<()> {
    validate_supplier(i)?;
    let n = conn.execute(
        "UPDATE suppliers SET name=?1, rut=?2, contact=?3, phone=?4, email=?5 WHERE id=?6",
        params![i.name.trim(), i.rut.trim(), i.contact, i.phone, i.email, id],
    )?;
    if n == 0 {
        return Err(CoreError::not_found("proveedor", id));
    }
    Ok(())
}

pub fn delete_supplier(conn: &Connection, id: i64) -> CoreResult<()> {
    conn.execute("DELETE FROM suppliers WHERE id=?1", params![id])?;
    Ok(())
}

pub fn get_supplier(conn: &Connection, id: i64) -> CoreResult<Supplier> {
    conn.query_row(
        "SELECT id, name, rut, contact, phone, email FROM suppliers WHERE id=?1",
        params![id],
        row_to_supplier,
    )
    .optional()?
    .ok_or_else(|| CoreError::not_found("proveedor", id))
}

fn row_to_supplier(r: &Row) -> rusqlite::Result<Supplier> {
    Ok(Supplier {
        id: r.get(0)?,
        name: r.get(1)?,
        rut: r.get(2)?,
        contact: r.get(3)?,
        phone: r.get(4)?,
        email: r.get(5)?,
    })
}

pub fn list_suppliers(conn: &Connection) -> CoreResult<Vec<Supplier>> {
    let mut stmt = conn.prepare("SELECT id, name, rut, contact, phone, email FROM suppliers ORDER BY name COLLATE NOCASE")?;
    let rows = stmt.query_map([], row_to_supplier)?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// Valida un RUT chileno (módulo 11). Acepta "12.345.678-5" o "123456785".
pub fn is_valid_rut(rut: &str) -> bool {
    let clean: String = rut
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .collect::<String>()
        .to_uppercase();
    if clean.len() < 2 {
        return false;
    }
    let (body, dv) = clean.split_at(clean.len() - 1);
    if !body.chars().all(|c| c.is_ascii_digit()) {
        return false;
    }
    let mut sum = 0u32;
    let mut factor = 2u32;
    for c in body.chars().rev() {
        sum += c.to_digit(10).unwrap() * factor;
        factor = if factor == 7 { 2 } else { factor + 1 };
    }
    let expected = match 11 - (sum % 11) {
        11 => '0',
        10 => 'K',
        n => char::from_digit(n, 10).unwrap(),
    };
    dv.starts_with(expected)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(y: i32, m: u32, day: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, day).unwrap()
    }

    fn sale(amount: i64, doc: DocumentType) -> MovementInput {
        MovementInput {
            kind: MovementKind::Income,
            date: d(2026, 11, 5),
            amount,
            includes_iva: true,
            category: Category::VentaTienda,
            payment_method: PaymentMethod::Cash,
            document_type: doc,
            document_number: None,
            description: "venta".into(),
            photo_path: None,
            garment_id: None,
        }
    }

    #[test]
    fn movement_without_document_has_no_iva() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        let id = add_movement(&conn, &s, &sale(10_000, DocumentType::SinDocumento)).unwrap();
        let m = get_movement(&conn, id).unwrap();
        assert_eq!((m.gross, m.net, m.iva), (10_000, 10_000, 0));
    }

    #[test]
    fn rejects_mismatched_category() {
        let conn = crate::db::open(":memory:").unwrap();
        let mut i = sale(1000, DocumentType::Boleta);
        i.category = Category::Arriendo;
        assert!(matches!(add_movement(&conn, &Settings::default(), &i), Err(CoreError::Validation { .. })));
    }

    #[test]
    fn receivable_partial_then_paid_generates_income() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        let id = add_receivable(
            &conn,
            &ReceivableInput {
                customer: "Ana".into(),
                amount: 30_000,
                issue_date: d(2026, 11, 1),
                due_date: d(2026, 11, 10),
                concept: ReceivableConcept::Apartado,
                notes: String::new(),
            },
        )
        .unwrap();
        assert_eq!(get_receivable(&conn, id, d(2026, 11, 11)).unwrap().status, DebtStatus::Overdue);
        let r = pay_receivable(&conn, &s, id, 10_000, d(2026, 11, 5), PaymentMethod::Cash).unwrap();
        assert_eq!(r.status, DebtStatus::Partial);
        assert_eq!(r.remaining, 20_000);
        assert!(pay_receivable(&conn, &s, id, 50_000, d(2026, 11, 6), PaymentMethod::Cash).is_err());
        let r = pay_receivable(&conn, &s, id, 20_000, d(2026, 11, 6), PaymentMethod::Cash).unwrap();
        assert_eq!(r.status, DebtStatus::Paid);
        let movs = list_movements(&conn, &MovementFilter::default()).unwrap();
        assert_eq!(movs.len(), 2);
        assert!(movs.iter().all(|m| m.kind == MovementKind::Income && m.receivable_id == Some(id)));
        // eliminar el movimiento elimina el abono
        delete_movement(&conn, movs[0].id).unwrap();
        assert_eq!(get_receivable(&conn, id, d(2026, 11, 6)).unwrap().status, DebtStatus::Partial);
    }

    #[test]
    fn payable_payment_generates_expense_with_credit() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        let sid = add_supplier(&conn, &SupplierInput {
            name: "Fardos del Sur".into(),
            rut: "76.086.428-5".into(),
            contact: String::new(),
            phone: String::new(),
            email: String::new(),
        })
        .unwrap();
        let id = add_payable(&conn, &PayableInput {
            supplier_id: Some(sid),
            supplier_name: String::new(),
            amount: 119_000,
            issue_date: d(2026, 11, 1),
            due_date: d(2026, 11, 30),
            document_type: DocumentType::FacturaCompra,
            document_number: Some("123".into()),
            category: Category::CompraMercaderia,
            notes: String::new(),
        })
        .unwrap();
        let p = pay_payable(&conn, &s, id, 119_000, d(2026, 11, 15), PaymentMethod::Transfer).unwrap();
        assert_eq!(p.supplier_name, "Fardos del Sur");
        assert_eq!(p.status, DebtStatus::Paid);
        let m = &list_movements(&conn, &MovementFilter::default()).unwrap()[0];
        assert_eq!((m.net, m.iva), (100_000, 19_000));
        assert!(m.iva_creditable);
    }

    #[test]
    fn rut_validation() {
        assert!(is_valid_rut("76.086.428-5"));
        assert!(is_valid_rut("11.111.111-1"));
        assert!(!is_valid_rut("11.111.111-2"));
        assert!(!is_valid_rut("abc"));
    }

    #[test]
    fn filter_by_month_and_kind() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        add_movement(&conn, &s, &sale(1000, DocumentType::Boleta)).unwrap();
        let mut other = sale(2000, DocumentType::Boleta);
        other.date = d(2026, 10, 1);
        add_movement(&conn, &s, &other).unwrap();
        let f = MovementFilter { year: Some(2026), month: Some(11), kind: Some(MovementKind::Income), category: None };
        assert_eq!(list_movements(&conn, &f).unwrap().len(), 1);
        assert_eq!(cash_available(&conn, &s, d(2026, 11, 30)).unwrap(), 3000);
    }
}
