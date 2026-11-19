//! Respaldo y restauración local en JSON.
//!
//! El formato es genérico: `{ "format": "vintage-manager-backup", "schema_version": N,
//! "exported_on": "YYYY-MM-DD", "tables": { "<tabla>": [ {columna: valor, ...}, ... ] } }`.
//! Solo se aceptan tablas y columnas que existen en el esquema actual (las
//! demás se ignoran), así un respaldo nunca puede inyectar SQL.

use std::collections::HashSet;

use chrono::NaiveDate;
use rusqlite::types::{Value, ValueRef};
use rusqlite::{params_from_iter, Connection};
use serde_json::{Map, Value as Json};

use crate::error::{CoreError, CoreResult};

pub const FORMAT: &str = "vintage-manager-backup";
pub const SCHEMA_VERSION: i64 = 1;

/// Tablas en orden de dependencia (padres primero).
pub const TABLES: &[&str] = &[
    "settings",
    "suppliers",
    "lots",
    "garments",
    "price_changes",
    "movements",
    "receivables",
    "receivable_payments",
    "payables",
    "payable_payments",
    "f29_filings",
    "alert_state",
    "notification_log",
];

fn columns(conn: &Connection, table: &str) -> CoreResult<Vec<String>> {
    let mut stmt = conn.prepare(&format!("PRAGMA table_info({table})"))?;
    let rows = stmt.query_map([], |r| r.get::<_, String>(1))?;
    Ok(rows.collect::<Result<_, _>>()?)
}

fn to_json(v: ValueRef<'_>) -> Json {
    match v {
        ValueRef::Null => Json::Null,
        ValueRef::Integer(i) => Json::from(i),
        ValueRef::Real(f) => Json::from(f),
        ValueRef::Text(t) => Json::from(String::from_utf8_lossy(t).into_owned()),
        ValueRef::Blob(b) => Json::from(b.iter().map(|x| format!("{x:02x}")).collect::<String>()),
    }
}

fn from_json(v: &Json) -> Value {
    match v {
        Json::Null => Value::Null,
        Json::Bool(b) => Value::Integer(*b as i64),
        Json::Number(n) => n
            .as_i64()
            .map(Value::Integer)
            .unwrap_or_else(|| Value::Real(n.as_f64().unwrap_or(0.0))),
        Json::String(s) => Value::Text(s.clone()),
        other => Value::Text(other.to_string()),
    }
}

pub fn export_json(conn: &Connection, today: NaiveDate) -> CoreResult<String> {
    let mut tables = Map::new();
    for t in TABLES {
        let cols = columns(conn, t)?;
        let mut stmt = conn.prepare(&format!("SELECT * FROM {t}"))?;
        let mut rows = stmt.query([])?;
        let mut list = Vec::new();
        while let Some(row) = rows.next()? {
            let mut obj = Map::new();
            for (i, c) in cols.iter().enumerate() {
                obj.insert(c.clone(), to_json(row.get_ref(i)?));
            }
            list.push(Json::Object(obj));
        }
        tables.insert(t.to_string(), Json::Array(list));
    }
    let mut root = Map::new();
    root.insert("format".into(), Json::from(FORMAT));
    root.insert("schema_version".into(), Json::from(SCHEMA_VERSION));
    root.insert("exported_on".into(), Json::from(today.format("%Y-%m-%d").to_string()));
    root.insert("tables".into(), Json::Object(tables));
    Ok(serde_json::to_string_pretty(&Json::Object(root))?)
}

/// Reemplaza todos los datos por los del respaldo. Devuelve el número de filas importadas.
pub fn import_json(conn: &Connection, json: &str) -> CoreResult<u64> {
    let root: Json = serde_json::from_str(json)?;
    if root.get("format").and_then(|v| v.as_str()) != Some(FORMAT) {
        return Err(CoreError::Backup { msg: "el archivo no es un respaldo de Vintage Manager".into() });
    }
    let version = root.get("schema_version").and_then(|v| v.as_i64()).unwrap_or(0);
    if version > SCHEMA_VERSION {
        return Err(CoreError::Backup {
            msg: "el respaldo es de una versión más nueva de la app; actualízala primero".into(),
        });
    }
    let tables = root
        .get("tables")
        .and_then(|v| v.as_object())
        .ok_or_else(|| CoreError::Backup { msg: "respaldo sin tablas".into() })?;

    let tx = conn.unchecked_transaction()?;
    tx.pragma_update(None, "defer_foreign_keys", "ON")?;
    for t in TABLES.iter().rev() {
        tx.execute(&format!("DELETE FROM {t}"), [])?;
    }
    let mut count = 0u64;
    for t in TABLES {
        let Some(rows) = tables.get(*t).and_then(|v| v.as_array()) else { continue };
        let valid: HashSet<String> = columns(&tx, t)?.into_iter().collect();
        for row in rows {
            let Some(obj) = row.as_object() else { continue };
            let cols: Vec<&String> = obj.keys().filter(|k| valid.contains(*k)).collect();
            if cols.is_empty() {
                continue;
            }
            let names = cols.iter().map(|c| format!("\"{c}\"")).collect::<Vec<_>>().join(", ");
            let marks = vec!["?"; cols.len()].join(", ");
            let values: Vec<Value> = cols.iter().map(|c| from_json(&obj[*c])).collect();
            tx.execute(&format!("INSERT INTO {t} ({names}) VALUES ({marks})"), params_from_iter(values))?;
            count += 1;
        }
    }
    tx.commit()?;
    Ok(count)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::*;
    use crate::settings::Settings;

    #[test]
    fn roundtrip_preserves_data() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        let today = NaiveDate::from_ymd_opt(2026, 11, 19).unwrap();
        crate::demo::load_demo(&conn, &s, today).unwrap();
        let before = crate::finance::list_movements(&conn, &MovementFilter::default()).unwrap();
        let json = export_json(&conn, today).unwrap();

        let other = crate::db::open(":memory:").unwrap();
        let n = import_json(&other, &json).unwrap();
        assert!(n > 40);
        let after = crate::finance::list_movements(&other, &MovementFilter::default()).unwrap();
        assert_eq!(before, after);
        assert_eq!(
            crate::inventory_fefo::all_garments(&conn).unwrap(),
            crate::inventory_fefo::all_garments(&other).unwrap()
        );
    }

    #[test]
    fn rejects_foreign_files() {
        let conn = crate::db::open(":memory:").unwrap();
        assert!(import_json(&conn, r#"{"format":"otro"}"#).is_err());
        assert!(import_json(&conn, "no es json").is_err());
    }

    #[test]
    fn ignores_unknown_columns() {
        let conn = crate::db::open(":memory:").unwrap();
        let json = r#"{"format":"vintage-manager-backup","schema_version":1,"tables":{
            "suppliers":[{"id":1,"name":"A","x; DROP TABLE garments":"y"}]}}"#;
        assert_eq!(import_json(&conn, json).unwrap(), 1);
        assert_eq!(crate::finance::list_suppliers(&conn).unwrap().len(), 1);
    }
}
