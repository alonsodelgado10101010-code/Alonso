//! Reportes: Resumen (dashboard), gráfico de 6 meses, métricas de inventario
//! y exportaciones CSV para el contador.

use std::collections::HashMap;

use chrono::NaiveDate;
use rusqlite::Connection;
use rust_decimal::Decimal;

use crate::alerts::{self, month_name};
use crate::db::{month_bounds, prev_month};
use crate::error::CoreResult;
use crate::finance;
use crate::inventory_fefo;
use crate::models::*;
use crate::settings::Settings;
use crate::tax::{self, DISCLAIMER};

/// Formato CLP: `$ 1.250.000` (sin decimales, punto como separador de miles).
pub fn format_clp(amount: i64) -> String {
    let digits = amount.unsigned_abs().to_string();
    let mut out = String::with_capacity(digits.len() + 4);
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i) % 3 == 0 {
            out.push('.');
        }
        out.push(c);
    }
    if amount < 0 {
        format!("-$ {out}")
    } else {
        format!("$ {out}")
    }
}

pub fn month_short(m: u32) -> String {
    let name = month_name(m);
    let mut s: String = name.chars().take(3).collect();
    if let Some(f) = s.get_mut(0..1) {
        f.make_ascii_uppercase();
    }
    s
}

pub fn month_flows(conn: &Connection, year: i32, month: u32, count: u32) -> CoreResult<Vec<MonthFlow>> {
    let mut months = Vec::new();
    let (mut y, mut m) = (year, month);
    for _ in 0..count {
        months.push((y, m));
        (y, m) = prev_month(y, m);
    }
    months.reverse();
    let mut out = Vec::with_capacity(months.len());
    for (y, m) in months {
        let (from, to) = month_bounds(y, m)?;
        let (income, expense): (i64, i64) = conn.query_row(
            "SELECT COALESCE(SUM(CASE WHEN kind='income' THEN gross END),0),
                    COALESCE(SUM(CASE WHEN kind='expense' THEN gross END),0)
             FROM movements WHERE date >= ?1 AND date < ?2",
            rusqlite::params![from, to],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        out.push(MonthFlow { year: y, month: m, label: month_short(m), income, expense });
    }
    Ok(out)
}

pub fn inventory_summary(conn: &Connection, s: &Settings, today: NaiveDate) -> CoreResult<InventorySummary> {
    let views = inventory_fefo::list_inventory(conn, s, &InventoryFilter::default(), today)?;
    let mut sum = InventorySummary {
        in_stock: views.len() as u32,
        value_cost: 0,
        value_price: 0,
        fresca: 0,
        rotar_pronto: 0,
        rotar_ya: 0,
        liquidar: 0,
    };
    for v in &views {
        sum.value_cost += v.garment.cost;
        sum.value_price += v.garment.price;
        match v.semaphore {
            Semaphore::Fresca => sum.fresca += 1,
            Semaphore::RotarPronto => sum.rotar_pronto += 1,
            Semaphore::RotarYa => sum.rotar_ya += 1,
            Semaphore::Liquidar => sum.liquidar += 1,
        }
    }
    Ok(sum)
}

pub fn debt_summary(conn: &Connection, today: NaiveDate) -> CoreResult<DebtSummary> {
    let rec = finance::list_receivables(conn, today, true)?;
    let pay = finance::list_payables(conn, today, true)?;
    Ok(DebtSummary {
        receivables_total: rec.iter().map(|r| r.remaining).sum(),
        receivables_overdue: rec.iter().filter(|r| r.status == DebtStatus::Overdue).map(|r| r.remaining).sum(),
        receivables_count: rec.len() as u32,
        payables_total: pay.iter().map(|p| p.remaining).sum(),
        payables_overdue: pay.iter().filter(|p| p.status == DebtStatus::Overdue).map(|p| p.remaining).sum(),
        payables_count: pay.len() as u32,
    })
}

pub fn dashboard(conn: &Connection, s: &Settings, year: i32, month: u32, today: NaiveDate) -> CoreResult<Dashboard> {
    let totals = tax::month_totals(conn, year, month)?;
    let (from, to) = month_bounds(year, month)?;
    let expenses_gross: i64 = conn.query_row(
        "SELECT COALESCE(SUM(gross),0) FROM movements WHERE kind='expense' AND date >= ?1 AND date < ?2",
        rusqlite::params![from, to],
        |r| r.get(0),
    )?;
    let mut attention = alerts::collect_alerts(conn, s, today)?;
    attention.truncate(5);
    Ok(Dashboard {
        year,
        month,
        sales_gross: totals.sales_gross,
        expenses_gross,
        profit_estimate: totals.income_net - totals.costs_net,
        cash_available: finance::cash_available(conn, s, today)?,
        tax: tax::monthly_tax(conn, s, year, month, today)?,
        debts: debt_summary(conn, today)?,
        inventory: inventory_summary(conn, s, today)?,
        attention,
        chart: month_flows(conn, year, month, 6)?,
        disclaimer: DISCLAIMER.to_string(),
    })
}

pub fn inventory_metrics(conn: &Connection, s: &Settings, today: NaiveDate) -> CoreResult<InventoryMetrics> {
    let all = inventory_fefo::all_garments(conn)?;
    let sold: Vec<&Garment> = all.iter().filter(|g| g.status == GarmentStatus::Vendida).collect();

    let mut by_type = Vec::new();
    for t in GarmentType::all() {
        let items: Vec<i64> = sold
            .iter()
            .filter(|g| g.garment_type == t)
            .filter_map(|g| g.sold_date.map(|d| (d - g.intake_date).num_days().max(0)))
            .collect();
        let avg = if items.is_empty() { 0 } else { items.iter().sum::<i64>() / items.len() as i64 };
        by_type.push(TypeMetric { garment_type: t, sold: items.len() as u32, avg_days_to_sell: avg as u32 });
    }

    let mut by_era = Vec::new();
    for e in Era::all() {
        let in_stock = all.iter().filter(|g| g.era == e && inventory_fefo::is_active(g.status)).count() as u32;
        let sold_n = sold.iter().filter(|g| g.era == e).count() as u32;
        let total = in_stock + sold_n;
        let rotation_pct = if total == 0 {
            Decimal::ZERO
        } else {
            (Decimal::from(sold_n) / Decimal::from(total)).round_dp(4)
        };
        by_era.push(EraMetric { era: e, in_stock, sold: sold_n, rotation_pct });
    }

    let mut oldest = inventory_fefo::list_inventory(conn, s, &InventoryFilter::default(), today)?;
    oldest.sort_by_key(|v| v.garment.intake_date);
    oldest.truncate(5);

    let margins: Vec<(i64, i64)> = sold
        .iter()
        .filter_map(|g| {
            g.sold_price.map(|p| {
                let net = tax::split_iva(p, true, s.iva_rate).1;
                (net - g.cost, net)
            })
        })
        .collect();
    let (avg_margin, avg_margin_pct) = if margins.is_empty() {
        (0, Decimal::ZERO)
    } else {
        let m: i64 = margins.iter().map(|x| x.0).sum();
        let n: i64 = margins.iter().map(|x| x.1).sum();
        (
            m / margins.len() as i64,
            if n > 0 { (Decimal::from(m) / Decimal::from(n)).round_dp(4) } else { Decimal::ZERO },
        )
    };
    Ok(InventoryMetrics { by_type, by_era, oldest, avg_margin, avg_margin_pct })
}

fn csv_escape(s: &str) -> String {
    if s.contains([';', '"', '\n']) {
        format!("\"{}\"", s.replace('"', "\"\""))
    } else {
        s.to_string()
    }
}

/// CSV (separador `;`, compatible con Excel en configuración regional Chile) del
/// desglose tributario anual + resumen de renta.
pub fn tax_csv(conn: &Connection, s: &Settings, year: i32, today: NaiveDate) -> CoreResult<String> {
    let months = tax::year_breakdown(conn, s, year, today)?;
    let annual = tax::annual_tax(conn, s, year, today)?;
    let mut out = String::from("\u{feff}");
    out.push_str(&format!("{};RUT {};Año comercial {}\n", csv_escape(&s.store_name), csv_escape(&s.store_rut), year));
    out.push_str("Mes;Ventas brutas;IVA débito;IVA crédito;Remanente anterior;IVA a pagar;Remanente siguiente;Base PPM;Tasa PPM;PPM;Total F29;Vencimiento;Declarado\n");
    for m in &months {
        out.push_str(&format!(
            "{};{};{};{};{};{};{};{};{}%;{};{};{};{}\n",
            month_name(m.month),
            m.sales_gross,
            m.iva_debit,
            m.iva_credit,
            m.remanente_prev,
            m.iva_to_pay,
            m.remanente_next,
            m.ppm_base,
            (m.ppm_rate * Decimal::from(100)).normalize().to_string().replace('.', ","),
            m.ppm,
            m.total_f29,
            m.due_date.format("%d-%m-%Y"),
            if m.filed { "Sí" } else { "No" }
        ));
    }
    out.push('\n');
    out.push_str("Impuesto a la Renta estimado\n");
    for (k, v) in [
        ("Ingresos netos", annual.income_net),
        ("Costos y gastos netos", annual.costs_net),
        ("Base imponible", annual.base),
        ("Impuesto Primera Categoría", annual.tax),
        ("PPM pagados", annual.ppm_paid),
        ("Saldo estimado", annual.balance),
        ("Proyección base a diciembre", annual.projected_base),
        ("Proyección impuesto", annual.projected_tax),
    ] {
        out.push_str(&format!("{k};{v}\n"));
    }
    out.push_str(&format!("{}\n", csv_escape(DISCLAIMER)));
    Ok(out)
}

pub fn movements_csv(conn: &Connection, filter: &MovementFilter) -> CoreResult<String> {
    let movs = finance::list_movements(conn, filter)?;
    let mut out = String::from("\u{feff}Fecha;Tipo;Categoría;Documento;N° documento;Medio de pago;Bruto;Neto;IVA;Crédito fiscal;Descripción\n");
    for m in movs {
        out.push_str(&format!(
            "{};{};{};{};{};{};{};{};{};{};{}\n",
            m.date.format("%d-%m-%Y"),
            if m.kind == MovementKind::Income { "Ingreso" } else { "Egreso" },
            m.category.as_str(),
            m.document_type.as_str(),
            csv_escape(m.document_number.as_deref().unwrap_or("")),
            m.payment_method.as_str(),
            m.gross,
            m.net,
            m.iva,
            if m.iva_creditable { "Sí" } else { "No" },
            csv_escape(&m.description)
        ));
    }
    Ok(out)
}

/// Contadores por categoría de un mes (para gráficos de torta en la UI).
pub fn category_totals(conn: &Connection, year: i32, month: u32, kind: MovementKind) -> CoreResult<Vec<(Category, i64)>> {
    let movs = finance::list_movements(
        conn,
        &MovementFilter { year: Some(year), month: Some(month), kind: Some(kind), category: None },
    )?;
    let mut map: HashMap<Category, i64> = HashMap::new();
    for m in movs {
        *map.entry(m.category).or_default() += m.gross;
    }
    let mut v: Vec<_> = map.into_iter().collect();
    v.sort_by(|a, b| b.1.cmp(&a.1));
    Ok(v)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clp_format() {
        assert_eq!(format_clp(0), "$ 0");
        assert_eq!(format_clp(999), "$ 999");
        assert_eq!(format_clp(1_000), "$ 1.000");
        assert_eq!(format_clp(1_250_000), "$ 1.250.000");
        assert_eq!(format_clp(-45_300), "-$ 45.300");
    }

    #[test]
    fn month_short_labels() {
        assert_eq!(month_short(1), "Ene");
        assert_eq!(month_short(9), "Sep");
    }

    #[test]
    fn flows_cover_six_months_across_year_boundary() {
        let conn = crate::db::open(":memory:").unwrap();
        let f = month_flows(&conn, 2026, 2, 6).unwrap();
        assert_eq!(f.len(), 6);
        assert_eq!((f[0].year, f[0].month), (2025, 9));
        assert_eq!((f[5].year, f[5].month), (2026, 2));
    }
}
