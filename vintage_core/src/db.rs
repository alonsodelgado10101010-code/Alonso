//! Esquema SQLite y migraciones versionadas (`rusqlite_migration`).
//!
//! Cada migración se agrega al final de `MIGRATIONS`; nunca se modifica una
//! migración ya publicada. `user_version` de SQLite lleva la versión actual.

use chrono::{Datelike, NaiveDate};
use rusqlite::Connection;
use rusqlite_migration::{Migrations, M};

use crate::error::{CoreError, CoreResult};

const V1_SCHEMA: &str = r#"
CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE suppliers (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    name    TEXT NOT NULL,
    rut     TEXT NOT NULL DEFAULT '',
    contact TEXT NOT NULL DEFAULT '',
    phone   TEXT NOT NULL DEFAULT '',
    email   TEXT NOT NULL DEFAULT ''
);

CREATE TABLE lots (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL,
    supplier_id   INTEGER REFERENCES suppliers(id) ON DELETE SET NULL,
    purchase_date TEXT NOT NULL,
    total_cost    INTEGER NOT NULL CHECK (total_cost >= 0),
    document_type TEXT NOT NULL,
    notes         TEXT NOT NULL DEFAULT '',
    movement_id   INTEGER
);

CREATE TABLE garments (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    sku                TEXT NOT NULL UNIQUE,
    garment_type       TEXT NOT NULL,
    brand              TEXT NOT NULL DEFAULT '',
    era                TEXT NOT NULL,
    size               TEXT NOT NULL DEFAULT '',
    color              TEXT NOT NULL DEFAULT '',
    material           TEXT NOT NULL DEFAULT '',
    condition          TEXT NOT NULL,
    season             TEXT NOT NULL,
    cost               INTEGER NOT NULL DEFAULT 0 CHECK (cost >= 0),
    price              INTEGER NOT NULL DEFAULT 0 CHECK (price >= 0),
    supplier_id        INTEGER REFERENCES suppliers(id) ON DELETE SET NULL,
    lot_id             INTEGER REFERENCES lots(id) ON DELETE SET NULL,
    intake_date        TEXT NOT NULL,
    location           TEXT NOT NULL,
    status             TEXT NOT NULL,
    photo_paths        TEXT NOT NULL DEFAULT '[]',
    description        TEXT NOT NULL DEFAULT '',
    paused_days        INTEGER NOT NULL DEFAULT 0,
    paused_since       TEXT,
    sold_date          TEXT,
    sold_price         INTEGER,
    last_discount_date TEXT
);
CREATE INDEX idx_garments_status ON garments(status);
CREATE INDEX idx_garments_lot ON garments(lot_id);

CREATE TABLE price_changes (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    garment_id INTEGER NOT NULL REFERENCES garments(id) ON DELETE CASCADE,
    date       TEXT NOT NULL,
    old_price  INTEGER NOT NULL,
    new_price  INTEGER NOT NULL,
    reason     TEXT NOT NULL DEFAULT ''
);

CREATE TABLE movements (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    kind            TEXT NOT NULL,
    date            TEXT NOT NULL,
    gross           INTEGER NOT NULL,
    net             INTEGER NOT NULL,
    iva             INTEGER NOT NULL,
    includes_iva    INTEGER NOT NULL,
    category        TEXT NOT NULL,
    payment_method  TEXT NOT NULL,
    document_type   TEXT NOT NULL,
    document_number TEXT,
    description     TEXT NOT NULL DEFAULT '',
    photo_path      TEXT,
    garment_id      INTEGER,
    receivable_id   INTEGER,
    payable_id      INTEGER,
    lot_id          INTEGER
);
CREATE INDEX idx_movements_date ON movements(date);

CREATE TABLE receivables (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    customer   TEXT NOT NULL,
    amount     INTEGER NOT NULL CHECK (amount > 0),
    issue_date TEXT NOT NULL,
    due_date   TEXT NOT NULL,
    concept    TEXT NOT NULL,
    notes      TEXT NOT NULL DEFAULT ''
);

CREATE TABLE receivable_payments (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    receivable_id INTEGER NOT NULL REFERENCES receivables(id) ON DELETE CASCADE,
    date          TEXT NOT NULL,
    amount        INTEGER NOT NULL CHECK (amount > 0),
    movement_id   INTEGER
);

CREATE TABLE payables (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    supplier_id     INTEGER REFERENCES suppliers(id) ON DELETE SET NULL,
    supplier_name   TEXT NOT NULL,
    amount          INTEGER NOT NULL CHECK (amount > 0),
    issue_date      TEXT NOT NULL,
    due_date        TEXT NOT NULL,
    document_type   TEXT NOT NULL,
    document_number TEXT,
    category        TEXT NOT NULL,
    notes           TEXT NOT NULL DEFAULT ''
);

CREATE TABLE payable_payments (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    payable_id  INTEGER NOT NULL REFERENCES payables(id) ON DELETE CASCADE,
    date        TEXT NOT NULL,
    amount      INTEGER NOT NULL CHECK (amount > 0),
    movement_id INTEGER
);

CREATE TABLE f29_filings (
    year       INTEGER NOT NULL,
    month      INTEGER NOT NULL,
    filed_date TEXT NOT NULL,
    PRIMARY KEY (year, month)
);

-- Estado de deduplicación y posposición por alerta.
CREATE TABLE alert_state (
    key            TEXT PRIMARY KEY,
    last_priority  TEXT,
    last_sent_abs  INTEGER,
    snoozed_until  TEXT
);

-- Cada notificación push publicada (para el tope diario y para retirarlas al resolverse).
CREATE TABLE notification_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    notification_id INTEGER NOT NULL,
    alert_keys      TEXT NOT NULL,
    priority        TEXT NOT NULL,
    sent_date       TEXT NOT NULL,
    sent_minute     INTEGER NOT NULL
);
CREATE INDEX idx_notification_log_date ON notification_log(sent_date);
"#;

pub fn migrations() -> Migrations<'static> {
    Migrations::new(vec![M::up(V1_SCHEMA)])
}

/// Abre (o crea) la base de datos y la lleva a la última versión del esquema.
pub fn open(path: &str) -> CoreResult<Connection> {
    let mut conn = if path == ":memory:" {
        Connection::open_in_memory()?
    } else {
        Connection::open(path)?
    };
    configure(&mut conn)?;
    Ok(conn)
}

fn configure(conn: &mut Connection) -> CoreResult<()> {
    conn.pragma_update(None, "foreign_keys", "ON")?;
    // WAL no aplica a bases en memoria; se ignora el resultado.
    let _ = conn.pragma_update(None, "journal_mode", "WAL");
    migrations().to_latest(conn)?;
    Ok(())
}

// ─────────────── utilidades de fechas compartidas ───────────────

/// Primer día del mes.
pub fn month_start(year: i32, month: u32) -> CoreResult<NaiveDate> {
    NaiveDate::from_ymd_opt(year, month, 1)
        .ok_or_else(|| CoreError::validation(format!("mes inválido {year}-{month}")))
}

/// (año, mes) siguiente.
pub fn next_month(year: i32, month: u32) -> (i32, u32) {
    if month == 12 {
        (year + 1, 1)
    } else {
        (year, month + 1)
    }
}

/// (año, mes) anterior.
pub fn prev_month(year: i32, month: u32) -> (i32, u32) {
    if month == 1 {
        (year - 1, 12)
    } else {
        (year, month - 1)
    }
}

/// Rango semiabierto [inicio, fin) del mes como texto ISO para consultas SQL.
pub fn month_bounds(year: i32, month: u32) -> CoreResult<(String, String)> {
    let start = month_start(year, month)?;
    let (ny, nm) = next_month(year, month);
    let end = month_start(ny, nm)?;
    Ok((iso(start), iso(end)))
}

pub fn iso(d: NaiveDate) -> String {
    d.format("%Y-%m-%d").to_string()
}

pub fn ym(d: NaiveDate) -> (i32, u32) {
    (d.year(), d.month())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migrations_are_valid() {
        migrations().validate().unwrap();
    }

    #[test]
    fn open_in_memory_creates_schema() {
        let conn = open(":memory:").unwrap();
        let n: i64 = conn
            .query_row(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='garments'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(n, 1);
    }
}
