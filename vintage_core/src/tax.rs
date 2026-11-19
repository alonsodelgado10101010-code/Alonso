//! Motor tributario para PYMEs en Chile (IVA, PPM, F29 e Impuesto a la Renta).
//!
//! **Redondeo:** todos los montos se redondean a pesos enteros con la regla
//! "mitad hacia arriba" (`MidpointAwayFromZero`): $0,5 → $1. Es la regla que
//! usa el SII en los formularios. El IVA de un monto bruto se calcula como
//! `neto = round(bruto / (1 + tasa))`, `iva = bruto − neto`, de modo que
//! neto + IVA siempre cuadra exactamente con el bruto.
//!
//! Simplificaciones documentadas (cálculo referencial):
//! * El remanente de IVA se arrastra en pesos nominales (el SII lo reajusta por UTM).
//! * El vencimiento del F29 se corre al lunes si cae en fin de semana; no se
//!   consideran feriados.
//! * Renta en base de caja (régimen Pro Pyme): ingresos percibidos − gastos
//!   pagados; las compras de mercadería se rebajan al comprarlas.

use chrono::{Datelike, NaiveDate, Weekday};
use rusqlite::{params, Connection};
use rust_decimal::prelude::ToPrimitive;
use rust_decimal::{Decimal, RoundingStrategy};

use crate::db::{month_bounds, month_start, next_month, prev_month};
use crate::error::{CoreError, CoreResult};
use crate::models::{AnnualTax, MonthlyTax, TaxRegime};
use crate::settings::Settings;

pub const DISCLAIMER: &str =
    "Cálculo referencial. Confirma con tu contador antes de declarar en el SII.";

/// Redondea a peso entero, mitad hacia arriba.
pub fn round_clp(d: Decimal) -> i64 {
    d.round_dp_with_strategy(0, RoundingStrategy::MidpointAwayFromZero)
        .to_i64()
        .unwrap_or(0)
}

/// Desglose de un monto en (bruto, neto, iva).
pub fn split_iva(amount: i64, includes_iva: bool, rate: Decimal) -> (i64, i64, i64) {
    if includes_iva {
        let net = round_clp(Decimal::from(amount) / (Decimal::ONE + rate));
        (amount, net, amount - net)
    } else {
        let iva = round_clp(Decimal::from(amount) * rate);
        (amount + iva, amount, iva)
    }
}

/// Resultado de IVA de un período: (resultado, iva a pagar, remanente siguiente).
pub fn iva_period(debit: i64, credit: i64, remanente_prev: i64) -> (i64, i64, i64) {
    let result = debit - credit - remanente_prev;
    if result >= 0 {
        (result, result, 0)
    } else {
        (result, 0, -result)
    }
}

pub fn ppm_amount(base: i64, rate: Decimal) -> i64 {
    round_clp(Decimal::from(base.max(0)) * rate)
}

/// Fecha de vencimiento del F29 del período (año, mes): día `due_day` del mes
/// siguiente, corrido al lunes si cae sábado o domingo.
pub fn f29_due_date(year: i32, month: u32, due_day: u32) -> NaiveDate {
    let (ny, nm) = next_month(year, month);
    let mut d = NaiveDate::from_ymd_opt(ny, nm, due_day.clamp(1, 28)).expect("fecha válida");
    while matches!(d.weekday(), Weekday::Sat | Weekday::Sun) {
        d = d.succ_opt().expect("fecha válida");
    }
    d
}

/// Período (año, mes) cuyo F29 es el próximo en vencer a partir de `today`.
pub fn pending_f29_period(today: NaiveDate, due_day: u32) -> (i32, u32) {
    let (py, pm) = prev_month(today.year(), today.month());
    if today <= f29_due_date(py, pm, due_day) {
        (py, pm)
    } else {
        (today.year(), today.month())
    }
}

// ───────────────────── agregaciones desde SQLite ─────────────────────

#[derive(Debug, Default, Clone, Copy, PartialEq)]
pub struct MonthTotals {
    pub sales_gross: i64,
    pub iva_debit: i64,
    pub iva_credit: i64,
    pub income_net: i64,
    pub costs_net: i64,
}

pub fn month_totals(conn: &Connection, year: i32, month: u32) -> CoreResult<MonthTotals> {
    let (from, to) = month_bounds(year, month)?;
    range_totals(conn, &from, &to)
}

pub fn range_totals(conn: &Connection, from: &str, to: &str) -> CoreResult<MonthTotals> {
    let t = conn.query_row(
        "SELECT
            COALESCE(SUM(CASE WHEN kind='income' THEN gross END), 0),
            COALESCE(SUM(CASE WHEN kind='income' AND document_type IN ('boleta','factura') THEN iva END), 0),
            COALESCE(SUM(CASE WHEN kind='expense' AND document_type IN ('factura','factura_compra') THEN iva END), 0),
            COALESCE(SUM(CASE WHEN kind='income' THEN net END), 0),
            COALESCE(SUM(CASE WHEN kind='expense' THEN
                CASE WHEN document_type IN ('factura','factura_compra') THEN net ELSE gross END END), 0)
         FROM movements WHERE date >= ?1 AND date < ?2",
        params![from, to],
        |r| {
            Ok(MonthTotals {
                sales_gross: r.get(0)?,
                iva_debit: r.get(1)?,
                iva_credit: r.get(2)?,
                income_net: r.get(3)?,
                costs_net: r.get(4)?,
            })
        },
    )?;
    Ok(t)
}

fn first_movement_month(conn: &Connection) -> CoreResult<Option<(i32, u32)>> {
    let d: Option<NaiveDate> = conn.query_row("SELECT MIN(date) FROM movements", [], |r| r.get(0))?;
    Ok(d.map(|d| (d.year(), d.month())))
}

fn is_filed(conn: &Connection, year: i32, month: u32) -> CoreResult<bool> {
    let n: i64 = conn.query_row(
        "SELECT count(*) FROM f29_filings WHERE year=?1 AND month=?2",
        params![year, month],
        |r| r.get(0),
    )?;
    Ok(n > 0)
}

/// Tasa PPM aplicable en `year`: según ingresos netos del año anterior en UF.
pub fn ppm_rate_for(conn: &Connection, s: &Settings, year: i32) -> CoreResult<Decimal> {
    let from = format!("{:04}-01-01", year - 1);
    let to = format!("{:04}-01-01", year);
    let prev = range_totals(conn, &from, &to)?;
    let threshold_clp = s.ppm_threshold_uf.saturating_mul(s.uf_value);
    Ok(if prev.income_net > threshold_clp {
        s.ppm_rate_high
    } else {
        s.ppm_rate_low
    })
}

/// Calcula el F29 de todos los meses desde el primer movimiento hasta `(year, month)`
/// inclusive, arrastrando el remanente de IVA. Devuelve solo los meses de `year`
/// a partir de `from_month`.
fn compute_chain(
    conn: &Connection,
    s: &Settings,
    year: i32,
    from_month: u32,
    to_month: u32,
    today: NaiveDate,
) -> CoreResult<Vec<MonthlyTax>> {
    let target_start = month_start(year, from_month)?;
    let mut cursor = match first_movement_month(conn)? {
        Some((fy, fm)) if month_start(fy, fm)? < target_start => (fy, fm),
        _ => (year, from_month),
    };
    let mut remanente = s.initial_iva_remanente.max(0);
    let mut out = Vec::new();
    let mut rate_cache: Option<(i32, Decimal)> = None;
    loop {
        let (cy, cm) = cursor;
        if (cy, cm) > (year, to_month) {
            break;
        }
        let totals = month_totals(conn, cy, cm)?;
        let (result, to_pay, rem_next) = iva_period(totals.iva_debit, totals.iva_credit, remanente);
        if (cy, cm) >= (year, from_month) {
            let rate = match rate_cache {
                Some((y, r)) if y == cy => r,
                _ => {
                    let r = ppm_rate_for(conn, s, cy)?;
                    rate_cache = Some((cy, r));
                    r
                }
            };
            let ppm = ppm_amount(totals.income_net, rate);
            let due = f29_due_date(cy, cm, s.f29_due_day);
            out.push(MonthlyTax {
                year: cy,
                month: cm,
                sales_gross: totals.sales_gross,
                iva_debit: totals.iva_debit,
                iva_credit: totals.iva_credit,
                remanente_prev: remanente,
                iva_result: result,
                iva_to_pay: to_pay,
                remanente_next: rem_next,
                ppm_base: totals.income_net,
                ppm_rate: rate,
                ppm,
                total_f29: to_pay + ppm,
                due_date: due,
                days_to_due: (due - today).num_days(),
                filed: is_filed(conn, cy, cm)?,
            });
        }
        remanente = rem_next;
        cursor = next_month(cy, cm);
    }
    Ok(out)
}

pub fn monthly_tax(
    conn: &Connection,
    s: &Settings,
    year: i32,
    month: u32,
    today: NaiveDate,
) -> CoreResult<MonthlyTax> {
    if !(1..=12).contains(&month) {
        return Err(CoreError::validation("mes inválido"));
    }
    compute_chain(conn, s, year, month, month, today)?
        .pop()
        .ok_or_else(|| CoreError::validation("sin datos"))
}

/// Desglose de los 12 meses del año.
pub fn year_breakdown(
    conn: &Connection,
    s: &Settings,
    year: i32,
    today: NaiveDate,
) -> CoreResult<Vec<MonthlyTax>> {
    compute_chain(conn, s, year, 1, 12, today)
}

/// Impuesto a la Renta estimado del año comercial `year`.
pub fn annual_tax(conn: &Connection, s: &Settings, year: i32, today: NaiveDate) -> CoreResult<AnnualTax> {
    // Meses transcurridos: año completo si ya terminó; si es el año en curso, hasta el mes actual.
    let months_elapsed: u32 = if year < today.year() {
        12
    } else if year == today.year() {
        today.month()
    } else {
        0
    };
    let months = year_breakdown(conn, s, year, today)?;
    let from = format!("{:04}-01-01", year);
    let to = format!("{:04}-01-01", year + 1);
    let totals = range_totals(conn, &from, &to)?;
    let base = totals.income_net - totals.costs_net;
    let tax = round_clp(Decimal::from(base.max(0)) * s.idpc_rate);
    // PPM pagados: meses cuyo F29 ya venció (o fue declarado).
    let ppm_paid: i64 = months
        .iter()
        .filter(|m| m.filed || m.due_date < today)
        .map(|m| m.ppm)
        .sum();
    let ppm_all: i64 = months.iter().map(|m| m.ppm).sum();
    let balance = tax - ppm_paid;

    let (projected_base, projected_ppm) = if months_elapsed > 0 {
        let factor = Decimal::from(12) / Decimal::from(months_elapsed);
        (
            round_clp(Decimal::from(base) * factor),
            round_clp(Decimal::from(ppm_all) * factor),
        )
    } else {
        (0, 0)
    };
    let projected_tax = round_clp(Decimal::from(projected_base.max(0)) * s.idpc_rate);
    let note = match s.regime {
        TaxRegime::ProPymeTransparente => {
            "Régimen transparente: la empresa no paga Primera Categoría; la utilidad tributa en el Global Complementario de los dueños.".to_string()
        }
        _ if balance < 0 => {
            "Los PPM pagados superan el impuesto estimado: posible devolución en la Operación Renta.".to_string()
        }
        _ => DISCLAIMER.to_string(),
    };
    Ok(AnnualTax {
        year,
        regime: s.regime,
        income_net: totals.income_net,
        costs_net: totals.costs_net,
        base,
        idpc_rate: s.idpc_rate,
        tax,
        ppm_paid,
        balance,
        possible_refund: balance < 0,
        months_elapsed,
        projected_base,
        projected_tax,
        projected_ppm,
        projected_balance: projected_tax - projected_ppm,
        note,
    })
}

pub fn mark_f29_filed(conn: &Connection, year: i32, month: u32, date: NaiveDate) -> CoreResult<()> {
    conn.execute(
        "INSERT INTO f29_filings(year, month, filed_date) VALUES (?1, ?2, ?3)
         ON CONFLICT(year, month) DO UPDATE SET filed_date = excluded.filed_date",
        params![year, month, date],
    )?;
    Ok(())
}

pub fn unmark_f29_filed(conn: &Connection, year: i32, month: u32) -> CoreResult<()> {
    conn.execute("DELETE FROM f29_filings WHERE year=?1 AND month=?2", params![year, month])?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::finance;
    use crate::models::*;

    fn d(y: i32, m: u32, day: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, day).unwrap()
    }

    #[test]
    fn split_iva_included_reconciles_exactly() {
        let (g, n, i) = split_iva(11_900, true, dec!(0.19));
        assert_eq!((g, n, i), (11_900, 10_000, 1_900));
        // 10.000 / 1,19 = 8.403,36 → 8.403; IVA = 1.597
        let (g, n, i) = split_iva(10_000, true, dec!(0.19));
        assert_eq!((g, n, i), (10_000, 8_403, 1_597));
        assert_eq!(n + i, g);
    }

    #[test]
    fn split_iva_not_included() {
        // 10.001 × 0,19 = 1.900,19 → 1.900
        assert_eq!(split_iva(10_001, false, dec!(0.19)), (11_901, 10_001, 1_900));
        // 50 × 0,19 = 9,5 → 10 (mitad hacia arriba)
        assert_eq!(split_iva(50, false, dec!(0.19)), (60, 50, 10));
    }

    #[test]
    fn iva_period_positive_and_negative() {
        assert_eq!(iva_period(1000, 300, 0), (700, 700, 0));
        assert_eq!(iva_period(1000, 1500, 0), (-500, 0, 500));
        assert_eq!(iva_period(1000, 300, 900), (-200, 0, 200));
        assert_eq!(iva_period(0, 0, 0), (0, 0, 0));
    }

    #[test]
    fn ppm_rounding() {
        // 1.000.000 × 0,125% = 1.250
        assert_eq!(ppm_amount(1_000_000, dec!(0.00125)), 1_250);
        // 1.004 × 0,125% = 1,255 → 1
        assert_eq!(ppm_amount(1_004, dec!(0.00125)), 1);
        assert_eq!(ppm_amount(-50, dec!(0.00125)), 0);
    }

    #[test]
    fn due_date_moves_weekend_to_monday() {
        // 20 de diciembre de 2026 es domingo → lunes 21
        assert_eq!(f29_due_date(2026, 11, 20), d(2026, 12, 21));
        // 20 de enero de 2027 es miércoles
        assert_eq!(f29_due_date(2026, 12, 20), d(2027, 1, 20));
        assert_eq!(f29_due_date(2026, 12, 12), d(2027, 1, 12));
    }

    #[test]
    fn pending_period() {
        assert_eq!(pending_f29_period(d(2026, 11, 10), 20), (2026, 10));
        assert_eq!(pending_f29_period(d(2026, 11, 25), 20), (2026, 11));
    }

    fn mov(kind: MovementKind, date: NaiveDate, amount: i64, cat: Category, doc: DocumentType) -> MovementInput {
        MovementInput {
            kind,
            date,
            amount,
            includes_iva: true,
            category: cat,
            payment_method: PaymentMethod::Debit,
            document_type: doc,
            document_number: None,
            description: String::new(),
            photo_path: None,
            garment_id: None,
        }
    }

    #[test]
    fn remanente_is_carried_across_months() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        // Septiembre: gran compra con factura → crédito 19.000, venta débito 1.900
        finance::add_movement(&conn, &s, &mov(MovementKind::Expense, d(2026, 9, 5), 119_000, Category::CompraMercaderia, DocumentType::FacturaCompra)).unwrap();
        finance::add_movement(&conn, &s, &mov(MovementKind::Income, d(2026, 9, 10), 11_900, Category::VentaTienda, DocumentType::Boleta)).unwrap();
        // Octubre: venta débito 9.500, compra con BOLETA (no da crédito)
        finance::add_movement(&conn, &s, &mov(MovementKind::Income, d(2026, 10, 3), 59_500, Category::VentaTienda, DocumentType::Boleta)).unwrap();
        finance::add_movement(&conn, &s, &mov(MovementKind::Expense, d(2026, 10, 4), 11_900, Category::OtroEgreso, DocumentType::Boleta)).unwrap();
        // Noviembre: venta débito 19.000
        finance::add_movement(&conn, &s, &mov(MovementKind::Income, d(2026, 11, 3), 119_000, Category::VentaTienda, DocumentType::Boleta)).unwrap();

        let today = d(2026, 11, 19);
        let sep = monthly_tax(&conn, &s, 2026, 9, today).unwrap();
        assert_eq!(sep.iva_debit, 1_900);
        assert_eq!(sep.iva_credit, 19_000);
        assert_eq!(sep.iva_to_pay, 0);
        assert_eq!(sep.remanente_next, 17_100);

        let oct = monthly_tax(&conn, &s, 2026, 10, today).unwrap();
        assert_eq!(oct.remanente_prev, 17_100);
        assert_eq!(oct.iva_debit, 9_500);
        assert_eq!(oct.iva_credit, 0, "las boletas de compra no dan crédito");
        assert_eq!(oct.iva_to_pay, 0);
        assert_eq!(oct.remanente_next, 7_600);

        let nov = monthly_tax(&conn, &s, 2026, 11, today).unwrap();
        assert_eq!(nov.remanente_prev, 7_600);
        assert_eq!(nov.iva_to_pay, 19_000 - 7_600);
        assert_eq!(nov.remanente_next, 0);
        // PPM = 100.000 neto × 0,125% = 125
        assert_eq!(nov.ppm, 125);
        assert_eq!(nov.total_f29, 11_400 + 125);
        assert_eq!(nov.due_date, d(2026, 12, 21));
    }

    #[test]
    fn initial_remanente_setting_is_used() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings { initial_iva_remanente: 5_000, ..Settings::default() };
        finance::add_movement(&conn, &s, &mov(MovementKind::Income, d(2026, 3, 3), 11_900, Category::VentaTienda, DocumentType::Boleta)).unwrap();
        let m = monthly_tax(&conn, &s, 2026, 3, d(2026, 3, 20)).unwrap();
        assert_eq!(m.remanente_prev, 5_000);
        assert_eq!(m.iva_to_pay, 0);
        assert_eq!(m.remanente_next, 3_100);
    }

    #[test]
    fn ppm_high_rate_when_previous_year_exceeds_threshold() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings { ppm_threshold_uf: 1, uf_value: 1_000, ..Settings::default() };
        finance::add_movement(&conn, &s, &mov(MovementKind::Income, d(2025, 6, 3), 11_900, Category::VentaTienda, DocumentType::Boleta)).unwrap();
        assert_eq!(ppm_rate_for(&conn, &s, 2026).unwrap(), s.ppm_rate_high);
        assert_eq!(ppm_rate_for(&conn, &s, 2025).unwrap(), s.ppm_rate_low);
    }

    #[test]
    fn annual_tax_and_refund() {
        let conn = crate::db::open(":memory:").unwrap();
        let s = Settings::default();
        // Ingresos netos 1.000.000, gastos con boleta 1.190.000 (costo bruto) → pérdida
        finance::add_movement(&conn, &s, &mov(MovementKind::Income, d(2026, 1, 10), 1_190_000, Category::VentaTienda, DocumentType::Boleta)).unwrap();
        finance::add_movement(&conn, &s, &mov(MovementKind::Expense, d(2026, 1, 11), 1_190_000, Category::Arriendo, DocumentType::Boleta)).unwrap();
        let a = annual_tax(&conn, &s, 2026, d(2026, 3, 1)).unwrap();
        assert_eq!(a.income_net, 1_000_000);
        assert_eq!(a.costs_net, 1_190_000);
        assert_eq!(a.base, -190_000);
        assert_eq!(a.tax, 0);
        assert_eq!(a.ppm_paid, 1_250);
        assert!(a.possible_refund);

        // Con utilidad
        finance::add_movement(&conn, &s, &mov(MovementKind::Income, d(2026, 2, 10), 2_380_000, Category::VentaTienda, DocumentType::Boleta)).unwrap();
        let a = annual_tax(&conn, &s, 2026, d(2026, 3, 1)).unwrap();
        assert_eq!(a.base, 3_000_000 - 1_190_000);
        assert_eq!(a.tax, round_clp(Decimal::from(1_810_000) * dec!(0.125)));
        assert_eq!(a.months_elapsed, 3);
        assert_eq!(a.projected_base, a.base * 4);
    }
}
